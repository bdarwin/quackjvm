package com.duckcq.internal;

import com.googlecode.concurrenttrees.common.LazyIterator;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.attribute.SimpleNullableAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.support.CloseableIterable;
import com.googlecode.cqengine.index.support.CloseableIterator;
import com.googlecode.cqengine.index.support.CloseableRequestResources;
import com.googlecode.cqengine.index.support.CloseableRequestResources.CloseableResourceGroup;
import com.googlecode.cqengine.index.support.KeyStatistics;
import com.googlecode.cqengine.index.support.KeyValue;
import com.googlecode.cqengine.index.support.KeyValueMaterialized;
import com.googlecode.cqengine.index.support.LazyCloseableIterator;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.query.simple.FilterQuery;
import com.googlecode.cqengine.query.simple.In;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The read path shared by both kinds of DuckDB index: the identity index over the object table,
 * and attribute indexes over their own {@code (objectKey, value)} tables.
 *
 * <p>The important thing this class does is <b>materialise objects with a single SQL statement</b>.
 * CQEngine's SQLite indexes resolve each matching key into an object with its own point query,
 * which is fine for SQLite but pathological for DuckDB: DuckDB spends a few hundred microseconds
 * on any query, so fetching 1000 objects one at a time takes ~200ms. Pushing the key set into
 * the object lookup as a semi-join brings the same retrieval down to a few milliseconds.</p>
 */
public final class DuckDBIndexCore<A extends Comparable<A>, O, K> {

    /** Retrieval cost of a DuckDB index; higher than any on-heap index, so those are preferred. */
    public static final int INDEX_RETRIEVAL_COST = 90;
    public static final int INDEX_RETRIEVAL_COST_FILTERING = INDEX_RETRIEVAL_COST + 1;

    /** How many keys are materialised per statement when a query cannot be pushed into SQL. */
    static final int KEY_BATCH_SIZE = 1024;

    private final Attribute<O, A> attribute;
    private final ObjectTable<O, K> objectTable;
    private final String indexTableName;
    private final boolean identity;
    /** Resolves a foreign collection to its DuckDB tables, so that joins can be pushed into SQL. */
    private volatile JoinResolver joinResolver;
    /** Alias of the object table in generated SQL; the same alias as the index table when identity. */
    private final String objectAlias;
    private final Supplier<Index<O>> effectiveIndex;

    /**
     * @param attribute      the indexed attribute
     * @param objectTable    the table holding the objects themselves
     * @param indexTableName the table holding {@code (objectKey, value)} pairs; for the identity
     *                       index this is the object table itself
     * @param identity       true when the indexed attribute is the primary key, so the index table
     *                       is the object table and the "value" column is the key column
     */
    public DuckDBIndexCore(Attribute<O, A> attribute, ObjectTable<O, K> objectTable, String indexTableName,
                           boolean identity, Supplier<Index<O>> effectiveIndex) {
        this.attribute = attribute;
        this.objectTable = objectTable;
        this.indexTableName = indexTableName;
        this.identity = identity;
        this.objectAlias = identity ? "i" : "o";
        this.effectiveIndex = effectiveIndex;
    }

    /** Looks up the DuckDB tables backing another CQEngine collection. */
    public interface JoinResolver {
        <F> JoinTarget<F> targetFor(com.googlecode.cqengine.IndexedCollection<F> collection);
    }

    public void setJoinResolver(JoinResolver joinResolver) {
        this.joinResolver = joinResolver;
    }

    public ObjectTable<O, K> getObjectTable() {
        return objectTable;
    }

    /**
     * Whether an {@code existsIn} join against another collection can be answered in SQL.
     *
     * <p>It can when the foreign collection is stored in the same DuckDB database and its
     * restriction translates - otherwise CQEngine evaluates the join itself, one foreign lookup per
     * object, which is correct but far slower.</p>
     */
    public boolean canPushDownJoin(Query<O> query) {
        return joinSql(query) != null;
    }

