package io.quackjvm.cqengine.index;

import io.quackjvm.cqengine.internal.SqlPredicate;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.index.sqlite.ConnectionManager;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.QueryFactory;
import com.googlecode.cqengine.query.option.QueryOptions;

/**
 * Helpers shared by the DuckDB index implementations.
 */
final class DuckDBIndexes {

    private DuckDBIndexes() {
    }

    static ConnectionManager connectionManager(QueryOptions queryOptions) {
        ConnectionManager connectionManager = queryOptions.get(ConnectionManager.class);
        if (connectionManager == null) {
            throw new IllegalStateException("A ConnectionManager is required but was not provided in the QueryOptions. "
                    + "This usually means the index was used outside a request into an IndexedCollection "
                    + "configured with DuckDBPersistence.");
        }
        return connectionManager;
    }

    /** Translates the open/closed key range used by CQEngine's key statistics API into SQL. */
    static <O, A extends Comparable<A>> SqlPredicate rangePredicate(Attribute<O, A> attribute, String column,
                                                                    A lowerBound, boolean lowerInclusive,
                                                                    A upperBound, boolean upperInclusive) {
        Query<O> query;
        if (lowerBound != null && upperBound != null) {
            query = QueryFactory.between(attribute, lowerBound, lowerInclusive, upperBound, upperInclusive);
        }
        else if (lowerBound != null) {
            query = lowerInclusive
                    ? QueryFactory.greaterThanOrEqualTo(attribute, lowerBound)
                    : QueryFactory.greaterThan(attribute, lowerBound);
        }
        else if (upperBound != null) {
            query = upperInclusive
                    ? QueryFactory.lessThanOrEqualTo(attribute, upperBound)
                    : QueryFactory.lessThan(attribute, upperBound);
        }
        else {
            return SqlPredicate.ALWAYS_TRUE;
        }
        return SqlPredicate.render(query, column);
    }
}
