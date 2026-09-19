package io.quackjvm.cqengine.persistence;

import io.quackjvm.core.sql.JoinPair;

import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.ConnectionPool;
import io.quackjvm.cqengine.internal.JoinTarget;
import io.quackjvm.cqengine.query.Join;
import io.quackjvm.core.sql.Materialization;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlQuery;
import io.quackjvm.core.sql.SqlRow;
import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import org.duckdb.DuckDBConnection;

import java.io.Closeable;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import io.quackjvm.core.metrics.Diagnosis;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.metrics.Timer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One DuckDB database, shared by any number of CQEngine collections.
 *
 * <p>A collection created through a database of its own can only be queried on its own. Collections
 * which share a database live in the same DuckDB instance as separate tables, which means they can
 * be <b>joined</b> - something CQEngine cannot do between two {@code IndexedCollection}s, because
 * its {@code existsIn()} evaluates one foreign lookup per object.</p>
 *
 * <pre>
 * DuckDBDatabase database = DuckDBDatabase.inMemory();
 *
 * IndexedCollection&lt;Car&gt; cars = database.collection(Car.CAR_ID)
 *         .columnarLayout(ColumnarLayout.ofRecord(Car.class))
 *         .build();
 * IndexedCollection&lt;Owner&gt; owners = database.collection(Owner.OWNER_ID)
 *         .columnarLayout(ColumnarLayout.ofRecord(Owner.class))
 *         .build();
 * </pre>
 *
 * <p>Each collection gets its own tables, named after it: {@code cq_car}, {@code cqidx_car_price}
 * and so on. The database is closed once, which closes every collection in it.</p>
 */
public final class DuckDBDatabase implements Closeable {

    private final File file;
    private final DuckDBConnection rootConnection;
    private final ConnectionPool connectionPool;
    private final Map<String, ManagedMaterialization> materializations = new ConcurrentHashMap<>();
    private final boolean serializeWrites;
    /**
     * Guards writes which touch the database as a whole rather than one collection's tables.
     * Writes to a collection take that collection's own lock instead - see
     * {@link DuckDBPersistence#getWriteLock()} - so two collections sharing a database do not
     * serialise against each other.
     */
    private final Lock databaseWriteLock = new ReentrantLock(true);

    /** Every persistence created from this database, by collection name. */
    private final Map<String, DuckDBPersistence<?, ?>> persistences = new ConcurrentHashMap<>();
    /** Collections created through {@link CollectionBuilder}, so that joins can resolve them. */
    /**
     * Collections to their persistence, keyed by <b>identity</b>.
     *
     * <p>Not by equality, and this matters more than it looks. An {@code IndexedCollection} is a
     * {@code Set}, so its {@code equals} and {@code hashCode} are {@code AbstractSet}'s - which
     * call {@code size()}, which for a DuckDB-backed collection is a database query. A
     * {@code ConcurrentHashMap} calls {@code equals} on a key <em>while holding the bin's lock</em>,
     * so registering a collection would run a query under that lock: one thread waiting on the
     * collection's write lock while holding the bin, another holding that write lock and waiting
     * for the bin. Eight collections built at once deadlocked on exactly that.</p>
     *
     * <p>Identity is also the semantics we want: two collection instances are two collections,
     * however equal their contents.</p>
     */
    private final Map<CollectionKey, DuckDBPersistence<?, ?>> byCollection = new ConcurrentHashMap<>();

    /** An identity key, so that looking a collection up never touches its contents. */
    private static final class CollectionKey {
        private final com.googlecode.cqengine.IndexedCollection<?> collection;

        CollectionKey(com.googlecode.cqengine.IndexedCollection<?> collection) {
            this.collection = collection;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CollectionKey && ((CollectionKey) other).collection == collection;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(collection);
        }
    }

    private volatile boolean closed;

    /** Always present; only recorded into when metrics are on. */
    private final QuackMetrics metrics = new QuackMetrics();
    private final boolean metricsEnabled;

