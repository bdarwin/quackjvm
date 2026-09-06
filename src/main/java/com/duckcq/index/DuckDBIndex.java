package com.duckcq.index;

import com.duckcq.DuckDBFlags;
import com.duckcq.internal.DuckDBIndexCore;
import com.duckcq.internal.IndexBulkTarget;
import com.duckcq.internal.IndexTable;
import com.duckcq.internal.ObjectTable;
import com.duckcq.internal.SqlPredicate;
import com.duckcq.internal.WriteHints;
import com.duckcq.persistence.DuckDBPersistence;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.support.CloseableIterable;
import com.googlecode.cqengine.index.support.KeyStatistics;
import com.googlecode.cqengine.index.support.KeyValue;
import com.googlecode.cqengine.index.support.SortedKeyStatisticsAttributeIndex;
import com.googlecode.cqengine.persistence.Persistence;
import com.googlecode.cqengine.persistence.composite.CompositePersistence;
import com.googlecode.cqengine.persistence.support.ObjectSet;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.query.simple.Between;
import com.googlecode.cqengine.query.simple.Equal;
import com.googlecode.cqengine.query.simple.ExistsIn;
import com.googlecode.cqengine.query.simple.FilterQuery;
import com.googlecode.cqengine.query.simple.GreaterThan;
import com.googlecode.cqengine.query.simple.Has;
import com.googlecode.cqengine.query.simple.In;
import com.googlecode.cqengine.query.simple.LessThan;
import com.googlecode.cqengine.query.simple.StringStartsWith;
import com.googlecode.cqengine.resultset.ResultSet;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An index on an attribute, stored in DuckDB as a two-column {@code (objectKey, value)} table.
 *
 * <p>This is the DuckDB counterpart of CQEngine's {@code DiskIndex}, and is used the same way:</p>
 * <pre>
 * IndexedCollection&lt;Car&gt; cars = new ConcurrentIndexedCollection&lt;&gt;(
 *         DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, new File("cars.duckdb")));
 * cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
 * </pre>
 *
 * <p>The index is created and populated when it is added to the collection, and reused as-is if
 * the DuckDB file already contains it.</p>
 */
