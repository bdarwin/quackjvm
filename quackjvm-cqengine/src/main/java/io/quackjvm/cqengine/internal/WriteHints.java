package io.quackjvm.cqengine.internal;

import com.googlecode.cqengine.query.option.QueryOptions;

/**
 * A hint passed between the identity index and the attribute indexes within one request.
 *
 * <p>CQEngine always writes to the object store before it writes to the indexes, so by the time
 * the attribute indexes are updated the identity index already knows whether the objects were
 * new. When they were, the attribute indexes can skip the "delete the old rows for these keys"
 * step which otherwise makes re-adding an object idempotent - saving one statement per index
 * on the common path of adding objects which are not in the collection yet.</p>
 */
public final class WriteHints {

    private boolean allObjectsWereNew;

    public static void setAllObjectsWereNew(QueryOptions queryOptions, boolean allObjectsWereNew) {
        WriteHints hints = queryOptions.get(WriteHints.class);
        if (hints == null) {
            hints = new WriteHints();
            queryOptions.put(WriteHints.class, hints);
        }
        hints.allObjectsWereNew = allObjectsWereNew;
    }

    /** True only if the identity index has just confirmed that every object written was new. */
    public static boolean allObjectsWereNew(QueryOptions queryOptions) {
        WriteHints hints = queryOptions.get(WriteHints.class);
        return hints != null && hints.allObjectsWereNew;
    }

    public static void clear(QueryOptions queryOptions) {
        setAllObjectsWereNew(queryOptions, false);
    }
}