    /** Builds the whole join as one statement, or returns null if it cannot be expressed. */
    private SqlFragment joinSql(Query<O> query) {
        JoinResolver resolver = this.joinResolver;
        if (resolver == null) {
            return null;
        }
        ExistsInQuery<O, Object, Object> existsIn = ExistsInQuery.of(query);
        if (existsIn == null) {
            return null;
        }
        JoinTarget<Object> foreign = resolver.targetFor(existsIn.getForeignCollection());
        if (foreign == null) {
            return null;
        }
        SqlFragment foreignKeyValues = ForeignQueryTranslator.foreignKeyValues(
                foreign, existsIn.getForeignKeyAttribute(), existsIn.getForeignRestrictions());
        if (foreignKeyValues == null) {
            return null;
        }
        // The local side: which of this collection's objects hold one of those key values.
        String localKeys;
        if (identity) {
            localKeys = ObjectTable.keyColumn(objectAlias) + " IN (" + foreignKeyValues.getSql() + ")";
        }
        else {
            localKeys = ObjectTable.keyColumn(objectAlias) + " IN (SELECT " + keyColumn()
                    + " FROM " + indexFrom() + " WHERE " + valueColumn()
                    + " IN (" + foreignKeyValues.getSql() + "))";
        }
        return new SqlFragment(localKeys, foreignKeyValues.getParameters());
    }

    public String getIndexTableName() {
        return indexTableName;
    }

    /** The value column of the index table, qualified with alias {@code i}. */
    public String valueColumn() {
        return identity ? "i." + Sql.quote(ObjectTable.KEY_COLUMN) : "i." + Sql.quote(IndexTable.VALUE_COLUMN);
    }

    public String keyColumn() {
        return "i." + Sql.quote(ObjectTable.KEY_COLUMN);
    }

    private String indexFrom() {
        return Sql.quote(indexTableName) + " i";
    }

    // ---------- SQL builders ----------

    /** Selects whole objects matching the predicate, in one statement. */
    String objectSelectSql(SqlPredicate predicate) {
        String selectFrom = "SELECT " + objectTable.selectList(objectAlias) + " FROM "
                + Sql.quote(objectTable.getTableName()) + " " + objectAlias;
        if (identity) {
            return selectFrom + predicate.toWhereClause();
        }
        // Push the key set into the object lookup as a semi-join, so the whole retrieval is
        // one statement rather than one statement per matching key.
        String matchingKeys = "SELECT " + keyColumn() + " FROM " + indexFrom() + predicate.toWhereClause();
        return selectFrom + " WHERE " + ObjectTable.keyColumn(objectAlias) + " IN (" + matchingKeys + ")";
    }

    String countSql(SqlPredicate predicate, boolean distinct) {
        String countExpression = distinct ? "count(DISTINCT " + keyColumn() + ")" : "count(*)";
        return "SELECT " + countExpression + " FROM " + indexFrom() + predicate.toWhereClause();
    }

    String distinctValuesSql(SqlPredicate predicate, boolean descending) {
        return "SELECT DISTINCT " + valueColumn() + " FROM " + indexFrom() + predicate.toWhereClause()
                + " ORDER BY 1 " + (descending ? "DESC" : "ASC");
    }

    String valueCountsSql(boolean descending) {
        return "SELECT " + valueColumn() + ", count(*) FROM " + indexFrom()
                + " GROUP BY 1 ORDER BY 1 " + (descending ? "DESC" : "ASC");
    }

    String keysAndValuesSql(SqlPredicate predicate, boolean descending) {
        String order = " ORDER BY 1 " + (descending ? "DESC" : "ASC");
        if (identity) {
            return "SELECT " + valueColumn() + ", " + objectTable.selectList(objectAlias)
                    + " FROM " + Sql.quote(objectTable.getTableName()) + " " + objectAlias
                    + predicate.toWhereClause() + order;
        }
        return "SELECT " + valueColumn() + ", " + objectTable.selectList(objectAlias)
                + " FROM " + indexFrom() + " JOIN " + Sql.quote(objectTable.getTableName()) + " " + objectAlias
                + " ON " + keyColumn() + " = " + ObjectTable.keyColumn(objectAlias)
                + (predicate.isAlwaysTrue() ? "" : " WHERE " + predicate.getSql()) + order;
    }

    /** Renders the predicate for this index's value column, rewriting it for the identity case. */
    public SqlPredicate predicateFor(Query<O> query) {
        return SqlPredicate.render(query, valueColumn());
    }

