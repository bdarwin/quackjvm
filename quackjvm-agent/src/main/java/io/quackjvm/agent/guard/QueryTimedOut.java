package io.quackjvm.agent.guard;

import java.time.Duration;

/**
 * A statement ran for longer than the policy allows and was cancelled.
 *
 * <p>A {@link io.quackjvm.core.sql.QueryTimedOut}, so one catch covers a guarded query and a
 * {@code Rows} with a timeout alike.</p>
 */
public class QueryTimedOut extends io.quackjvm.core.sql.QueryTimedOut {

    private static final long serialVersionUID = 1L;

    public QueryTimedOut(Duration limit, Throwable cause) {
        super("The query was cancelled after " + limit.toMillis() + " ms. Narrow it - fewer rows, a filter,"
                + " or an aggregate instead of the rows themselves - or raise the policy's timeout.", limit, cause);
    }
}