public class DuckDBIndex<A extends Comparable<A>, O, K extends Comparable<K>>
        implements SortedKeyStatisticsAttributeIndex<A, O>, DuckDBTypeIndex {

    private static final Set<Class<? extends Query>> SUPPORTED_QUERIES = Set.of(
            Equal.class, In.class, LessThan.class, GreaterThan.class, Between.class,
            StringStartsWith.class, Has.class);

    /** Objects are read back from the object store in pages of this size when building the index. */
    static final int INDEX_BUILD_PAGE_SIZE = 8192;

    private final Attribute<O, A> attribute;
    private final String tableNameSuffix;
    private final boolean artIndexes;

    private volatile State<A, O, K> state;

    protected DuckDBIndex(Attribute<O, A> attribute, String tableNameSuffix, boolean artIndexes) {
        this.attribute = attribute;
        this.tableNameSuffix = tableNameSuffix;
        this.artIndexes = artIndexes;
    }

    /** Everything which can only be known once the index has been added to a collection. */
    private static final class State<A extends Comparable<A>, O, K extends Comparable<K>> {
        final SimpleAttribute<O, K> primaryKeyAttribute;
        final ObjectTable<O, K> objectTable;
        final IndexTable<K, A> indexTable;
        final DuckDBIndexCore<A, O, K> core;

        State(SimpleAttribute<O, K> primaryKeyAttribute, ObjectTable<O, K> objectTable,
              IndexTable<K, A> indexTable, DuckDBIndexCore<A, O, K> core) {
            this.primaryKeyAttribute = primaryKeyAttribute;
            this.objectTable = objectTable;
            this.indexTable = indexTable;
            this.core = core;
        }
    }

    private State<A, O, K> state() {
        State<A, O, K> state = this.state;
        if (state == null) {
            throw new IllegalStateException("This index can only be used after it has been added to an "
                    + "IndexedCollection: " + this);
        }
        return state;
    }

    // ---------- Lifecycle ----------

    @Override
    @SuppressWarnings("unchecked")
    public void init(ObjectStore<O> objectStore, QueryOptions queryOptions) {
        DuckDBPersistence<O, K> persistence = resolvePersistence(queryOptions);
        SimpleAttribute<O, K> primaryKeyAttribute = persistence.getPrimaryKeyAttribute();
        if (primaryKeyAttribute == null) {
            throw new IllegalStateException("DuckDBIndex on attribute '" + attribute.getAttributeName()
                    + "' requires the persistence to be configured with a primary key attribute");
        }
        ObjectTable<O, K> objectTable = persistence.getObjectTable();
        IndexTable<K, A> indexTable = new IndexTable<>(persistence.getCollectionName(),
                attribute.getAttributeName(), tableNameSuffix,
                primaryKeyAttribute.getAttributeType(), attribute.getAttributeType(),
                persistence.getAppenderThreshold(), artIndexes, persistence.getStagingChunkRows());
        DuckDBIndexCore<A, O, K> core = new DuckDBIndexCore<>(attribute, objectTable, indexTable.getTableName(),
                false, this::getEffectiveIndex);
        core.setJoinResolver(persistence.getDatabase()::joinTargetFor);
        this.state = new State<>(primaryKeyAttribute, objectTable, indexTable, core);
        // Register with the persistence so that DuckDBPersistence.bulkWriter() knows to write
        // this index too.
        persistence.registerIndexTable(new IndexBulkTarget<>(indexTable, attribute));

        ConnectionManager connectionManager = DuckDBIndexes.connectionManager(queryOptions);
        Connection connection = connectionManager.getConnection(this, queryOptions);
        if (indexTable.exists(connection)) {
            // Already built in a previous run against this database file.
            return;
        }
        indexTable.create(connection);
        buildFromObjectStore(connection, objectTable, indexTable, primaryKeyAttribute, queryOptions);
    }

    /**
     * Populates a newly created index table from the objects already in the collection, reading
     * them a page at a time so that no result set is held open while the index is being written.
     */
    private void buildFromObjectStore(Connection connection, ObjectTable<O, K> objectTable,
                                      IndexTable<K, A> indexTable, SimpleAttribute<O, K> primaryKeyAttribute,
                                      QueryOptions queryOptions) {
        if (!objectTable.exists(connection)) {
            return;
        }
        K afterKey = null;
        while (true) {
            List<O> page = objectTable.readPage(connection, afterKey, INDEX_BUILD_PAGE_SIZE);
            if (page.isEmpty()) {
                return;
            }
            indexTable.write(connection, page, primaryKeyAttribute, attribute, queryOptions, true);
            afterKey = primaryKeyAttribute.getValue(page.get(page.size() - 1), queryOptions);
        }
    }

    @Override
    public void destroy(QueryOptions queryOptions) {
        State<A, O, K> state = state();
        ConnectionManager connectionManager = DuckDBIndexes.connectionManager(queryOptions);
        if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
            return;
        }
        state.indexTable.drop(connectionManager.getConnection(this, queryOptions));
    }

    // ---------- Index contract ----------

    @Override
    public Attribute<O, A> getAttribute() {
        return attribute;
    }

    @Override
    public Index<O> getEffectiveIndex() {
        return this;
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public boolean isQuantized() {
        return false;
    }

    @Override
    public boolean supportsQuery(Query<O> query, QueryOptions queryOptions) {
        if (query instanceof ExistsIn) {
            // Only claim a join if it can actually be answered in SQL; otherwise CQEngine falls
            // back to evaluating it itself.
            State<A, O, K> state = this.state;
            return state != null && state.core.canPushDownJoin(query);
        }
        return query instanceof FilterQuery || SUPPORTED_QUERIES.contains(query.getClass());
    }

    @Override
    public ResultSet<O> retrieve(Query<O> query, QueryOptions queryOptions) {
        return state().core.retrieve(query, queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    // ---------- Writes ----------

    @Override
    public boolean addAll(ObjectSet<O> objectSet, QueryOptions queryOptions) {
        try {
            State<A, O, K> state = state();
            ConnectionManager connectionManager = DuckDBIndexes.connectionManager(queryOptions);
            if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
                return false;
            }
            Connection connection = connectionManager.getConnection(this, queryOptions);
            state.indexTable.create(connection);
            // The object store has already told us whether these objects were new; if they were,
            // there is nothing to replace and the delete step can be skipped.
            boolean skipDelete = DuckDBFlags.isBulkImport(queryOptions) || WriteHints.allObjectsWereNew(queryOptions);
            long rows = state.indexTable.write(connection, objectSet, state.primaryKeyAttribute, attribute,
                    queryOptions, skipDelete);
            return rows > 0;
        }
        finally {
            objectSet.close();
        }
    }

    @Override
    public boolean removeAll(ObjectSet<O> objectSet, QueryOptions queryOptions) {
        try {
            State<A, O, K> state = state();
            ConnectionManager connectionManager = DuckDBIndexes.connectionManager(queryOptions);
            if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
                return false;
            }
            Connection connection = connectionManager.getConnection(this, queryOptions);
            state.indexTable.create(connection);
            List<K> keys = new ArrayList<>();
            for (O object : objectSet) {
                keys.add(state.primaryKeyAttribute.getValue(object, queryOptions));
            }
            return state.indexTable.deleteKeys(connection, keys) > 0;
        }
        finally {
            objectSet.close();
        }
    }

    @Override
    public void clear(QueryOptions queryOptions) {
        State<A, O, K> state = state();
        ConnectionManager connectionManager = DuckDBIndexes.connectionManager(queryOptions);
        if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
            return;
        }
        Connection connection = connectionManager.getConnection(this, queryOptions);
        state.indexTable.create(connection);
        state.indexTable.clear(connection);
    }

    // ---------- Key statistics ----------

    @Override
    public CloseableIterable<A> getDistinctKeys(QueryOptions queryOptions) {
        return state().core.distinctValues(SqlPredicate.ALWAYS_TRUE, false, queryOptions,
                DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeys(A lowerBound, boolean lowerInclusive, A upperBound,
                                                boolean upperInclusive, QueryOptions queryOptions) {
        return state().core.distinctValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), false,
                queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeysDescending(QueryOptions queryOptions) {
        return state().core.distinctValues(SqlPredicate.ALWAYS_TRUE, true, queryOptions,
                DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeysDescending(A lowerBound, boolean lowerInclusive, A upperBound,
                                                          boolean upperInclusive, QueryOptions queryOptions) {
        return state().core.distinctValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), true,
                queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public Integer getCountForKey(A key, QueryOptions queryOptions) {
        return retrieve(QueryFactory.equal(attribute, key), queryOptions).size();
    }

    @Override
    public Integer getCountOfDistinctKeys(QueryOptions queryOptions) {
        return state().core.countDistinctValues(queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyStatistics<A>> getStatisticsForDistinctKeys(QueryOptions queryOptions) {
        return state().core.keyStatistics(false, queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyStatistics<A>> getStatisticsForDistinctKeysDescending(QueryOptions queryOptions) {
        return state().core.keyStatistics(true, queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValues(QueryOptions queryOptions) {
        return state().core.keysAndValues(SqlPredicate.ALWAYS_TRUE, false, queryOptions,
                DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValues(A lowerBound, boolean lowerInclusive, A upperBound,
                                                              boolean upperInclusive, QueryOptions queryOptions) {
        return state().core.keysAndValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), false,
                queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValuesDescending(QueryOptions queryOptions) {
        return state().core.keysAndValues(SqlPredicate.ALWAYS_TRUE, true, queryOptions,
                DuckDBIndexes.connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValuesDescending(A lowerBound, boolean lowerInclusive,
                                                                        A upperBound, boolean upperInclusive,
                                                                        QueryOptions queryOptions) {
        return state().core.keysAndValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), true,
                queryOptions, DuckDBIndexes.connectionManager(queryOptions));
    }

    private SqlPredicate range(A lowerBound, boolean lowerInclusive, A upperBound, boolean upperInclusive) {
        return DuckDBIndexes.rangePredicate(attribute, state().core.valueColumn(),
                lowerBound, lowerInclusive, upperBound, upperInclusive);
    }

    @SuppressWarnings("unchecked")
    private DuckDBPersistence<O, K> resolvePersistence(QueryOptions queryOptions) {
        Persistence<O, ?> persistence = (Persistence<O, ?>) queryOptions.get(Persistence.class);
        if (persistence == null) {
            throw new IllegalStateException("A required Persistence object was not supplied in query options");
        }
        if (persistence instanceof DuckDBPersistence) {
            return (DuckDBPersistence<O, K>) persistence;
        }
        if (persistence instanceof CompositePersistence) {
            Persistence<O, ?> forIndex = ((CompositePersistence<O, ?>) persistence).getPersistenceForIndex(this);
            if (forIndex instanceof DuckDBPersistence) {
                return (DuckDBPersistence<O, K>) forIndex;
            }
        }
        throw new IllegalStateException("A DuckDBIndex on attribute '" + attribute.getAttributeName()
                + "' requires the IndexedCollection to be configured with DuckDBPersistence, but found: "
                + persistence);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return attribute.equals(((DuckDBIndex<?, ?, ?>) o).attribute);
    }

    @Override
    public int hashCode() {
        return 31 * getClass().hashCode() + attribute.hashCode();
    }

    @Override
    public String toString() {
        return "DuckDBIndex{attribute=" + attribute.getAttributeName() + "}";
    }

    // ---------- Factory methods ----------

    /**
     * Creates an index on the given attribute. The collection it is added to must be configured
     * with {@link DuckDBPersistence}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A extends Comparable<A>, O> DuckDBIndex<A, O, ? extends Comparable<?>> onAttribute(
            Attribute<O, A> attribute) {
        return new DuckDBIndex(attribute, "", false);
    }

    /**
     * Creates an index on the given attribute which additionally builds DuckDB ART indexes over
     * its columns.
     *
     * <p>This makes lookups into a very large index table faster, but the ART structures typically
     * cost several times more disk and memory than the indexed data itself, so only use this for
     * attributes whose lookups are both frequent and latency-critical. The plain
     * {@link #onAttribute(Attribute)} form relies on DuckDB scanning the compressed columns, which
     * is usually only a few milliseconds even for very large tables.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A extends Comparable<A>, O> DuckDBIndex<A, O, ? extends Comparable<?>> onAttributeWithArtIndex(
            Attribute<O, A> attribute) {
        return new DuckDBIndex(attribute, "", true);
    }

    /**
     * Creates an index on the given attribute, storing it in a table with the given name suffix.
     * Useful when two indexes on the same attribute must coexist in one database file.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <A extends Comparable<A>, O> DuckDBIndex<A, O, ? extends Comparable<?>> onAttributeWithSuffix(
            Attribute<O, A> attribute, String tableNameSuffix, boolean artIndexes) {
        return new DuckDBIndex(attribute, tableNameSuffix, artIndexes);
    }
}
