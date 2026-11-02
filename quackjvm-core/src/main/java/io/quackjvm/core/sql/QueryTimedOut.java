package io.quackjvm.core.sql;

import java.time.Duration;

/**
 * A query ran for longer than its timeout and was cancelled.
 *
 * <p>The connection it ran on is usable afterwards: DuckDB interrupts the query, not the
 * connection. See {@link Rows#timeout(Duration)}.</p>
 */
public class QueryTimedOut extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration limit;

    public QueryTimedOut(Duration limit, Throwable cause) {
        this("The query was cancelled after " + limit.toMillis() + " ms, its timeout.", limit, cause);
    }

    protected QueryTimedOut(String message, Duration limit, Throwable cause) {
        super(message, cause);
        this.limit = limit;
    }

    /** The timeout the query ran into. */
    public Duration getLimit() {
        return limit;
    }
}
