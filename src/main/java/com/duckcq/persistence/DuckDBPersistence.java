package com.duckcq.persistence;

import com.duckcq.index.DuckDBIdentityIndex;
import com.duckcq.index.DuckDBTypeIndex;
import com.duckcq.internal.BlobRowCodec;
import com.duckcq.internal.ColumnarRowCodec;
import com.duckcq.internal.ConnectionPool;
import com.duckcq.internal.IndexBulkTarget;
import com.duckcq.internal.JoinTarget;
import com.duckcq.internal.Connections;
import com.duckcq.internal.ObjectCache;
import com.duckcq.internal.ObjectTable;
import com.duckcq.internal.RowCodec;
import com.duckcq.internal.Sql;
import com.duckcq.internal.TableWriter;
import com.duckcq.layout.ColumnarLayout;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.sqlite.RequestScopeConnectionManager;
import com.googlecode.cqengine.index.sqlite.SQLitePersistence;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.persistence.support.sqlite.SQLiteObjectStore;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.duckdb.DuckDBConnection;

import java.io.Closeable;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.googlecode.cqengine.persistence.support.PersistenceFlags.READ_REQUEST;
import static com.googlecode.cqengine.query.QueryFactory.noQueryOptions;
import static com.googlecode.cqengine.query.option.FlagsEnabled.isFlagEnabled;

/**
 * Persists an {@code IndexedCollection} and its indexes in a DuckDB database, on disk or in memory.
 *
 * <p>It is a drop-in alternative to CQEngine's on-heap, off-heap and disk persistence:</p>
 * <pre>
 * // before
 * IndexedCollection&lt;Car&gt; cars = new ConcurrentIndexedCollection&lt;&gt;();
 * cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));
 *
 * // after - in memory by default, off the Java heap
 * IndexedCollection&lt;Car&gt; cars = new ConcurrentIndexedCollection&lt;&gt;(
 *         DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
 * cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
 *
 * // or persisted to a file
 * new ConcurrentIndexedCollection&lt;&gt;(
 *         DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, new File("cars.duckdb")));
 * </pre>
 *
 * <p><b>In-memory databases hold their data in native memory, not the Java heap</b>, so they are
 * limited by {@code memory_limit} (DuckDB's default is 80% of system RAM) rather than by
 * {@code -Xmx}. When that limit is reached DuckDB spills to temporary files rather than failing.</p>
 *
 * <p><b>How objects are stored.</b> By default each object is serialized into one BLOB, which
 * works for any class without any mapping code. Supplying a {@link ColumnarLayout} through
 * {@link #builder(SimpleAttribute)} instead shreds each object into one typed column per field,
 * which is what lets DuckDB compress the data column by column - usually several times smaller
 * again, and much faster to scan.</p>
 *
 * <p><b>Concurrency.</b> A single DuckDB database is opened, and each request into CQEngine gets
 * its own connection to it. Reads run concurrently with everything else, under DuckDB's own MVCC.
 * Writes are serialised against each other by default, because two DuckDB transactions which
 * modify the same table at the same time make one of them fail with a conflict error; applications
 * which coordinate their own writes can turn that off with {@link Builder#serializeWrites(boolean)}.</p>
 *
 * <p>As with CQEngine's other non-heap persistence implementations, a {@code ResultSet} obtained
 * from a collection using this persistence holds a database connection until it is closed, so
 * result sets should always be closed (ideally with try-with-resources).</p>
 */