    // ---------- Retrieval ----------

    public ResultSet<O> retrieve(Query<O> query, QueryOptions queryOptions, ConnectionManager connectionManager) {
        CloseableResourceGroup resources = CloseableRequestResources.forQueryOptions(queryOptions).addGroup();
        SqlFragment join = joinSql(query);
        if (join != null) {
            return new JoinResultSet(query, join, queryOptions, connectionManager, resources);
        }
        if (query instanceof FilterQuery) {
            @SuppressWarnings("unchecked")
            FilterQuery<O, A> filterQuery = (FilterQuery<O, A>) query;
            return new FilterQueryResultSet(filterQuery, query, queryOptions, connectionManager, resources);
        }
        return new PushedDownResultSet(query, predicateFor(query), queryOptions, connectionManager, resources);
    }

    private boolean hasAtMostOneValuePerObject() {
        return identity || attribute instanceof SimpleAttribute || attribute instanceof SimpleNullableAttribute;
    }

    private Connection connection(ConnectionManager connectionManager, QueryOptions queryOptions) {
        return connectionManager.getConnection(effectiveIndex.get(), queryOptions);
    }

    /** A result set whose query was translated into SQL and answered entirely by DuckDB. */
    private final class PushedDownResultSet extends ResultSet<O> {
        private final Query<O> query;
        private final SqlPredicate predicate;
        private final QueryOptions queryOptions;
        private final ConnectionManager connectionManager;
        private final CloseableResourceGroup resources;
        private int mergeCost = -1;

        PushedDownResultSet(Query<O> query, SqlPredicate predicate, QueryOptions queryOptions,
                            ConnectionManager connectionManager, CloseableResourceGroup resources) {
            this.query = query;
            this.predicate = predicate;
            this.queryOptions = queryOptions;
            this.connectionManager = connectionManager;
            this.resources = resources;
        }

        @Override
        public Iterator<O> iterator() {
            Connection connection = connection(connectionManager, queryOptions);
            return objectIterator(connection, objectSelectSql(predicate), predicate.getParameters(), resources);
        }

        @Override
        public boolean contains(O object) {
            Connection connection = connection(connectionManager, queryOptions);
            K key = objectTable.getPrimaryKeyAttribute().getValue(object, queryOptions);
            SqlPredicate withKey = predicate.and(keyColumn() + " = ?", key);
            return Sql.queryLong(connection,
                    "SELECT count(*) FROM (SELECT 1 FROM " + indexFrom() + withKey.toWhereClause() + " LIMIT 1)",
                    withKey.getParameters()) > 0;
        }

        @Override
        public boolean matches(O object) {
            return query.matches(object, queryOptions);
        }

        @Override
        public Query<O> getQuery() {
            return query;
        }

        @Override
        public QueryOptions getQueryOptions() {
            return queryOptions;
        }

        @Override
        public int getRetrievalCost() {
            return INDEX_RETRIEVAL_COST;
        }

        @Override
        public int getMergeCost() {
            if (mergeCost < 0) {
                Connection connection = connection(connectionManager, queryOptions);
                mergeCost = (int) Math.min(Integer.MAX_VALUE,
                        Sql.queryLong(connection, countSql(predicate, false), predicate.getParameters()));
            }
            return mergeCost;
        }

        @Override
        public int size() {
            Connection connection = connection(connectionManager, queryOptions);
            boolean atMostOneValuePerObject = hasAtMostOneValuePerObject();
            boolean disjointInQuery = query instanceof In && ((In<?, ?>) query).isDisjoint();
            boolean distinct = !(atMostOneValuePerObject || disjointInQuery);
            return (int) Math.min(Integer.MAX_VALUE,
                    Sql.queryLong(connection, countSql(predicate, distinct), predicate.getParameters()));
        }

        @Override
        public void close() {
            resources.close();
        }
    }

    /**
     * A result set for an {@code existsIn} join which was translated into a single SQL statement:
     * a semi-join against the foreign collection's tables, rather than one foreign lookup per
     * object as CQEngine would otherwise do.
     */
    private final class JoinResultSet extends ResultSet<O> {
        private final Query<O> query;
        private final SqlFragment joinCondition;
        private final QueryOptions queryOptions;
        private final ConnectionManager connectionManager;
        private final CloseableResourceGroup resources;
        private int mergeCost = -1;

