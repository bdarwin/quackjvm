package io.quackjvm.agent.guard;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Stops a guarded query from outside - a Stop button, or a caller that was itself cancelled.
 *
 * <pre>
 * CancelSignal stop = new CancelSignal();
 * executor.submit(() -&gt; guard.run(sql, stop));
 * ...
 * stop.cancel();          // the run throws QueryCancelled
 *
 * // or from a flag the application already has: polled while the query runs
 * guard.run(sql, CancelSignal.from(() -&gt; stopButton.isPressed()));
 * </pre>
 *
 * <p>Cancelling before the run starts stops it before anything runs; cancelling after it has
 * finished does nothing. A signal stays cancelled, so a run given one that already is throws
 * straight away. Safe to call from any thread.</p>
 */
public final class CancelSignal {

    /** How often {@link #from} asks its supplier. DuckDB stops within a millisecond of being asked, measured. */
    public static final Duration DEFAULT_POLL = Duration.ofMillis(50);

    /** One daemon thread polls every signal made from a supplier, and only while it has a query running. */
    private static final ScheduledExecutorService POLLER = poller();

    private final BooleanSupplier stop;
    private final long pollNanos;
    private boolean cancelled;
    private Statement running;
    private ScheduledFuture<?> watch;

    public CancelSignal() {
        this(null, 0);
    }

    private CancelSignal(BooleanSupplier stop, long pollNanos) {
        this.stop = stop;
        this.pollNanos = pollNanos;
    }

    /**
     * A signal that is cancelled when the supplier says so: asked when a run starts, so one that is
     * already true stops it before anything runs, and then every {@link #DEFAULT_POLL} while it runs.
     */
    public static CancelSignal from(BooleanSupplier stop) {
        return polling(stop, DEFAULT_POLL);
    }

    /** As {@link #from}, asking the supplier this often. A supplier that throws counts as not stopping. */
    public static CancelSignal polling(BooleanSupplier stop, Duration every) {
        if (stop == null) {
            throw new IllegalArgumentException("No supplier to poll");
        }
        if (every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("The poll interval must be positive: " + every);
        }
        return new CancelSignal(stop, every.toNanos());
    }

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

    /** Whether it has been cancelled - by {@link #cancel()}, or by its supplier when last asked. */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Asks the supplier now, if there is one; true if the signal is, or has just become, cancelled. */
    boolean cancelledNow() {
        if (stop != null && !isCancelled() && asks()) {
            synchronized (this) {
                cancelled = true;
            }
        }
        return isCancelled();
    }

    /** The statement now running under this signal; false if the signal was cancelled already. */
    boolean attach(Statement statement) {
        if (cancelledNow()) {
            return false;
        }
        synchronized (this) {
            if (cancelled) {
                return false;
            }
            running = statement;
            if (stop != null && watch == null) {
                watch = POLLER.scheduleWithFixedDelay(this::poll, pollNanos, pollNanos, TimeUnit.NANOSECONDS);
            }
            return true;
        }
    }

    void detach() {
        ScheduledFuture<?> stopWatching;
        synchronized (this) {
            running = null;
            stopWatching = watch;
            watch = null;
        }
        if (stopWatching != null) {
            stopWatching.cancel(false);
        }
    }

    private void poll() {
        if (asks()) {
            cancel();
        }
    }

    private boolean asks() {
        try {
            return stop.getAsBoolean();
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    private static ScheduledExecutorService poller() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Executors.defaultThreadFactory().newThread(task);
            thread.setName("quackjvm-cancel-poll");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
