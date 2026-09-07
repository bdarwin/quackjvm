package io.quackjvm.cqengine.persistence;

import io.quackjvm.cqengine.internal.ForeignQueryTranslator;
import io.quackjvm.cqengine.internal.PushedDownQuery;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.OrderByOption;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import com.googlecode.cqengine.resultset.closeable.CloseableResultSet;

/**
 * An {@code IndexedCollection} which sends whole queries to DuckDB.
 *
 * <p>CQEngine evaluates {@code and(a, b)} by retrieving each side and intersecting them in Java.
 * With the data in a database that means materialising every object matching the cheaper branch
 * only to discard most of them: a two-attribute query returning 4,000 objects was costing 51 ms
 * because 20,000 objects were being rebuilt from columns first. This collection translates the
 * whole expression - including {@code existsIn} joins nested inside it - into one statement, so
 * DuckDB intersects key columns and returns only the objects which match.</p>
 *
 * <p>It is otherwise an ordinary {@link ConcurrentIndexedCollection}. Any query which cannot be
 * translated is handed straight back to CQEngine, so behaviour never depends on the push-down.</p>
 */
public class DuckDBIndexedCollection<O, A extends Comparable<A>> extends ConcurrentIndexedCollection<O> {

    private final DuckDBPersistence<O, A> duckDBPersistence;

    public DuckDBIndexedCollection(DuckDBPersistence<O, A> persistence) {
        super(persistence);
        this.duckDBPersistence = persistence;
    }

    @Override
    public ResultSet<O> retrieve(Query<O> query) {
        return retrieve(query, null);
    }

    @Override
    public ResultSet<O> retrieve(Query<O> query, QueryOptions queryOptions) {
        if (!canPushDown(query, queryOptions)) {
            return super.retrieve(query, queryOptions);
        }
        final QueryOptions finalQueryOptions = openRequestScopeResourcesIfNecessary(queryOptions);
        flagAsReadRequest(finalQueryOptions);
        ConnectionManager connectionManager = finalQueryOptions.get(ConnectionManager.class);
        ResultSet<O> results = connectionManager == null ? null : PushedDownQuery.retrieve(
                duckDBPersistence.getObjectTable(), duckDBPersistence, joinResolver(),
                query, finalQueryOptions, connectionManager, duckDBPersistence.getIdentityIndex());
        if (results == null) {
            // Decided against at the last moment; fall back without leaking the request scope.
            closeRequestScopeResourcesIfNecessary(finalQueryOptions);
            return super.retrieve(query, queryOptions);
        }
        return new CloseableResultSet<>(results, query, finalQueryOptions) {
            @Override
            public void close() {
                super.close();
                closeRequestScopeResourcesIfNecessary(finalQueryOptions);
            }
        };
    }

    /**
     * Whether the whole query should go to DuckDB.
     *
     * <p>Simple queries are left to their index, which already answers them in one statement.
     * Ordering is left to CQEngine, which applies it on the returned objects.</p>
     */
    private boolean canPushDown(Query<O> query, QueryOptions queryOptions) {
        if (queryOptions != null && queryOptions.get(OrderByOption.class) != null) {
            return false;
        }
        return PushedDownQuery.isTranslatable(duckDBPersistence, joinResolver(), query);
    }

    private ForeignQueryTranslator.JoinResolver joinResolver() {
        return duckDBPersistence.getDatabase()::joinTargetFor;
    }
}