        JoinResultSet(Query<O> query, SqlFragment joinCondition, QueryOptions queryOptions,
                      ConnectionManager connectionManager, CloseableResourceGroup resources) {
            this.query = query;
            this.joinCondition = joinCondition;
            this.queryOptions = queryOptions;
            this.connectionManager = connectionManager;
            this.resources = resources;
        }

        private String selectObjects() {
            return "SELECT " + objectTable.selectList(objectAlias) + " FROM "
                    + Sql.quote(objectTable.getTableName()) + " " + objectAlias
                    + " WHERE " + joinCondition.getSql();
        }

        private String countMatching() {
            return "SELECT count(*) FROM " + Sql.quote(objectTable.getTableName()) + " " + objectAlias
                    + " WHERE " + joinCondition.getSql();
        }

        @Override
        public Iterator<O> iterator() {
            Connection connection = connection(connectionManager, queryOptions);
            return objectIterator(connection, selectObjects(), joinCondition.getParameters(), resources);
        }

        @Override
        public boolean contains(O object) {
            Connection connection = connection(connectionManager, queryOptions);
            K key = objectTable.getPrimaryKeyAttribute().getValue(object, queryOptions);
            List<Object> parameters = new ArrayList<>(joinCondition.getParameters());
            parameters.add(key);
            return Sql.queryLong(connection, countMatching() + " AND "
                    + ObjectTable.keyColumn(objectAlias) + " = ?", parameters) > 0;
        }

        @Override
        public boolean matches(O object) {
            return query.matches(object, queryOptions);
        }

        @Override
        public Query<O> getQuery() {
            return query;
        }

        @Override
        public QueryOptions getQueryOptions() {
            return queryOptions;
        }

        @Override
        public int getRetrievalCost() {
            return INDEX_RETRIEVAL_COST;
        }

        @Override
        public int getMergeCost() {
            if (mergeCost < 0) {
                mergeCost = size();
            }
            return mergeCost;
        }

        @Override
        public int size() {
            Connection connection = connection(connectionManager, queryOptions);
            return (int) Math.min(Integer.MAX_VALUE,
                    Sql.queryLong(connection, countMatching(), joinCondition.getParameters()));
        }

        @Override
        public void close() {
            resources.close();
        }
    }

    /**
     * A result set for a {@link FilterQuery}, whose predicate is arbitrary Java code and so cannot
     * be translated into SQL. The index table is streamed, values are tested on-heap, and matching
     * objects are then fetched in batches rather than one at a time.
     */
    private final class FilterQueryResultSet extends ResultSet<O> {
        private final FilterQuery<O, A> filterQuery;
        private final Query<O> query;
        private final QueryOptions queryOptions;
        private final ConnectionManager connectionManager;
        private final CloseableResourceGroup resources;

        FilterQueryResultSet(FilterQuery<O, A> filterQuery, Query<O> query, QueryOptions queryOptions,
                             ConnectionManager connectionManager, CloseableResourceGroup resources) {
            this.filterQuery = filterQuery;
            this.query = query;
            this.queryOptions = queryOptions;
            this.connectionManager = connectionManager;
            this.resources = resources;
        }

        @Override
        public Iterator<O> iterator() {
            return new BatchMaterialisingIterator(matchingKeys(), queryOptions, connectionManager);
        }

        private Iterator<K> matchingKeys() {
            Connection connection = connection(connectionManager, queryOptions);
            String sql = "SELECT " + keyColumn() + ", " + valueColumn() + " FROM " + indexFrom();
            java.sql.ResultSet resultSet = openQuery(connection, sql, List.of(), resources);
            // An object appears once per attribute value, so keys only need de-duplicating when
            // the attribute can hold more than one value.
            Set<K> alreadyReturned = hasAtMostOneValuePerObject() ? null : new LinkedHashSet<>();
            return new LazyIterator<K>() {
                @Override
                protected K computeNext() {
                    try {
                        while (resultSet.next()) {
                            K key = objectTable.readKey(resultSet, 1);
                            A value = DuckDBTypes.read(resultSet, 2, attribute.getAttributeType());
                            if (filterQuery.matchesValue(value, queryOptions)
                                    && (alreadyReturned == null || alreadyReturned.add(key))) {
                                return key;
                            }
                        }
                        return endOfData();
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to read index entries", e);
                    }
                }
            };
        }

