package io.quackjvm.agent.guard;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Stops a guarded query from outside - a Stop button, or a caller that was itself cancelled.
 *
 * <pre>
 * CancelSignal stop = new CancelSignal();
 * executor.submit(() -&gt; guard.run(sql, stop));
 * ...
 * stop.cancel();          // the run throws QueryCancelled
 * </pre>
 *
 * <p>Cancelling before the run starts stops it before anything runs; cancelling after it has
 * finished does nothing. A signal stays cancelled, so a run given one that already is throws
 * straight away. Safe to call from any thread.</p>
 */
public final class CancelSignal {

    private boolean cancelled;
    private Statement running;

    /** Stops the query this signal was given to, if it is still running. */
    public void cancel() {
        Statement statement;
        synchronized (this) {
            cancelled = true;
            statement = running;
        }
        if (statement != null) {
            try {
                // Interrupts only while that statement is running: the driver checks under the
                // connection's lock, so a query that has just finished is not touched.
                statement.cancel();
            }
            catch (SQLException | RuntimeException ignored) {
                // It finished first, or the connection went away.
            }
        }
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** The statement now running under this signal; false if the signal was cancelled already. */
    synchronized boolean attach(Statement statement) {
        if (cancelled) {
            return false;
        }
        running = statement;
        return true;
    }

    synchronized void detach() {
        running = null;
    }
}
