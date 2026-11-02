package io.quackjvm.agent.guard;

/**
 * A guarded query was stopped through its {@link CancelSignal}. Not a {@link QueryTimedOut}: no
 * limit was reached, someone asked for it to stop. The connection is usable afterwards.
 */
public class QueryCancelled extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueryCancelled(Throwable cause) {
        super("The query was cancelled.", cause);
    }
}