        @Override
        public boolean contains(O object) {
            K key = objectTable.getPrimaryKeyAttribute().getValue(object, queryOptions);
            Connection connection = connection(connectionManager, queryOptions);
            String sql = "SELECT " + valueColumn() + " FROM " + indexFrom() + " WHERE " + keyColumn() + " = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                DuckDBTypes.bind(statement, 1, key);
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        A value = DuckDBTypes.read(resultSet, 1, attribute.getAttributeType());
                        if (filterQuery.matchesValue(value, queryOptions)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to evaluate filter query", e);
            }
        }

        @Override
        public boolean matches(O object) {
            return query.matches(object, queryOptions);
        }

        @Override
        public Query<O> getQuery() {
            return query;
        }

        @Override
        public QueryOptions getQueryOptions() {
            return queryOptions;
        }

        @Override
        public int getRetrievalCost() {
            return INDEX_RETRIEVAL_COST_FILTERING;
        }

        @Override
        public int getMergeCost() {
            Connection connection = connection(connectionManager, queryOptions);
            return (int) Math.min(Integer.MAX_VALUE,
                    Sql.queryLong(connection, countSql(SqlPredicate.ALWAYS_TRUE, false), List.of()));
        }

        @Override
        public int size() {
            int count = 0;
            for (Iterator<K> keys = matchingKeys(); keys.hasNext(); keys.next()) {
                count++;
            }
            return count;
        }

        @Override
        public void close() {
            resources.close();
        }
    }

    /** Materialises objects a batch of keys at a time, to amortise DuckDB's per-query overhead. */
    private final class BatchMaterialisingIterator extends LazyIterator<O> {
        private final Iterator<K> keys;
        private final QueryOptions queryOptions;
        private final ConnectionManager connectionManager;
        private Iterator<O> currentBatch = java.util.Collections.emptyIterator();

        BatchMaterialisingIterator(Iterator<K> keys, QueryOptions queryOptions, ConnectionManager connectionManager) {
            this.keys = keys;
            this.queryOptions = queryOptions;
            this.connectionManager = connectionManager;
        }

        @Override
        protected O computeNext() {
            while (!currentBatch.hasNext()) {
                if (!keys.hasNext()) {
                    return endOfData();
                }
                List<Object> batch = new ArrayList<>(KEY_BATCH_SIZE);
                while (keys.hasNext() && batch.size() < KEY_BATCH_SIZE) {
                    batch.add(keys.next());
                }
                currentBatch = fetchObjects(batch);
            }
            return currentBatch.next();
        }