    private DuckDBDatabase(File file, Properties properties, boolean serializeWrites, int maxPooledConnections,
                           boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
        this.file = file;
        this.serializeWrites = serializeWrites;
        Properties effective = new Properties();
        // Stream large result sets instead of materialising them in the JVM heap.
        effective.setProperty("jdbc_stream_results", "true");
        effective.putAll(properties);
        String url = "jdbc:duckdb:" + (file == null ? "" : file.getAbsolutePath());
        try {
            this.rootConnection = (DuckDBConnection) DriverManager.getConnection(url, effective);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open DuckDB database: " + url, e);
        }
        this.connectionPool = new ConnectionPool(rootConnection, maxPooledConnections,
                metricsEnabled ? metrics : null);
        if (metricsEnabled) {
            metrics.watch(rootConnection);
        }
    }

    // ---------- Factory methods ----------

    /** A database held in memory, discarded when it is closed. */
    public static DuckDBDatabase inMemory() {
        return builder().build();
    }

    /** A database persisted to the given file. */
    public static DuckDBDatabase inFile(File file) {
        return builder().file(file).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Configures a {@link DuckDBDatabase}. */
    public static final class Builder {
        private File file;
        private boolean serializeWrites = true;
        private int maxPooledConnections = 32;
        private boolean metrics = true;
        private boolean profileStatements = true;
        private final Properties properties = new Properties();

        Builder() {
        }

        /** Persist to this file. Omit for an in-memory database. */
        public Builder file(File file) {
            this.file = file;
            return this;
        }

        /** Cap the memory DuckDB uses for its buffer pool, e.g. {@code "512MB"}. Worth setting. */
        public Builder memoryLimit(String memoryLimit) {
            return property("memory_limit", memoryLimit);
        }

        public Builder property(String name, String value) {
            properties.setProperty(name, value);
            return this;
        }

        public Builder properties(Properties properties) {
            this.properties.putAll(properties);
            return this;
        }

        /** Whether write requests are serialised against each other. On by default. */
        public Builder serializeWrites(boolean serializeWrites) {
            this.serializeWrites = serializeWrites;
            return this;
        }

        public Builder maxPooledConnections(int maxPooledConnections) {
            this.maxPooledConnections = Math.max(0, maxPooledConnections);
            return this;
        }

        /**
         * Whether to record {@link DuckDBDatabase#metrics()}. On by default: timing a request or a
         * statement costs tens of nanoseconds, against 48 microseconds for DuckDB's cheapest query.
         */
        public Builder metrics(boolean metrics) {
            this.metrics = metrics;
            return this;
        }

        /**
         * Whether heavy statements - a millisecond or more on average - have one real execution a
         * minute profiled by DuckDB, as {@code EXPLAIN ANALYZE} would show it, in
         * {@code metrics().profiles()}. On by default with metrics; about 3% of that one execution.
         */
        public Builder profileStatements(boolean profileStatements) {
            this.profileStatements = profileStatements;
            return this;
        }

        public DuckDBDatabase build() {
            DuckDBDatabase database = new DuckDBDatabase(file, properties, serializeWrites, maxPooledConnections, metrics);
            database.metrics.setProfiling(metrics && profileStatements);
            return database;
        }
    }

    // ---------- Collections ----------

    /**
     * Starts building a collection stored in this database.
     *
     * @param primaryKeyAttribute an attribute which uniquely identifies each object
     */
    public <O, A extends Comparable<A>> CollectionBuilder<O, A> collection(
            SimpleAttribute<O, A> primaryKeyAttribute) {
        return new CollectionBuilder<>(this, primaryKeyAttribute);
    }

    /** Configures one collection within a shared database. */
    public static final class CollectionBuilder<O, A extends Comparable<A>> {
        private final DuckDBDatabase database;
        private final SimpleAttribute<O, A> primaryKeyAttribute;
        private String name;
        private ColumnarLayout<O> columnarLayout;
        private int objectCacheSize;
        private int appenderThreshold = TableWriter.DEFAULT_APPENDER_THRESHOLD;
        private int stagingChunkRows = TableWriter.DEFAULT_STAGING_CHUNK_ROWS;

        CollectionBuilder(DuckDBDatabase database, SimpleAttribute<O, A> primaryKeyAttribute) {
            this.database = database;
            this.primaryKeyAttribute = primaryKeyAttribute;
        }

        /**
         * The name this collection's tables are given. Defaults to the object type's simple name in
         * lower case, so a collection of {@code Car} is stored in {@code cq_car}. Give two
         * collections of the same type different names.
         */
        public CollectionBuilder<O, A> name(String name) {
            this.name = name;
            return this;
        }

        /** Shred objects into one typed column per field. Required for joins to filter in SQL. */
        public CollectionBuilder<O, A> columnarLayout(ColumnarLayout<O> columnarLayout) {
            this.columnarLayout = columnarLayout;
            return this;
        }

        public CollectionBuilder<O, A> objectCacheSize(int objectCacheSize) {
            this.objectCacheSize = objectCacheSize;
            return this;
        }

        public CollectionBuilder<O, A> appenderThreshold(int appenderThreshold) {
            this.appenderThreshold = Math.max(1, appenderThreshold);
            return this;
        }

        public CollectionBuilder<O, A> stagingChunkRows(int stagingChunkRows) {
            this.stagingChunkRows = Math.max(1, stagingChunkRows);
            return this;
        }

        /** Creates the persistence, for use with your own {@code IndexedCollection}. */
        public DuckDBPersistence<O, A> buildPersistence() {
            String collectionName = name != null
                    ? name
                    : primaryKeyAttribute.getObjectType().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            DuckDBPersistence<O, A> persistence = DuckDBPersistence.builder(primaryKeyAttribute)
                    .database(database)
                    .collectionName(collectionName)
                    .columnarLayout(columnarLayout)
                    .objectCacheSize(objectCacheSize)
                    .appenderThreshold(appenderThreshold)
                    .stagingChunkRows(stagingChunkRows)
                    .build();
            database.register(collectionName, persistence);
            return persistence;
        }

        /**
         * Creates the persistence and an {@code IndexedCollection} using it, registering the pair so
         * that joins from other collections in this database can find it.
         *
         * <p>The collection returned sends whole {@code and}/{@code or}/{@code not} queries to
         * DuckDB in one statement rather than letting CQEngine intersect them in Java; see
         * {@link DuckDBIndexedCollection}. It is an ordinary {@code IndexedCollection} in every
         * other respect.</p>
         */
        public com.googlecode.cqengine.IndexedCollection<O> build() {
            DuckDBPersistence<O, A> persistence = buildPersistence();
            com.googlecode.cqengine.IndexedCollection<O> collection =
                    new DuckDBIndexedCollection<>(persistence);
            database.byCollection.put(new CollectionKey(collection), persistence);
            // The object table exists by now (the collection's construction created it), so the
            // view can be pointed at it.
            database.createViewFor(persistence);
            return collection;
        }
    }

    void register(String name, DuckDBPersistence<?, ?> persistence) {
        DuckDBPersistence<?, ?> existing = persistences.putIfAbsent(name, persistence);
        if (existing != null && existing != persistence) {
            throw new IllegalStateException("This database already holds a collection named '" + name
                    + "'. Give one of them a different name with .name(...).");
        }
    }

    /**
     * Creates a SQL view named after the collection, so that {@link #sql} can be written against
     * {@code vehicle} rather than the internal {@code cq_vehicle}. Called once the collection's
     * table exists.
     */
    void createViewFor(DuckDBPersistence<?, ?> persistence) {
        try (Connection connection = newConnection()) {
            Sql.execute(connection, "CREATE OR REPLACE VIEW " + Sql.quote(persistence.collectionName())
                    + " AS SELECT * FROM " + Sql.quote(persistence.objectTableName()));
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to create the SQL view for collection '"
                    + persistence.collectionName() + "'", e);
        }
    }