public class DuckDBPersistence<O, A extends Comparable<A>>
        implements SQLitePersistence<O, A>, JoinTarget<O>, Closeable {

    private final SimpleAttribute<O, A> primaryKeyAttribute;
    private final String collectionName;
    private final DuckDBDatabase database;
    /** True when this persistence opened its own database and must therefore close it. */
    private final boolean ownsDatabase;
    private final ObjectTable<O, A> objectTable;
    private final int appenderThreshold;
    private final int stagingChunkRows;

    /** The identity index over the object table, remembered when the object store is created. */
    private volatile DuckDBIdentityIndex<A, O> identityIndex;
    /** Index tables registered by the DuckDBIndexes using this persistence, for bulk writing. */
    private final Map<String, IndexBulkTarget<O>> indexTables = new ConcurrentHashMap<>();
    private volatile boolean closed;

    protected DuckDBPersistence(Builder<O, A> builder) {
        this.primaryKeyAttribute = builder.primaryKeyAttribute;
        this.collectionName = builder.collectionName != null
                ? builder.collectionName
                : primaryKeyAttribute.getObjectType().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        this.appenderThreshold = builder.appenderThreshold;
        this.stagingChunkRows = builder.stagingChunkRows;

        if (builder.database != null) {
            this.database = builder.database;
            this.ownsDatabase = false;
        }
        else {
            DuckDBDatabase.Builder databaseBuilder = DuckDBDatabase.builder()
                    .serializeWrites(builder.serializeWrites)
                    .maxPooledConnections(builder.maxPooledConnections)
                    .properties(builder.properties);
            if (builder.file != null) {
                databaseBuilder.file(builder.file);
            }
            this.database = databaseBuilder.build();
            this.ownsDatabase = true;
        }

        Class<O> objectType = primaryKeyAttribute.getObjectType();
        RowCodec<O> codec = builder.columnarLayout == null
                ? new BlobRowCodec<>(objectType)
                : new ColumnarRowCodec<>(builder.columnarLayout);
        ObjectCache<A, O> cache = builder.objectCacheSize > 0
                ? new ObjectCache<>(builder.objectCacheSize)
                : ObjectCache.disabled();
        this.objectTable = new ObjectTable<>(collectionName, primaryKeyAttribute, codec, cache,
                appenderThreshold, stagingChunkRows);
    }

    /** The database holding this collection, which may hold others alongside it. */
    public DuckDBDatabase getDatabase() {
        return database;
    }

    /** The name this collection's tables are derived from. */
    public String getCollectionName() {
        return collectionName;
    }

    // ---------- Persistence contract ----------

    @Override
    public SimpleAttribute<O, A> getPrimaryKeyAttribute() {
        return primaryKeyAttribute;
    }

    @Override
    public ObjectStore<O> createObjectStore() {
        return new SQLiteObjectStore<>(this);
    }

    @Override
    public DuckDBIdentityIndex<A, O> createIdentityIndex() {
        DuckDBIdentityIndex<A, O> index = new DuckDBIdentityIndex<>(primaryKeyAttribute, objectTable,
                database::joinTargetFor);
        this.identityIndex = index;
        return index;
    }

    /**
     * The index over the object table, which is also what a whole-query push-down asks for a
     * connection with: CQEngine's connection manager resolves a persistence from the index using it.
     */
    public DuckDBIdentityIndex<A, O> getIdentityIndex() {
        return identityIndex;
    }

    /** @return true if the given index stores its data in this DuckDB database. */
    @Override
    public boolean supportsIndex(Index<O> index) {
        return index instanceof DuckDBTypeIndex;
    }

    @Override
    public void openRequestScopeResources(QueryOptions queryOptions) {
        if (queryOptions.get(ConnectionManager.class) == null) {
            queryOptions.put(ConnectionManager.class, new RequestScopeConnectionManager(this));
        }
    }

    @Override
    public void closeRequestScopeResources(QueryOptions queryOptions) {
        ConnectionManager connectionManager = queryOptions.get(ConnectionManager.class);
        if (connectionManager instanceof RequestScopeConnectionManager) {
            ((RequestScopeConnectionManager) connectionManager).close();
            queryOptions.remove(ConnectionManager.class);
        }
    }

    @Override
    public Connection getConnection(Index<?> index, QueryOptions queryOptions) {
        return database.borrowConnection(isFlagEnabled(queryOptions, READ_REQUEST));
    }

    /** An unmanaged connection for maintenance operations, which the caller must close. */
    private Connection newConnection() {
        return database.newConnection();
    }

    // ---------- Storage management ----------

    /**
     * @return the size of the DuckDB database file, or for an in-memory database the number of
     * bytes DuckDB is currently using to hold it.
     */
    /**
     * @return the size of the whole DuckDB database. When several collections share a database this
     * is their combined size, not this collection's share of it.
     */
    @Override
    public long getBytesUsed() {
        return database.getBytesUsed();
    }

    /**
     * Writes everything to the database file and reclaims space left behind by deleted rows.
     */
    @Override
    public void compact() {
        checkpoint(true);
    }

    /**
     * No-op. Unlike SQLite, DuckDB grows its file as needed and gives no way to pre-allocate it;
     * this method exists so that code written against CQEngine's SQLite persistence still compiles
     * and runs.
     */
    @Override
    public void expand(long numBytes) {
        // Intentionally empty; see javadoc.
    }

    private void checkpoint(boolean required) {
        database.checkpoint(required);
    }

    /**
     * Reorganises the index tables so that selective queries scan less of them, and checkpoints.
     *
     * <p>Index tables are written in the order objects arrive, which means the values in any given
     * block span most of the range and DuckDB cannot skip any of it: a range or equality query has
     * to scan the whole index, and that cost grows with the collection. Rewriting each index
     * ordered by value lets DuckDB's per-block minimum and maximum do their job. On a four-million
     * row index this took the scan from 4.4 ms to 0.46 ms.</p>
     *
     * <p>Worth calling after a bulk load, and after any large batch of writes. It rewrites every
     * index table, so it is not cheap and it briefly needs room for a second copy of the largest
     * one. Objects added afterwards land at the end of the table unsorted, so the benefit decays
     * gradually as the collection is modified.</p>
     */
    public void optimize() {
        Lock lock = database.getWriteLock();
        if (lock != null) {
            lock.lock();
        }
        try (Connection connection = newConnection()) {
            for (IndexBulkTarget<O> indexTable : indexTables.values()) {
                indexTable.optimize(connection);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to optimize " + describeDatabase(), e);
        }
        finally {
            if (lock != null) {
                lock.unlock();
            }
        }
        checkpoint(false);
    }

    /** Runs DuckDB's {@code ANALYZE}, refreshing the statistics its query planner uses. */
    public void analyze() {
        try (Connection connection = newConnection()) {
            Sql.execute(connection, "ANALYZE");
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to analyze " + describeDatabase(), e);
        }
    }

    /** @return the file this collection is persisted in, or null for an in-memory database. */
    public File getFile() {
        return database.getFile();
    }

    public ObjectTable<O, A> getObjectTable() {
        return objectTable;
    }

    public int getAppenderThreshold() {
        return appenderThreshold;
    }

    public int getStagingChunkRows() {
        return stagingChunkRows;
    }

    /**
     * Called by each {@link com.duckcq.index.DuckDBIndex} as it initialises, so that
     * {@link #bulkWriter()} knows which index tables it has to write.
     */
    public void registerIndexTable(IndexBulkTarget<O> indexTable) {
        indexTables.put(indexTable.getTableName(), indexTable);
    }

    // ---------- JoinTarget: what a cross-collection join needs to know ----------

    @Override
    public String objectTableName() {
        return objectTable.getTableName();
    }

    @Override
    public ObjectTable<O, A> objectTable() {
        return objectTable;
    }

    @Override
    public SimpleAttribute<O, A> primaryKeyAttribute() {
        return primaryKeyAttribute;
    }

    /**
     * @return the index table backing the given attribute, or null when the attribute is not
     * indexed in DuckDB and so cannot take part in a pushed-down join
     */
    @Override
    public String indexTableFor(Attribute<?, ?> attribute) {
        for (IndexBulkTarget<O> indexTable : indexTables.values()) {
            if (indexTable.getAttribute().equals(attribute)) {
                return indexTable.getTableName();
            }
        }
        return null;
    }

    /**
     * Opens a writer which streams objects straight into DuckDB, an order of magnitude faster than
     * adding them one at a time and without needing the whole batch in memory first.
     *
     * <pre>
     * try (DuckDBBulkWriter&lt;Car&gt; writer = persistence.bulkWriter()) {
     *     while (records.hasNext()) {
     *         writer.add(toCar(records.next()));
     *     }
     * }
     * </pre>
     *
     * <p>The objects must not already be in the collection, and the collection is only guaranteed
     * consistent once the writer is flushed or closed. See {@link DuckDBBulkWriter} for the full
     * set of trade-offs; use {@code collection.addAll(...)} when you need ordinary semantics.</p>
     *
     * <p>Add the collection's indexes before opening the writer, so that it writes them too.</p>
     */
    public DuckDBBulkWriter<O> bulkWriter() {
        if (closed) {
            throw new IllegalStateException("DuckDBPersistence has been closed: " + this);
        }
        Lock lock = database.getWriteLock();
        if (lock != null) {
            lock.lock();
        }
        try {
            // A dedicated connection, not a request-scoped one: the writer holds it open across
            // many appends, and appenders are bound to the connection which created them.
            return new DuckDBBulkWriter<>(objectTable, List.copyOf(indexTables.values()), newConnection(), lock);
        }
        catch (RuntimeException e) {
            if (lock != null) {
                lock.unlock();
            }
            throw e;
        }
    }

    /**
     * Closes the database. The persistence cannot be used afterwards.
     */
    /**
     * Closes this collection's storage. When the persistence opened its own database that database
     * is closed too; when it shares one created with {@link DuckDBDatabase}, closing the database
     * is the caller's job and this is a no-op.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ownsDatabase) {
            database.close();
        }
    }

    private String describeDatabase() {
        return database.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DuckDBPersistence<?, ?> that = (DuckDBPersistence<?, ?>) o;
        return primaryKeyAttribute.equals(that.primaryKeyAttribute)
                && collectionName.equals(that.collectionName)
                && database == that.database;
    }

    @Override
    public int hashCode() {
        return 31 * primaryKeyAttribute.hashCode() + collectionName.hashCode();
    }

    @Override
    public String toString() {
        return "DuckDBPersistence{collection=" + collectionName
                + ", primaryKey=" + primaryKeyAttribute.getAttributeName()
                + ", database=" + database + "}";
    }

    // ---------- Factory methods ----------

    /**
     * Holds the collection in an in-memory DuckDB database: off the Java heap, compressed, and
     * discarded when the persistence is closed. This is the default because the usual reason to
     * reach for this plugin is to get a large collection out of the Java heap, not to make it
     * durable.
     *
     * @see #onPrimaryKeyInFile(SimpleAttribute, File) to persist to a file instead
     */
    public static <O, A extends Comparable<A>> DuckDBPersistence<O, A> onPrimaryKey(
            SimpleAttribute<O, A> primaryKeyAttribute) {
        return builder(primaryKeyAttribute).inMemory().build();
    }

    /** Persists to a temporary DuckDB file, whose location {@link #getFile()} reports. */
    public static <O, A extends Comparable<A>> DuckDBPersistence<O, A> onPrimaryKeyInTempFile(
            SimpleAttribute<O, A> primaryKeyAttribute) {
        return builder(primaryKeyAttribute).tempFile().build();
    }

    /** Persists to the given DuckDB file, creating it if it does not exist. */
    public static <O, A extends Comparable<A>> DuckDBPersistence<O, A> onPrimaryKeyInFile(
            SimpleAttribute<O, A> primaryKeyAttribute, File file) {
        return builder(primaryKeyAttribute).file(file).build();
    }

    /** Persists to the given DuckDB file, with extra DuckDB connection settings applied. */
    public static <O, A extends Comparable<A>> DuckDBPersistence<O, A> onPrimaryKeyInFileWithProperties(
            SimpleAttribute<O, A> primaryKeyAttribute, File file, Properties properties) {
        return builder(primaryKeyAttribute).file(file).properties(properties).build();
    }

    /**
     * Same as {@link #onPrimaryKey(SimpleAttribute)}; spelled out for code which wants to state
     * that the collection is held in memory.
     */
    public static <O, A extends Comparable<A>> DuckDBPersistence<O, A> onPrimaryKeyInMemory(
            SimpleAttribute<O, A> primaryKeyAttribute) {
        return builder(primaryKeyAttribute).inMemory().build();
    }

    public static <O, A extends Comparable<A>> Builder<O, A> builder(SimpleAttribute<O, A> primaryKeyAttribute) {
        return new Builder<>(primaryKeyAttribute);
    }

    public static File createTempFile() {
        try {
            File tempFile = File.createTempFile("cqengine_", ".duckdb");
            // DuckDB wants to create the file itself; an existing empty file is not a valid database.
            if (!tempFile.delete()) {
                throw new IllegalStateException("Failed to clear temp file: " + tempFile);
            }
            tempFile.deleteOnExit();
            return tempFile;
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to create a temp file for CQEngine DuckDB persistence", e);
        }
    }

    /** Configures a {@link DuckDBPersistence}. */
    public static final class Builder<O, A extends Comparable<A>> {

        private final SimpleAttribute<O, A> primaryKeyAttribute;
        private DuckDBDatabase database;
        private String collectionName;
        private File file;
        private ColumnarLayout<O> columnarLayout;
        private int objectCacheSize;
        private int appenderThreshold = TableWriter.DEFAULT_APPENDER_THRESHOLD;
        private int stagingChunkRows = TableWriter.DEFAULT_STAGING_CHUNK_ROWS;
        private boolean serializeWrites = true;
        private int maxPooledConnections = 32;
        private final Properties properties = new Properties();

        Builder(SimpleAttribute<O, A> primaryKeyAttribute) {
            this.primaryKeyAttribute = primaryKeyAttribute;
        }

        /** Store this collection in an existing, possibly shared, database. */
        public Builder<O, A> database(DuckDBDatabase database) {
            this.database = database;
            return this;
        }

        /**
         * The name this collection's tables are derived from; defaults to the object type's simple
         * name in lower case. Only matters when several collections share a database.
         */
        public Builder<O, A> collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /** Persist to this file. */
        public Builder<O, A> file(File file) {
            this.file = file;
            return this;
        }

        /** Persist to a temporary file, deleted when the JVM exits. */
        public Builder<O, A> tempFile() {
            return file(createTempFile());
        }

        /**
         * Hold the database in memory rather than in a file. This is the default.
         *
         * <p>The data still lives outside the Java heap, in DuckDB's own memory, so it is bounded
         * by DuckDB's {@code memory_limit} setting rather than by {@code -Xmx}.</p>
         */
        public Builder<O, A> inMemory() {
            this.file = null;
            return this;
        }

        /**
         * Store objects shredded into one typed column per field, instead of one BLOB per object.
         * This is what gives DuckDB's per-column compression something to work with.
         */
        public Builder<O, A> columnarLayout(ColumnarLayout<O> columnarLayout) {
            if (columnarLayout == null) {
                this.columnarLayout = null;
                return this;
            }
            if (!columnarLayout.getObjectType().isAssignableFrom(primaryKeyAttribute.getObjectType())) {
                throw new IllegalArgumentException("The columnar layout is for " + columnarLayout.getObjectType()
                        + " but the collection holds " + primaryKeyAttribute.getObjectType());
            }
            this.columnarLayout = columnarLayout;
            return this;
        }

        /**
         * Keep up to this many recently used objects on the heap, keyed by primary key.
         * Disabled by default. A cache trades heap back for latency on repeated point lookups,
         * which DuckDB answers in a few hundred microseconds.
         */
        public Builder<O, A> objectCacheSize(int objectCacheSize) {
            this.objectCacheSize = objectCacheSize;
            return this;
        }

        /**
         * Batches larger than this are loaded through DuckDB's Appender via a temporary table;
         * smaller ones go through prepared statements. Defaults to
         * {@value TableWriter#DEFAULT_APPENDER_THRESHOLD}.
         */
        public Builder<O, A> appenderThreshold(int appenderThreshold) {
            this.appenderThreshold = Math.max(1, appenderThreshold);
            return this;
        }

        /**
         * How many rows a bulk load stages at a time before moving them into the target table.
         * This bounds the memory a large {@code addAll} needs; lower it if you run DuckDB with a
         * tight {@code memory_limit} and store wide objects.
         */
        public Builder<O, A> stagingChunkRows(int stagingChunkRows) {
            this.stagingChunkRows = Math.max(1, stagingChunkRows);
            return this;
        }

        /**
         * Caps the memory DuckDB uses for its buffer pool, e.g. {@code "512MB"}.
         *
         * <p>Worth setting. DuckDB's default limit is 80% of system RAM, and it will happily keep
         * that much cached, so the process stays large even though the data is small. With a limit
         * set, a file-backed database evicts buffers to stay under it and an in-memory one spills
         * to temporary files.</p>
         */
        public Builder<O, A> memoryLimit(String memoryLimit) {
            return property("memory_limit", memoryLimit);
        }

        /**
         * Whether write requests are serialised against each other with a mutex. On by default.
         * Turning it off lets several threads write at once, at the risk of DuckDB aborting one of
         * them with a transaction conflict error. Read requests are never blocked either way.
         */
        public Builder<O, A> serializeWrites(boolean serializeWrites) {
            this.serializeWrites = serializeWrites;
            return this;
        }

        /**
         * How many DuckDB connections to keep open for reuse between requests. Reusing connections
         * saves the cost of opening one per request; each idle connection holds a little memory.
         */
        public Builder<O, A> maxPooledConnections(int maxPooledConnections) {
            this.maxPooledConnections = Math.max(0, maxPooledConnections);
            return this;
        }

        /** Additional DuckDB settings, e.g. {@code memory_limit} or {@code threads}. */
        public Builder<O, A> properties(Properties properties) {
            this.properties.putAll(properties);
            return this;
        }

        public Builder<O, A> property(String name, String value) {
            this.properties.setProperty(name, value);
            return this;
        }

        public DuckDBPersistence<O, A> build() {
            return new DuckDBPersistence<>(this);
        }
    }
}
