package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.udf.Udfs;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The Java functions a {@link FileTable}'s queries can call, and stopping them when a query is
 * cancelled.
 *
 * <p>DuckDB checks for a cancel between the chunks of 2,048 rows it hands a function, so a function
 * taking 1 ms a row went on for 2.6 s after its query was cancelled - measured. So each function is
 * wrapped: before each call it reads one flag, set within a millisecond of the signal of a query
 * running on this table being cancelled, and if it is set the call throws, which stops the query.
 * DuckDB cannot say which query a call belongs to, so another query running then stops too; {@link
 * FileTable#query} runs that one again once the cancelled one has gone.</p>
 */
final class TableFunctions {

    /** What a function stopped this way says, so that the query it stopped can tell. */
    static final String STOPPED = "quackjvm: stopped for a cancelled query on the same table";

    /** How often the signals of the running queries are looked at. */
    static final long WATCH_MICROS = 1_000;

    private static final ScheduledExecutorService WATCHER = watcher();

    /** A function's registration, by name: what is done to a connection to register it. */
    private final Map<String, Registrar> functions = new LinkedHashMap<>();

    /** The signals of the queries running now, each with how many runs use it. */
    private final Map<CancelSignal, Integer> running = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private ScheduledFuture<?> watch;

    interface Registrar {
        void register(Connection connection);
    }

    static final class Stopped extends RuntimeException {
        Stopped() {
            super(STOPPED, null, false, false);
        }
    }

    // ---------- registering ----------

    <A, R> Registrar add(String name, Class<R> returns, Class<A> a, Function<A, R> function) {
        return put(name, connection -> Udfs.register(connection, name, returns, a, x -> {
            check();
            return function.apply(x);
        }));
    }

    <A, B, R> Registrar add(String name, Class<R> returns, Class<A> a, Class<B> b, BiFunction<A, B, R> function) {
        return put(name, connection -> Udfs.register(connection, name, returns, a, b, (x, y) -> {
            check();
            return function.apply(x, y);
        }));
    }

    <A, B, C, R> Registrar add(String name, Class<R> returns, Class<A> a, Class<B> b, Class<C> c,
                               Udfs.Fn3<A, B, C, R> function) {
        return put(name, connection -> Udfs.register(connection, name, returns, a, b, c, (x, y, z) -> {
            check();
            return function.apply(x, y, z);
        }));
    }

    private synchronized Registrar put(String name, Registrar registrar) {
        functions.put(name.toLowerCase(Locale.ROOT), registrar);
        return registrar;
    }

    synchronized List<Registrar> all() {
        return List.copyOf(functions.values());
    }

    boolean isEmpty() {
        synchronized (this) {
            return functions.isEmpty();
        }
    }

    // ---------- stopping ----------

    private void check() {
        if (stopping) {
            throw new Stopped();
        }
    }

    /** A query starts that may call the functions. */
    void enter(CancelSignal signal) {
        if (signal == null) {
            return;
        }
        running.merge(signal, 1, Integer::sum);
        synchronized (this) {
            if (watch == null) {
                watch = WATCHER.scheduleWithFixedDelay(this::look, WATCH_MICROS, WATCH_MICROS, TimeUnit.MICROSECONDS);
            }
        }
    }

    /** That query is over. */
    void leave(CancelSignal signal) {
        if (signal == null) {
            return;
        }
        running.computeIfPresent(signal, (s, n) -> n == 1 ? null : n - 1);
        look();
        synchronized (this) {
            if (running.isEmpty() && watch != null) {
                watch.cancel(false);
                watch = null;
                stopping = false;
            }
        }
    }

    /** Whether a failure is a function stopped for another query's cancel. */
    static boolean stoppedHere(Throwable failure) {
        for (Throwable e = failure; e != null; e = e.getCause()) {
            if (String.valueOf(e.getMessage()).contains(STOPPED)) {
                return true;
            }
        }
        return false;
    }

    /** Waits until no cancelled query is running, so a query stopped for one can run again. */
    void awaitClear(CancelSignal own) {
        while (stopping) {
            if (own != null && own.isCancelled()) {
                throw new QueryCancelled(null);
            }
            try {
                Thread.sleep(1);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new QueryCancelled(e);
            }
            look();
        }
    }

    private void look() {
        boolean any = false;
        Set<CancelSignal> signals = running.keySet();
        for (CancelSignal signal : signals) {
            if (signal.isCancelled()) {
                any = true;
                break;
            }
        }
        stopping = any;
    }

    private static ScheduledExecutorService watcher() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Executors.defaultThreadFactory().newThread(task);
            thread.setName("quackjvm-function-cancel");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