    /** Associates a collection with its persistence, so joins can resolve it. */
    public void register(com.googlecode.cqengine.IndexedCollection<?> collection,
                         DuckDBPersistence<?, ?> persistence) {
        byCollection.put(new CollectionKey(collection), persistence);
    }

    /**
     * Resolves a CQEngine collection to the DuckDB tables behind it, so that a join against it can
     * be pushed into SQL. Returns null when the collection is not stored in this database - a plain
     * on-heap collection, or one in a different database - and the join then falls back to
     * CQEngine's own evaluation.
     */
    @SuppressWarnings("unchecked")
    public <F> JoinTarget<F> joinTargetFor(com.googlecode.cqengine.IndexedCollection<F> collection) {
        if (collection == null) {
            return null;
        }
        DuckDBPersistence<?, ?> persistence = byCollection.get(new CollectionKey(collection));
        if (persistence == null) {
            persistence = persistenceByReflection(collection);
        }
        return persistence == null ? null : (JoinTarget<F>) persistence;
    }

    /**
     * Recovers the persistence from a collection which was constructed directly rather than through
     * {@link CollectionBuilder}. CQEngine keeps it in a protected field with no accessor.
     */
    private DuckDBPersistence<?, ?> persistenceByReflection(
            com.googlecode.cqengine.IndexedCollection<?> collection) {
        try {
            java.lang.reflect.Field field =
                    com.googlecode.cqengine.ConcurrentIndexedCollection.class.getDeclaredField("persistence");
            field.setAccessible(true);
            Object value = field.get(collection);
            if (value instanceof DuckDBPersistence<?, ?> duckDBPersistence
                    && duckDBPersistence.getDatabase() == this) {
                byCollection.put(new CollectionKey(collection), duckDBPersistence);
                return duckDBPersistence;
            }
            return null;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** The persistence backing the given collection, or null if it is not stored in this database. */
    public DuckDBPersistence<?, ?> persistenceFor(com.googlecode.cqengine.IndexedCollection<?> collection) {
        return byCollection.get(new CollectionKey(collection));
    }

    public Collection<DuckDBPersistence<?, ?>> getPersistences() {
        return List.copyOf(persistences.values());
    }

    // ---------- Querying across collections ----------

    /**
     * Joins two collections stored in this database, returning the matched objects in pairs.
     *
     * <pre>
     * try (Stream&lt;JoinPair&lt;Vehicle, Person&gt;&gt; pairs = database.join(vehicles, people)
     *         .on(Vehicle.OWNER_ID, Person.PERSON_ID)
     *         .whereRight(equal(Person.COUNTRY, "FR"))
     *         .stream()) {
     *     ...
     * }
     * </pre>
     *
     * @throws IllegalArgumentException if either collection is not stored in this database
     */
    public <L, R> Join<L, R> join(com.googlecode.cqengine.IndexedCollection<L> leftCollection,
                                  com.googlecode.cqengine.IndexedCollection<R> rightCollection) {
        JoinTarget<L> left = requireTarget(leftCollection, "left");
        JoinTarget<R> right = requireTarget(rightCollection, "right");
        return new Join<>(left, right, () -> borrowConnection(true));
    }

    private <T> JoinTarget<T> requireTarget(com.googlecode.cqengine.IndexedCollection<T> collection, String side) {
        JoinTarget<T> target = joinTargetFor(collection);
        if (target == null) {
            throw new IllegalArgumentException("The " + side + " collection is not stored in this database, "
                    + "so it cannot be joined here. Create it with database.collection(...), or use "
                    + "CQEngine's existsIn() which works across any collections.");
        }
        return target;
    }

    /**
     * Runs arbitrary SQL against this database - joins, {@code GROUP BY}, aggregates, window
     * functions - over the tables holding the collections.
     *
     * <pre>
     * try (Stream&lt;SqlRow&gt; rows = database.sql(
     *         "SELECT p.country, count(*) AS vehicles, avg(v.price) AS avgPrice "
     *       + "FROM " + database.tableName(vehicles) + " v "
     *       + "JOIN " + database.tableName(people) + " p ON v.ownerId = p.personId "
     *       + "GROUP BY 1 ORDER BY 2 DESC")) {
     *     rows.forEach(row -&gt; ...);
     * }
     * </pre>
     *
     * <p>This is only useful for collections stored with a
     * {@link io.quackjvm.core.layout.ColumnarLayout}, whose fields are real columns. A collection stored
     * as BLOBs has just a key and an opaque blob.</p>
     *
     * <p>The returned stream holds a connection and must be closed.</p>
     */
    /**
     * Runs a query and reads its result without rebuilding any objects - the fastest way to ask a
     * question of a collection.
     *
     * <pre>
     * double total = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
     *                        .scalar(Double.class);
     * List&lt;String&gt; makes = database.query("SELECT DISTINCT make FROM car").list(String.class);
     * List&lt;MakeStats&gt; stats = database.query(
     *         "SELECT make, count(*), avg(price) FROM car GROUP BY 1").records(MakeStats.class);
     * </pre>
     *
     * <p>See {@link Rows} for what can be done with the result, and {@link #describe()} for the
     * names to use.</p>
     */
    public Rows query(String sql, Object... parameters) {
        if (closed) {
            throw new IllegalStateException("This DuckDBDatabase has been closed: " + this);
        }
        return Rows.of(borrowConnection(true), sql, parameters);
    }

    public java.util.stream.Stream<SqlRow> sql(String sql, Object... parameters) {
        try {
            return SqlQuery.stream(borrowConnection(true), sql, parameters);
        }
        catch (RuntimeException e) {
            // A wrong table or column name is the usual mistake here, and DuckDB's message alone
            // does not say what the right ones would have been.
            throw new IllegalStateException(e.getMessage() + "\n\n" + describe(), e);
        }
    }

    /**
     * The name to use for a collection in {@link #sql} - the same name the collection was created
     * with, which exists as a SQL view over its table.
     *
     * <p>Usually you can just write the name directly: a collection of {@code Vehicle} is
     * {@code vehicle} unless you named it something else. This method is for when you would rather
     * not hard-code it.</p>
     *
     * @throws IllegalArgumentException if the collection is not stored in this database
     */
    public String table(com.googlecode.cqengine.IndexedCollection<?> collection) {
        return requireTarget(collection, "given").collectionName();
    }

    /** @deprecated use {@link #table}, which returns the collection's view name. */
    @Deprecated
    public String tableName(com.googlecode.cqengine.IndexedCollection<?> collection) {
        return table(collection);
    }

    /**
     * The SQL column holding the given attribute's value, checked against the collection's actual
     * columns so that a mismatch is reported here rather than as a SQL error.
     *
     * @throws IllegalArgumentException if the collection has no such column - the message lists the
     *                                  columns it does have
     */
    public String column(com.googlecode.cqengine.IndexedCollection<?> collection,
                         com.googlecode.cqengine.attribute.Attribute<?, ?> attribute) {
        JoinTarget<?> target = requireTarget(collection, "given");
        String attributeName = attribute.getAttributeName();
        for (ColumnDef column : target.objectTable().getColumns()) {
            if (column.getName().equals(attributeName)) {
                return column.getName();
            }
        }
        throw new IllegalArgumentException("Collection '" + target.collectionName() + "' has no column '"
                + attributeName + "'. Its columns are " + columns(collection) + "."
                + (target.objectTable().isColumnar()
                        ? " Attribute names must match the names in its ColumnarLayout."
                        : " It stores objects as BLOBs, so its fields are not columns; build it with"
                          + " a ColumnarLayout to query them in SQL."));
    }

    /** The columns of a collection, as they appear in SQL. */
    public List<String> columns(com.googlecode.cqengine.IndexedCollection<?> collection) {
        JoinTarget<?> target = requireTarget(collection, "given");
        List<String> names = new ArrayList<>();
        for (ColumnDef column : target.objectTable().getColumns()) {
            names.add(column.getName());
        }
        return names;
    }

    /**
     * A readable listing of everything {@link #sql} can query: each collection, the name to use for
     * it, and its columns. Print this when writing a query.
     */
    // ---------- Materializations ----------

    /**
     * Precomputes a query into a table, so that requests are served from the answer rather than
     * from the data. See {@link Materialization}.
     *
     * <pre>
     * ManagedMaterialization topModels = database.materialize("top_models")
     *         .as("SELECT region, make, sum(price) AS revenue FROM sale GROUP BY 1, 2")
     *         .build();
     *
     * database.query("SELECT * FROM top_models WHERE region = ?", "EMEA").records(Row.class);
     * topModels.refresh();
     * </pre>
     */
    public MaterializationBuilder materialize(String name) {
        return new MaterializationBuilder(this, name);
    }

    /** The materializations built through this database, by name. */
    public Map<String, ManagedMaterialization> getMaterializations() {
        return Map.copyOf(materializations);
    }

    public static final class MaterializationBuilder {
        private final DuckDBDatabase database;
        private final String name;
        private String sql;

        private MaterializationBuilder(DuckDBDatabase database, String name) {
            this.database = database;
            this.name = name;
        }

        /** The query to precompute. */
        public MaterializationBuilder as(String sql) {
            this.sql = sql;
            return this;
        }

        /** Registers it and builds the table if it is not there already. */
        public ManagedMaterialization build() {
            ManagedMaterialization managed =
                    new ManagedMaterialization(database, new Materialization(name, sql));
            managed.createIfAbsent();
            database.materializations.put(name, managed);
            return managed;
        }
    }

    /** A {@link Materialization} bound to this database, so it needs no connection passed in. */
    public static final class ManagedMaterialization {
        private final DuckDBDatabase database;
        private final Materialization materialization;

        private ManagedMaterialization(DuckDBDatabase database, Materialization materialization) {
            this.database = database;
            this.materialization = materialization;
        }

        public String getName() {
            return materialization.getName();
        }

        public String getSql() {
            return materialization.getSql();
        }

        public java.time.Instant getBuiltAt() {
            return materialization.getBuiltAt();
        }

        public boolean isOlderThan(java.time.Duration age) {
            return materialization.isOlderThan(age);
        }

        /** Rebuilds it atomically; readers never see it missing. */
        public void refresh() {
            withConnection(materialization::refresh);
        }

        /**
         * Folds new rows in without rebuilding, by appending the result of a query computing the
         * same aggregate over only those rows. See {@link Materialization#appendDelta}: the
         * measures must be additive and panels must re-aggregate.
         */
        public long appendDelta(String deltaSql, Object... parameters) {
            return withConnectionReturning(
                    connection -> materialization.appendDelta(connection, deltaSql, parameters));
        }

        public void refreshIfOlderThan(java.time.Duration age) {
            withConnection(connection -> materialization.refreshIfOlderThan(connection, age));
        }

        public long rowCount() {
            return withConnectionReturning(materialization::rowCount);
        }

        public boolean exists() {
            return withConnectionReturning(materialization::exists);
        }

        /** Drops the table and forgets it. */
        public void drop() {
            withConnection(materialization::drop);
            database.materializations.remove(getName());
        }

        private void createIfAbsent() {
            withConnection(materialization::createIfAbsent);
        }

        private void withConnection(java.util.function.Consumer<Connection> action) {
            // An unmanaged connection: a refresh runs DDL and must not hold a request-scoped one.
            try (Connection connection = database.newConnection()) {
                action.accept(connection);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open a connection for "
                        + materialization, e);
            }
        }

        private <T> T withConnectionReturning(java.util.function.Function<Connection, T> action) {
            try (Connection connection = database.newConnection()) {
                return action.apply(connection);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open a connection for "
                        + materialization, e);
            }
        }

        @Override
        public String toString() {
            return materialization.toString();
        }
    }

