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
    /**
     * Set when the object store has just written the objects of this request, and cleared when the
     * identity index sees it. See {@link #markObjectTableWritten}.
     */
    private Boolean objectTableAlreadyWritten;
    private Boolean objectTableAlreadyDeleted;

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

    /**
     * Records that the object store has already written this request's objects to the object table.
     *
     * <p>CQEngine writes every object twice. {@code ConcurrentIndexedCollection.doAddAll} calls
     * {@code objectStore.addAll(...)}, which is backed by the identity index, and then
     * {@code indexEngine.addAll(...)}, which fans the same objects out over every index - the
     * identity index included. SQLite gets away with that because its second write is a few
     * microseconds. DuckDB does not: the second write is a whole statement, and because the keys
     * now exist its {@code INSERT OR IGNORE} reports nothing inserted and falls through to an
     * {@code INSERT OR REPLACE}, so a one-object add costs three statements rather than one.</p>
     *
     * <p>The store marks the write here, and the index engine's redundant pass over the identity
     * index consumes the mark instead of repeating it. The mark is consumed exactly once, so any
     * later write in the same request - a rebuild, a {@code retainAll} - is unaffected.</p>
     */
    public static void markObjectTableWritten(QueryOptions queryOptions, boolean result) {
        hints(queryOptions).objectTableAlreadyWritten = result;
    }

    /** @return the remembered result if this request's write has already happened, else null. */
    public static Boolean consumeObjectTableWritten(QueryOptions queryOptions) {
        WriteHints hints = queryOptions.get(WriteHints.class);
        if (hints == null) {
            return null;
        }
        Boolean result = hints.objectTableAlreadyWritten;
        hints.objectTableAlreadyWritten = null;
        return result;
    }

    /** The delete-side counterpart of {@link #markObjectTableWritten}. */
    public static void markObjectTableDeleted(QueryOptions queryOptions, boolean result) {
        hints(queryOptions).objectTableAlreadyDeleted = result;
    }

    /** @return the remembered result if this request's delete has already happened, else null. */
    public static Boolean consumeObjectTableDeleted(QueryOptions queryOptions) {
        WriteHints hints = queryOptions.get(WriteHints.class);
        if (hints == null) {
            return null;
        }
        Boolean result = hints.objectTableAlreadyDeleted;
        hints.objectTableAlreadyDeleted = null;
        return result;
    }

    private static WriteHints hints(QueryOptions queryOptions) {
        WriteHints hints = queryOptions.get(WriteHints.class);
        if (hints == null) {
            hints = new WriteHints();
            queryOptions.put(WriteHints.class, hints);
        }
        return hints;
    }
}
