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
    /** The SQL of a prepared statement; null for a plain one, whose SQL comes with each execute. */
    private final String sql;
    private Timer openQuery;
    private long openedAt;
    /** Set when the open query's execution is being profiled: its statement and SQL. */
    private Statement profiledStatement;
    private String profiledSql;

    public StatementMeter(QuackMetrics metrics, Timer fixedTimer, String sql) {
        this.metrics = metrics;
        this.fixedTimer = fixedTimer;
        this.sql = sql;
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
        String executed = sql;
        if (timer == null) {
            executed = args != null && args.length > 0 && args[0] instanceof String given ? given : "(batch)";
            timer = metrics.statementTimer(executed);
        }
        boolean profiled = metrics.beforeExecute(target, timer);
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
            // A query's profile is complete only once its results have been read: take it at finish.
            profiledStatement = profiled ? target : null;
            profiledSql = profiled ? executed : null;
        }
        else {
            long elapsed = System.nanoTime() - startedAt;
            timer.record(elapsed);
            if (profiled) {
                metrics.captureProfile(target, timer, executed, elapsed);
            }
        }
        return result;
    }

    /** Ends the timing of a query whose results were open. Harmless if none was. */
    public void finish() {
        if (openQuery != null) {
            long elapsed = System.nanoTime() - openedAt;
            openQuery.record(elapsed);
            if (profiledStatement != null) {
                metrics.captureProfile(profiledStatement, openQuery, profiledSql, elapsed);
                profiledStatement = null;
                profiledSql = null;
            }
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
