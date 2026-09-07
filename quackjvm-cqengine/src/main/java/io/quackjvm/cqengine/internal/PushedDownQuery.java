package io.quackjvm.cqengine.internal;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.SqlFragment;

import com.googlecode.concurrenttrees.common.LazyIterator;
import com.googlecode.cqengine.index.Index;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.index.support.CloseableRequestResources;
import com.googlecode.cqengine.index.support.CloseableRequestResources.CloseableResourceGroup;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.logical.LogicalQuery;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs a whole CQEngine query as a single SQL statement.
 *
 * <p>CQEngine evaluates {@code and(a, b)} by retrieving both sides and intersecting them in Java,
 * which means materialising every object matching the cheaper branch even though most of them will
 * be discarded. Translating the entire expression - including any {@code existsIn} joins nested
 * inside it - lets DuckDB do the intersection over two columns of keys and return only the objects
 * which actually match.</p>
 *
 * <p>Only logical queries are handled here. A simple query on one attribute is already answered by
 * a single index in one statement, so there is nothing to gain and the well-trodden path is left
 * alone.</p>
 */
public final class PushedDownQuery {

    private PushedDownQuery() {
    }

    /**
     * @return a result set answering the whole query in SQL, or null if it cannot be translated -
     * in which case the caller must let CQEngine evaluate the query as it normally would
     */
    public static <O> ResultSet<O> retrieve(ObjectTable<O, ?> objectTable, JoinTarget<O> target,
                                            ForeignQueryTranslator.JoinResolver resolver,
                                            Query<O> query, QueryOptions queryOptions,
                                            ConnectionManager connectionManager, Index<O> indexForConnections) {
        if (!(query instanceof LogicalQuery) || indexForConnections == null) {
            return null;
        }
        SqlFragment keys = ForeignQueryTranslator.keysMatching(target, query, resolver);
        if (keys == null) {
            return null;
        }
        return new SqlResultSet<>(objectTable, query, keys, queryOptions, connectionManager, indexForConnections);
    }

    /** True if the query could be answered in SQL, without running anything. */
    public static <O> boolean isTranslatable(JoinTarget<O> target,
                                             ForeignQueryTranslator.JoinResolver resolver, Query<O> query) {
        return query instanceof LogicalQuery
                && ForeignQueryTranslator.keysMatching(target, query, resolver) != null;
    }

    private static final class SqlResultSet<O> extends ResultSet<O> {

        private final ObjectTable<O, ?> objectTable;
        private final Query<O> query;
        private final SqlFragment keys;
        private final QueryOptions queryOptions;
        private final ConnectionManager connectionManager;
        private final Index<O> indexForConnections;
        private final CloseableResourceGroup resources;
        private int size = -1;

        SqlResultSet(ObjectTable<O, ?> objectTable, Query<O> query, SqlFragment keys,
                     QueryOptions queryOptions, ConnectionManager connectionManager,
                     Index<O> indexForConnections) {
            this.indexForConnections = indexForConnections;
            this.objectTable = objectTable;
            this.query = query;
            this.keys = keys;
            this.queryOptions = queryOptions;
            this.connectionManager = connectionManager;
            this.resources = CloseableRequestResources.forQueryOptions(queryOptions).addGroup();
        }

        private String selectObjects() {
            return "SELECT " + objectTable.selectList("o") + " FROM "
                    + Sql.quote(objectTable.getTableName()) + " o WHERE "
                    + ObjectTable.keyColumn("o") + " IN (" + keys.getSql() + ")";
        }

        private String countObjects() {
            return "SELECT count(*) FROM " + Sql.quote(objectTable.getTableName()) + " o WHERE "
                    + ObjectTable.keyColumn("o") + " IN (" + keys.getSql() + ")";
        }

        private Connection connection() {
            // The query belongs to no single index, so the collection's identity index stands in:
            // CQEngine's connection manager resolves the persistence from whichever index asks.
            return connectionManager.getConnection(indexForConnections, queryOptions);
        }

        @Override
        public Iterator<O> iterator() {
            java.sql.ResultSet resultSet = DuckDBIndexCore.openQuery(
                    connection(), selectObjects(), keys.getParameters(), resources);
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

        @Override
        public boolean contains(O object) {
            Object key = objectTable.getPrimaryKeyAttribute().getValue(object, queryOptions);
            List<Object> parameters = new ArrayList<>(keys.getParameters());
            parameters.add(key);
            return Sql.queryLong(connection(),
                    countObjects() + " AND " + ObjectTable.keyColumn("o") + " = ?", parameters) > 0;
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
            return DuckDBIndexCore.INDEX_RETRIEVAL_COST;
        }

        @Override
        public int getMergeCost() {
            return size();
        }

        @Override
        public int size() {
            if (size < 0) {
                size = (int) Math.min(Integer.MAX_VALUE,
                        Sql.queryLong(connection(), countObjects(), keys.getParameters()));
            }
            return size;
        }

        @Override
        public void close() {
            resources.close();
        }
    }
}
