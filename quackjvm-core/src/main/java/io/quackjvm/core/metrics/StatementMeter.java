package io.quackjvm.core.metrics;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Statement;

/**
 * Times the executions of one JDBC statement. Not thread-safe - neither is the statement.
 *
 * <p>An update is timed around its execute call. A query is timed from its execute call until the
 * statement is closed or executed again: with results streamed, DuckDB does much of a query's work
 * while its rows are being read, so the execute call alone would miss most of it. That also counts
 * the time the caller spends between rows - which is honest, since the query holds its snapshot
 * and its threads for all of it.</p>
 */
public final class StatementMeter {

    private final QuackMetrics metrics;
    /** The timer for a prepared statement; null for a plain one, which is timed per SQL. */
    private final Timer fixedTimer;
    private Timer openQuery;
    private long openedAt;

    public StatementMeter(QuackMetrics metrics, Timer fixedTimer) {
        this.metrics = metrics;
        this.fixedTimer = fixedTimer;
    }

    /** A proxy handler that meters every call to the given statement. */
    InvocationHandler handler(Statement target) {
        return (proxy, method, args) -> {
            if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                finish();
            }
            return invoke(target, method, args);
        };
    }

    /**
     * Calls the method on the statement, timing it if it executes something. Closing is not
     * handled here - call {@link #finish} when the statement is closed or handed back.
     */
    public Object invoke(Statement target, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (!name.startsWith("execute")) {
            return call(target, method, args);
        }
        // Executing again closes the previous result set, which ends that query.
        finish();
        Timer timer = fixedTimer;
        if (timer == null) {
            timer = args != null && args.length > 0 && args[0] instanceof String sql
                    ? metrics.statementTimer(sql)
                    : metrics.statementTimer("(batch)");
        }
        long startedAt = System.nanoTime();
        Object result;
        try {
            result = call(target, method, args);
        }
        catch (Throwable e) {
            timer.record(System.nanoTime() - startedAt);
            metrics.recordError(e);
            throw e;
        }
        if ("executeQuery".equals(name) || ("execute".equals(name) && Boolean.TRUE.equals(result))) {
            openQuery = timer;
            openedAt = startedAt;
        }
        else {
            timer.record(System.nanoTime() - startedAt);
        }
        return result;
    }

    /** Ends the timing of a query whose results were open. Harmless if none was. */
    public void finish() {
        if (openQuery != null) {
            openQuery.record(System.nanoTime() - openedAt);
            openQuery = null;
        }
    }

    private static Object call(Statement target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
