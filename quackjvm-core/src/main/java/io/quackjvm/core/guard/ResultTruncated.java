package io.quackjvm.core.guard;

/**
 * The query would have returned more than the policy allows. Nothing partial is handed back: a
 * truncated answer read as a whole one is worse than no answer.
 */
public class ResultTruncated extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long limit;
    private final boolean rows;

    private ResultTruncated(String message, long limit, boolean rows) {
        super(message);
        this.limit = limit;
        this.rows = rows;
    }

    static ResultTruncated rows(long limit) {
        return new ResultTruncated("The query returns more than " + limit + " rows, which is the policy's limit."
                + " Add a LIMIT, a filter, or a GROUP BY that summarises instead of listing.", limit, true);
    }

    static ResultTruncated bytes(long limit, long seen) {
        return new ResultTruncated("The rows returned add up to more than " + limit + " bytes (" + seen
                + " so far), which is the policy's limit. Select fewer columns, or summarise them.", limit, false);
    }

    /** The limit that was passed: rows or bytes, according to {@link #isRowLimit()}. */
    public long getLimit() {
        return limit;
    }

    public boolean isRowLimit() {
        return rows;
    }
}