    public String describe() {
        StringBuilder description = new StringBuilder("DuckDB database ")
                .append(file == null ? "(in memory)" : file).append(", queryable with sql():\n");
        if (persistences.isEmpty()) {
            return description.append("  (no collections yet)").toString();
        }
        for (DuckDBPersistence<?, ?> persistence : persistences.values()) {
            description.append("  ").append(persistence.collectionName());
            description.append("  [").append(persistence.getPrimaryKeyAttribute().getObjectType().getSimpleName());
            description.append(persistence.objectTable().isColumnar() ? ", columnar]" : ", BLOB]");
            description.append('\n');
            if (persistence.objectTable().isColumnar()) {
                description.append("      columns: ");
                for (java.util.Iterator<ColumnDef> i = persistence.objectTable().getColumns().iterator();
                     i.hasNext(); ) {
                    ColumnDef column = i.next();
                    description.append(column.getName()).append(' ').append(column.getSqlType());
                    if (i.hasNext()) description.append(", ");
                }
            }
            else {
                description.append("      columns: objectKey, value (a serialized blob - not queryable;")
                        .append(" build this collection with a ColumnarLayout to query its fields)");
            }
            description.append('\n');
        }
        return description.toString();
    }

    // ---------- Connections ----------

    Connection borrowConnection(boolean readRequest) {
        return borrowConnection(readRequest, databaseWriteLock, SQL_SCOPE);
    }

