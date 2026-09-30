package io.quackjvm.core.guard;

import java.time.Duration;

/** A statement ran for longer than the policy allows and was cancelled. */
public class QueryTimedOut extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration limit;

    public QueryTimedOut(Duration limit, Throwable cause) {
        super("The query was cancelled after " + limit.toMillis() + " ms. Narrow it - fewer rows, a filter,"
                + " or an aggregate instead of the rows themselves - or raise the policy's timeout.", cause);
        this.limit = limit;
    }

    public Duration getLimit() {
        return limit;
    }
}
