package io.quackjvm.core.internal;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancels a statement that is still running when its time is up.
 *
 * <pre>
 * try (Deadline deadline = Deadline.start(statement, timeout)) {
 *     ... run and read ...
 * }
 * catch (SQLException e) {
 *     if (deadline.hasExpired()) throw new QueryTimedOut(timeout, e);
 * }
 * </pre>
 *
 * <p>The driver's {@code cancel()} interrupts only while that statement is running, and checks
 * under the connection's lock, so a cancel that fires as the statement finishes does nothing.
 * Closing still waits for a cancel already under way, so that no interrupt is in flight once the
 * connection is handed to someone else.</p>
 */
public final class Deadline implements AutoCloseable {

    private static final ScheduledExecutorService CANCELLERS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "quackjvm-query-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private static final Deadline NONE = new Deadline(null, null);

    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> canceller;

    private Deadline(Statement statement, Duration timeout) {
        this.canceller = statement == null ? null : CANCELLERS.schedule(() -> {
            expired.set(true);
            try {
                statement.cancel();
            }
            catch (SQLException | RuntimeException ignored) {
                // The statement finished first, or the connection went away.
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Starts the clock on a statement; with no timeout, a deadline that never expires. */
    public static Deadline start(Statement statement, Duration timeout) {
        return timeout == null ? NONE : new Deadline(statement, timeout);
    }

    /** Whether the time ran out, so that a failure is the cancel rather than anything else. */
    public boolean hasExpired() {
        return expired.get();
    }

    @Override
    public void close() {
        if (canceller == null || canceller.cancel(false)) {
            return;
        }
        // Already running, or done: wait for it, so that no cancel is still on its way.
        try {
            canceller.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (ExecutionException | CancellationException ignored) {
            // Nothing to wait for.
        }
    }

    /** A timeout must be positive; null means none. */
    public static Duration check(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("A timeout must be positive: " + timeout);
        }
        return timeout;
    }
}