    /** The scope under which requests made through {@link #sql}, {@link #query} and joins are recorded. */
    static final String SQL_SCOPE = "sql";

    /**
     * @param writeLock the lock a write request should hold for its duration, or null not to
     *                  serialise. A collection passes its own, so that collections sharing this
     *                  database write concurrently - two different tables cannot conflict in
     *                  DuckDB, verified with eight threads creating, indexing and writing their
     *                  own tables at once.
     */
    Connection borrowConnection(boolean readRequest, Lock writeLock, String scope) {
        if (closed) {
            throw new IllegalStateException("This DuckDBDatabase has been closed: " + this);
        }
        Timer requestTimer = metricsEnabled
                ? metrics.timer(readRequest ? QuackMetrics.REQUEST_READ : QuackMetrics.REQUEST_WRITE, scope)
                : null;
        if (!serializeWrites || readRequest || writeLock == null) {
            return Connections.managed(connectionPool.borrow(), connectionPool, null, requestTimer);
        }
        lock(writeLock, scope);
        try {
            return Connections.managed(connectionPool.borrow(), connectionPool, writeLock, requestTimer);
        }
        catch (RuntimeException e) {
            writeLock.unlock();
            throw e;
        }
    }

    /** Takes a write lock, recording how long that took under the given scope. */
    void lock(Lock writeLock, String scope) {
        if (!metricsEnabled) {
            writeLock.lock();
            return;
        }
        long startedAt = System.nanoTime();
        writeLock.lock();
        metrics.timer(QuackMetrics.WRITE_LOCK_WAIT, scope).stop(startedAt);
    }

