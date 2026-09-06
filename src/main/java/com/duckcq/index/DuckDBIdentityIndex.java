package com.duckcq.index;

import com.duckcq.internal.DuckDBIndexCore;
import com.duckcq.DuckDBFlags;
import com.duckcq.internal.ObjectTable;
import com.duckcq.internal.SqlPredicate;
import com.duckcq.internal.TableWriter;
import com.duckcq.internal.WriteHints;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.sqlite.SQLiteIdentityIndex;
import com.googlecode.cqengine.index.support.CloseableIterable;
import com.googlecode.cqengine.index.support.KeyStatistics;
import com.googlecode.cqengine.index.support.KeyValue;
import com.googlecode.cqengine.persistence.support.ObjectSet;
import com.googlecode.cqengine.persistence.support.ObjectStore;
import com.googlecode.cqengine.query.Query;
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
 * The index over the primary key which also <i>is</i> the object store: it owns the
 * {@code cq_objects} table, and answers queries on the primary key attribute directly from it.
 *
 * <p>It extends CQEngine's {@link SQLiteIdentityIndex} - overriding all of its behaviour - because
 * CQEngine's query engine recognises that type and registers it as a queryable index of the
 * collection. Every method here goes to DuckDB; nothing is inherited but the type.</p>
 */
public class DuckDBIdentityIndex<A extends Comparable<A>, O> extends SQLiteIdentityIndex<A, O>
        implements DuckDBTypeIndex {

    private static final Set<Class<? extends Query>> SUPPORTED_QUERIES = Set.of(
            Equal.class, In.class, LessThan.class, GreaterThan.class, Between.class,
            StringStartsWith.class, Has.class);

    private final SimpleAttribute<O, A> primaryKeyAttribute;
    private final ObjectTable<O, A> objectTable;
    private final DuckDBIndexCore<A, O, A> core;

    public DuckDBIdentityIndex(SimpleAttribute<O, A> primaryKeyAttribute, ObjectTable<O, A> objectTable) {
        this(primaryKeyAttribute, objectTable, null);
    }

    public DuckDBIdentityIndex(SimpleAttribute<O, A> primaryKeyAttribute, ObjectTable<O, A> objectTable,
                               DuckDBIndexCore.JoinResolver joinResolver) {
        super(primaryKeyAttribute);
        this.primaryKeyAttribute = primaryKeyAttribute;
        this.objectTable = objectTable;
        this.core = new DuckDBIndexCore<>(primaryKeyAttribute, objectTable, objectTable.getTableName(), true,
                this::getEffectiveIndex);
        this.core.setJoinResolver(joinResolver);
    }

    public ObjectTable<O, A> getObjectTable() {
        return objectTable;
    }

    @Override
    public Attribute<O, A> getAttribute() {
        return primaryKeyAttribute;
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
            return core.canPushDownJoin(query);
        }
        return query instanceof FilterQuery || SUPPORTED_QUERIES.contains(query.getClass());
    }

    @Override
    public SimpleAttribute<A, O> getForeignKeyAttribute() {
        return new SimpleAttribute<>(primaryKeyAttribute.getAttributeType(), primaryKeyAttribute.getObjectType()) {
            @Override
            public O getValue(A key, QueryOptions queryOptions) {
                return objectTable.get(connection(queryOptions), key);
            }
        };
    }

    // ---------- Reads ----------

    @Override
    public ResultSet<O> retrieve(Query<O> query, QueryOptions queryOptions) {
        return core.retrieve(query, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeys(QueryOptions queryOptions) {
        return core.distinctValues(SqlPredicate.ALWAYS_TRUE, false, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeys(A lowerBound, boolean lowerInclusive, A upperBound,
                                                boolean upperInclusive, QueryOptions queryOptions) {
        return core.distinctValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), false,
                queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeysDescending(QueryOptions queryOptions) {
        return core.distinctValues(SqlPredicate.ALWAYS_TRUE, true, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<A> getDistinctKeysDescending(A lowerBound, boolean lowerInclusive, A upperBound,
                                                          boolean upperInclusive, QueryOptions queryOptions) {
        return core.distinctValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), true,
                queryOptions, connectionManager(queryOptions));
    }

    @Override
    public Integer getCountForKey(A key, QueryOptions queryOptions) {
        return retrieve(com.googlecode.cqengine.query.QueryFactory.equal(primaryKeyAttribute, key), queryOptions).size();
    }

    @Override
    public Integer getCountOfDistinctKeys(QueryOptions queryOptions) {
        return core.countDistinctValues(queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyStatistics<A>> getStatisticsForDistinctKeys(QueryOptions queryOptions) {
        return core.keyStatistics(false, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyStatistics<A>> getStatisticsForDistinctKeysDescending(QueryOptions queryOptions) {
        return core.keyStatistics(true, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValues(QueryOptions queryOptions) {
        return core.keysAndValues(SqlPredicate.ALWAYS_TRUE, false, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValues(A lowerBound, boolean lowerInclusive, A upperBound,
                                                              boolean upperInclusive, QueryOptions queryOptions) {
        return core.keysAndValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), false,
                queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValuesDescending(QueryOptions queryOptions) {
        return core.keysAndValues(SqlPredicate.ALWAYS_TRUE, true, queryOptions, connectionManager(queryOptions));
    }

    @Override
    public CloseableIterable<KeyValue<A, O>> getKeysAndValuesDescending(A lowerBound, boolean lowerInclusive,
                                                                        A upperBound, boolean upperInclusive,
                                                                        QueryOptions queryOptions) {
        return core.keysAndValues(range(lowerBound, lowerInclusive, upperBound, upperInclusive), true,
                queryOptions, connectionManager(queryOptions));
    }

    // ---------- Writes ----------

    @Override
    public void init(ObjectStore<O> objectStore, QueryOptions queryOptions) {
        // The object store this index is asked to build itself from is the very table it owns,
        // so there is nothing to copy: creating the table is all that is needed.
        objectTable.create(connection(queryOptions));
    }

    @Override
    public boolean addAll(ObjectSet<O> objectSet, QueryOptions queryOptions) {
        try {
            ConnectionManager connectionManager = connectionManager(queryOptions);
            if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
                return false;
            }
            Connection connection = connectionManager.getConnection(this, queryOptions);
            objectTable.create(connection);
            TableWriter.WriteResult result = objectTable.write(connection, objectSet, queryOptions,
                    DuckDBFlags.isBulkImport(queryOptions));
            // Let the attribute indexes know whether they can skip their delete-before-insert.
            WriteHints.setAllObjectsWereNew(queryOptions, result.rowsReplaced == 0);
            return result.rowsWritten - result.rowsReplaced > 0;
        }
        finally {
            objectSet.close();
        }
    }

    @Override
    public boolean removeAll(ObjectSet<O> objectSet, QueryOptions queryOptions) {
        try {
            ConnectionManager connectionManager = connectionManager(queryOptions);
            if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
                return false;
            }
            Connection connection = connectionManager.getConnection(this, queryOptions);
            objectTable.create(connection);
            List<A> keys = new ArrayList<>();
            for (O object : objectSet) {
                keys.add(primaryKeyAttribute.getValue(object, queryOptions));
            }
            WriteHints.clear(queryOptions);
            return objectTable.delete(connection, keys) > 0;
        }
        finally {
            objectSet.close();
        }
    }

    @Override
    public void clear(QueryOptions queryOptions) {
        ConnectionManager connectionManager = connectionManager(queryOptions);
        if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
            return;
        }
        Connection connection = connectionManager.getConnection(this, queryOptions);
        objectTable.create(connection);
        objectTable.clear(connection);
    }

    @Override
    public void destroy(QueryOptions queryOptions) {
        ConnectionManager connectionManager = connectionManager(queryOptions);
        if (!connectionManager.isApplyUpdateForIndexEnabled(this)) {
            return;
        }
        objectTable.drop(connectionManager.getConnection(this, queryOptions));
    }

    // ---------- Helpers ----------

    private SqlPredicate range(A lowerBound, boolean lowerInclusive, A upperBound, boolean upperInclusive) {
        return DuckDBIndexes.rangePredicate(primaryKeyAttribute, core.valueColumn(),
                lowerBound, lowerInclusive, upperBound, upperInclusive);
    }

    private ConnectionManager connectionManager(QueryOptions queryOptions) {
        return DuckDBIndexes.connectionManager(queryOptions);
    }

    private Connection connection(QueryOptions queryOptions) {
        return connectionManager(queryOptions).getConnection(this, queryOptions);
    }

    @Override
    public String toString() {
        return "DuckDBIdentityIndex{primaryKey=" + primaryKeyAttribute.getAttributeName() + "}";
    }
}
