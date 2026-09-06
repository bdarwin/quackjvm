package com.duckcq.persistence;

import com.duckcq.internal.ConnectionPool;
import com.duckcq.internal.JoinTarget;
import com.duckcq.query.Join;
import com.duckcq.query.SqlQuery;
import com.duckcq.query.SqlRow;
import com.duckcq.internal.Connections;
import com.duckcq.internal.Sql;
import com.duckcq.internal.TableWriter;
import com.duckcq.layout.ColumnarLayout;
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
    private final boolean serializeWrites;
    private final Lock writeLock = new ReentrantLock(true);

    /** Every persistence created from this database, by collection name. */
    private final Map<String, DuckDBPersistence<?, ?>> persistences = new ConcurrentHashMap<>();
    /** Collections created through {@link CollectionBuilder}, so that joins can resolve them. */
    private final Map<Object, DuckDBPersistence<?, ?>> byCollection = new ConcurrentHashMap<>();

    private volatile boolean closed;

    private DuckDBDatabase(File file, Properties properties, boolean serializeWrites, int maxPooledConnections) {
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
        this.connectionPool = new ConnectionPool(rootConnection, maxPooledConnections);
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

        public DuckDBDatabase build() {
            return new DuckDBDatabase(file, properties, serializeWrites, maxPooledConnections);
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
            database.byCollection.put(collection, persistence);
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

    /** Associates a collection with its persistence, so joins can resolve it. */
    public void register(com.googlecode.cqengine.IndexedCollection<?> collection,
                         DuckDBPersistence<?, ?> persistence) {
        byCollection.put(collection, persistence);
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
        DuckDBPersistence<?, ?> persistence = byCollection.get(collection);
        if (persistence == null) {
            persistence = persistenceByReflection(collection);
        }
        return persistence == null ? null : (JoinTarget<F>) persistence;
    }

    /**
     * Recovers the persistence from a collection which was constructed directly rather than through
     * {@link CollectionBuilder}. CQEngine keeps it in a protected field with no accessor.
     */
    private DuckDBPersistence<?, ?> persistenceByReflection(Object collection) {
        try {
            java.lang.reflect.Field field =
                    com.googlecode.cqengine.ConcurrentIndexedCollection.class.getDeclaredField("persistence");
            field.setAccessible(true);
            Object value = field.get(collection);
            if (value instanceof DuckDBPersistence<?, ?> duckDBPersistence
                    && duckDBPersistence.getDatabase() == this) {
                byCollection.put(collection, duckDBPersistence);
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
        return byCollection.get(collection);
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
     * {@link com.duckcq.layout.ColumnarLayout}, whose fields are real columns. A collection stored
     * as BLOBs has just a key and an opaque blob.</p>
     *
     * <p>The returned stream holds a connection and must be closed.</p>
     */
    public java.util.stream.Stream<SqlRow> sql(String sql, Object... parameters) {
        return SqlQuery.stream(borrowConnection(true), sql, parameters);
    }

    /**
     * The name of the table holding a collection's objects, for use in {@link #sql}.
     *
     * @throws IllegalArgumentException if the collection is not stored in this database
     */
    public String tableName(com.googlecode.cqengine.IndexedCollection<?> collection) {
        JoinTarget<?> target = joinTargetFor(collection);
        if (target == null) {
            throw new IllegalArgumentException("That collection is not stored in this database");
        }
        return target.objectTableName();
    }

    // ---------- Connections ----------

    Connection borrowConnection(boolean readRequest) {
        if (closed) {
            throw new IllegalStateException("This DuckDBDatabase has been closed: " + this);
        }
        if (!serializeWrites || readRequest) {
            return Connections.managed(connectionPool.borrow(), connectionPool, null);
        }
        writeLock.lock();
        try {
            return Connections.managed(connectionPool.borrow(), connectionPool, writeLock);
        }
        catch (RuntimeException e) {
            writeLock.unlock();
            throw e;
        }
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

    Lock getWriteLock() {
        return serializeWrites ? writeLock : null;
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