    /**
     * What this database has been doing: request and statement timings, write-lock waits,
     * conflicts, the connection pool and statement cache, and DuckDB's memory and threads. Take two
     * snapshots and subtract them for an interval, and give that to {@link Diagnosis} to be told
     * where it is choking.
     */
    public QuackMetrics metrics() {
        return metrics;
    }

    /** An unmanaged connection which the caller must close. */
    Connection newConnection() {
        try {
            return rootConnection.duplicate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open a DuckDB connection to " + this, e);
        }
    }

    /**
     * How many prepared statements were served from cache and how many reached DuckDB, as
     * {@code {hits, misses}}. A miss costs DuckDB about 200 microseconds; a hit costs nothing.
     */
    public long[] getStatementCacheStats() {
        return connectionPool.getStatementCacheStats();
    }

    Lock getWriteLock() {
        return serializeWrites ? databaseWriteLock : null;
    }

    boolean isSerializeWrites() {
        return serializeWrites;
    }

    public File getFile() {
        return file;
    }

    public boolean isClosed() {
        return closed;
    }

    // ---------- Maintenance ----------

    /** Size of the database file, or the memory DuckDB reports for an in-memory database. */
    public long getBytesUsed() {
        if (file != null) {
            checkpoint(false);
            File writeAheadLog = new File(file.getAbsolutePath() + ".wal");
            return file.length() + (writeAheadLog.exists() ? writeAheadLog.length() : 0);
        }
        try (Connection connection = newConnection()) {
            return Sql.queryLong(connection,
                    "SELECT coalesce(sum(memory_usage_bytes), 0) FROM duckdb_memory()", List.of());
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to measure DuckDB memory usage", e);
        }
    }

    /** Flushes to disk and reclaims space left by deleted rows. */
    public void compact() {
        checkpoint(true);
    }

    void checkpoint(boolean required) {
        try (Connection connection = newConnection()) {
            Sql.execute(connection, "CHECKPOINT");
        }
        catch (SQLException | RuntimeException e) {
            if (required) {
                throw new IllegalStateException("Failed to checkpoint " + this
                        + ". DuckDB cannot checkpoint while a transaction is open, which usually means "
                        + "a ResultSet from one of its collections has not been closed.", e);
            }
        }
    }

    /** Reorganises every collection's index tables; see {@link DuckDBPersistence#optimize()}. */
    public void optimize() {
        List<DuckDBPersistence<?, ?>> all = new ArrayList<>(persistences.values());
        for (DuckDBPersistence<?, ?> persistence : all) {
            persistence.optimize();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connectionPool.close();
        try {
            rootConnection.close();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to close " + this, e);
        }
    }

    @Override
    public String toString() {
        return "DuckDBDatabase{" + (file == null ? ":memory:" : file)
                + ", collections=" + persistences.keySet() + "}";
    }
}