        private Iterator<O> fetchObjects(List<Object> batch) {
            Connection connection = connection(connectionManager, queryOptions);
            String sql = "SELECT " + objectTable.selectList("o") + " FROM " + Sql.quote(objectTable.getTableName())
                    + " o WHERE " + ObjectTable.keyColumn("o") + " IN " + Sql.placeholders(batch.size());
            List<O> objects = new ArrayList<>(batch.size());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                Sql.bindAll(statement, batch, 1);
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        objects.add(objectTable.readObject(resultSet, 1));
                    }
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to materialise a batch of " + batch.size() + " objects", e);
            }
            return objects.iterator();
        }
    }

    // ---------- Key statistics ----------

    public CloseableIterable<A> distinctValues(SqlPredicate predicate, boolean descending, QueryOptions queryOptions,
                                               ConnectionManager connectionManager) {
        CloseableResourceGroup resources = CloseableRequestResources.forQueryOptions(queryOptions).addGroup();
        String sql = distinctValuesSql(predicate, descending);
        return () -> {
            Connection connection = connection(connectionManager, queryOptions);
            java.sql.ResultSet resultSet = openQuery(connection, sql, predicate.getParameters(), resources);
            return new LazyCloseableIterator<A>() {
                @Override
                protected A computeNext() {
                    try {
                        return resultSet.next()
                                ? DuckDBTypes.read(resultSet, 1, attribute.getAttributeType())
                                : endOfData();
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to read distinct keys", e);
                    }
                }

                @Override
                public void close() {
                    resources.close();
                }
            };
        };
    }

    public CloseableIterable<KeyStatistics<A>> keyStatistics(boolean descending, QueryOptions queryOptions,
                                                             ConnectionManager connectionManager) {
        CloseableResourceGroup resources = CloseableRequestResources.forQueryOptions(queryOptions).addGroup();
        String sql = valueCountsSql(descending);
        return () -> {
            Connection connection = connection(connectionManager, queryOptions);
            java.sql.ResultSet resultSet = openQuery(connection, sql, List.of(), resources);
            return new LazyCloseableIterator<KeyStatistics<A>>() {
                @Override
                protected KeyStatistics<A> computeNext() {
                    try {
                        if (!resultSet.next()) {
                            return endOfData();
                        }
                        A key = DuckDBTypes.read(resultSet, 1, attribute.getAttributeType());
                        return new KeyStatistics<>(key, resultSet.getInt(2));
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to read key statistics", e);
                    }
                }

                @Override
                public void close() {
                    resources.close();
                }
            };
        };
    }

    public CloseableIterable<KeyValue<A, O>> keysAndValues(SqlPredicate predicate, boolean descending,
                                                           QueryOptions queryOptions, ConnectionManager connectionManager) {
        CloseableResourceGroup resources = CloseableRequestResources.forQueryOptions(queryOptions).addGroup();
        String sql = keysAndValuesSql(predicate, descending);
        return () -> {
            Connection connection = connection(connectionManager, queryOptions);
            java.sql.ResultSet resultSet = openQuery(connection, sql, predicate.getParameters(), resources);
            return new LazyCloseableIterator<KeyValue<A, O>>() {
                @Override
                protected KeyValue<A, O> computeNext() {
                    try {
                        if (!resultSet.next()) {
                            return endOfData();
                        }
                        A key = DuckDBTypes.read(resultSet, 1, attribute.getAttributeType());
                        O object = objectTable.readObject(resultSet, 2);
                        return new KeyValueMaterialized<>(key, object);
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to read keys and values", e);
                    }
                }

                @Override
                public void close() {
                    resources.close();
                }
            };
        };
    }

    public int countDistinctValues(QueryOptions queryOptions, ConnectionManager connectionManager) {
        Connection connection = connection(connectionManager, queryOptions);
        return (int) Sql.queryLong(connection,
                "SELECT count(DISTINCT " + valueColumn() + ") FROM " + indexFrom(), List.of());
    }

    // ---------- Streaming helpers ----------

    /** Streams whole objects out of DuckDB without materialising the result set on the heap. */
    public Iterator<O> objectIterator(Connection connection, String sql, List<Object> parameters,
                                      CloseableResourceGroup resources) {
        java.sql.ResultSet resultSet = openQuery(connection, sql, parameters, resources);
        return new LazyIterator<O>() {
            @Override
            protected O computeNext() {
                try {
                    return resultSet.next() ? objectTable.readObject(resultSet, 1) : endOfData();
                }
                catch (SQLException e) {
                    throw new IllegalStateException("Failed to read objects from DuckDB", e);
                }
            }
        };
    }

    /**
     * Runs a query, registering both the statement and its result set with the request's
     * resource group so that they are closed when CQEngine closes the request.
     */
    static java.sql.ResultSet openQuery(Connection connection, String sql, List<Object> parameters,
                                        CloseableResourceGroup resources) {
        Sql.STATEMENTS.incrementAndGet();
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            Sql.bindAll(statement, parameters, 1);
            java.sql.ResultSet resultSet = statement.executeQuery();
            PreparedStatement toClose = statement;
            resources.add((Closeable) () -> {
                Sql.closeQuietly(resultSet);
                Sql.closeQuietly(toClose);
            });
            return resultSet;
        }
        catch (SQLException e) {
            Sql.closeQuietly(statement);
            throw new IllegalStateException("Failed to execute: " + sql, e);
        }
    }
}
