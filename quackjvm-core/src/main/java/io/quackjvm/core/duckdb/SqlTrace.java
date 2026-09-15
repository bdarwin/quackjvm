package io.quackjvm.core.duckdb;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traces every statement a request runs, with the time each took, to show where a request spends
 * its latency. Off unless {@code -Dquackjvm.trace=true} is set, and free when off.
 *
 * <p>DuckDB does its work in native code, so a JVM profiler attributes almost all of a write to
 * one JNI frame and tells you nothing about which statements ran. Counting and timing them at the
 * JDBC boundary is what showed that a one-object {@code add} was running three statements against
 * the object table rather than one.</p>
 *
 * <pre>
 * SqlTrace.reset();
 * for (int i = 0; i &lt; n; i++) collection.add(object(i));
 * SqlTrace.dump("add", n);
 * </pre>
 *
 * <p>Statement <em>counts</em> are exact and do not depend on how fast the machine is, which makes
 * them the one part of performance that can be asserted in a test. {@link #countsByStatement()}
 * exists for that: a budget of "one add issues two statements against the object table" catches a
 * regression that a timing threshold would miss on a fast machine and report falsely on a slow
 * one.</p>
 *
 * <p>Timings are accumulated under a lock, so tracing serialises the work it measures. Leave it off
 * when measuring concurrency; it answers "where does one request spend its time", not "why do N
 * requests not scale".</p>
 */
public final class SqlTrace {

    private static volatile boolean enabled = Boolean.getBoolean("quackjvm.trace");
    public static final Map<String, long[]> STATS = new LinkedHashMap<>();

    private SqlTrace() {
    }

    /** Whether tracing is on. Set by {@code -Dquackjvm.trace=true} or {@link #setEnabled}. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns tracing on or off at runtime, so a test can measure one block without a system
     * property. Only connections borrowed after this call are traced.
     */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** How many times each statement ran since the last {@link #reset}, keyed by its SQL. */
    public static synchronized Map<String, Long> countsByStatement() {
        Map<String, Long> counts = new LinkedHashMap<>();
        STATS.forEach((what, stat) -> counts.put(what, stat[0]));
        return counts;
    }

    /**
     * How many statements were executed since the last {@link #reset}, ignoring the cost of
     * preparing them and of opening and closing connections.
     *
     * @param sqlFragment counts only statements whose SQL contains this, or all of them if null
     */
    public static synchronized long executionCount(String sqlFragment) {
        long total = 0;
        for (Map.Entry<String, long[]> entry : STATS.entrySet()) {
            String what = entry.getKey();
            boolean isExecution = what.startsWith("execute");
            if (isExecution && (sqlFragment == null || what.contains(sqlFragment))) {
                total += entry.getValue()[0];
            }
        }
        return total;
    }

    public static synchronized void record(String what, long nanos) {
        STATS.computeIfAbsent(what, k -> new long[2]);
        long[] s = STATS.get(what);
        s[0]++;
        s[1] += nanos;
    }

    public static synchronized void reset() {
        STATS.clear();
    }

    public static synchronized void dump(String title, int iterations) {
        System.out.println("== " + title);
        long total = 0;
        for (Map.Entry<String, long[]> e : STATS.entrySet()) {
            total += e.getValue()[1];
        }
        STATS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> System.out.printf("  %8.1f us/add  %6.2f calls/add  %s%n",
                        e.getValue()[1] / 1000.0 / iterations,
                        e.getValue()[0] / (double) iterations,
                        e.getKey().length() > 90 ? e.getKey().substring(0, 90) + "..." : e.getKey()));
        System.out.printf("  %8.1f us/add  TOTAL traced%n", total / 1000.0 / iterations);
    }

    private static String label(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > 70 ? s.substring(0, 70) : s;
    }

    public static Connection wrap(Connection connection) {
        if (!enabled) {
            return connection;
        }
        return (Connection) Proxy.newProxyInstance(SqlTrace.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ConnectionHandler(connection));
    }

    private record ConnectionHandler(Connection delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            long start = System.nanoTime();
            Object result;
            try {
                result = method.invoke(delegate, args);
            }
            catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
            long elapsed = System.nanoTime() - start;
            if (name.equals("prepareStatement")) {
                record("prepareStatement: " + label((String) args[0]), elapsed);
                return wrapStatement((PreparedStatement) result, label((String) args[0]));
            }
            if (name.equals("createStatement")) {
                record("createStatement()", elapsed);
                return wrapStatement((Statement) result, null);
            }
            record("Connection." + name + "()", elapsed);
            return result;
        }
    }

    private static Object wrapStatement(Statement statement, String preparedSql) {
        Class<?>[] interfaces = preparedSql == null
                ? new Class<?>[]{Statement.class}
                : new Class<?>[]{PreparedStatement.class};
        return Proxy.newProxyInstance(SqlTrace.class.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    long start = System.nanoTime();
                    Object result;
                    try {
                        result = method.invoke(statement, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                    long elapsed = System.nanoTime() - start;
                    String name = method.getName();
                    if (name.startsWith("execute")) {
                        String sql = preparedSql != null ? preparedSql
                                : (args != null && args.length > 0 && args[0] instanceof String s ? label(s) : "?");
                        record(name + ": " + sql, elapsed);
                    }
                    else if (name.equals("close")) {
                        record("Statement.close()", elapsed);
                    }
                    return result;
                });
    }
}
