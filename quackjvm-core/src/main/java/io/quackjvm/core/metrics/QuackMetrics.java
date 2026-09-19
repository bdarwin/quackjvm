package io.quackjvm.core.metrics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Where time goes, and where it waits: timers, counters and gauges for one database.
 *
 * <p>Built to answer one question - when things are slow, is quackjvm <em>waiting</em> for
 * something or <em>working</em>? - so every operation that can queue records its wait apart from
 * its work. A write that is slow because it queued for its collection's write lock and a write that
 * is slow because DuckDB is short of CPU look identical from outside and have opposite fixes.
 * {@link Diagnosis} reads a snapshot and names which it is.</p>
 *
 * <pre>
 * MetricsSnapshot before = metrics.snapshot();
 * // ... load ...
 * MetricsSnapshot interval = metrics.snapshot().minus(before);
 * Diagnosis.of(interval).forEach(System.out::println);
 * </pre>
 *
 * <p>Plain connections - one per thread, from {@code DuckDBConnection.duplicate()} - are measured by
 * passing them through {@link #meter(Connection)}. A {@code DuckDBDatabase} measures its own.</p>
 *
 * <p>Names are dotted, with an optional scope in brackets: {@code write_lock.wait[orders]}. The
 * constants below are the ones quackjvm records.</p>
 */
public final class QuackMetrics {

    /** Borrow-to-close of a read request's connection. Scope: the collection, or {@code sql}. */
    public static final String REQUEST_READ = "request.read";
    /** Borrow-to-close of a write request's connection, lock already held. Scope: the collection. */
    public static final String REQUEST_WRITE = "request.write";
    /** Time spent waiting for a collection's write lock. Scope: the collection. */
    public static final String WRITE_LOCK_WAIT = "write_lock.wait";
    /**
     * Execution of one statement. For a query, from execute until the statement is closed - with
     * results streamed, DuckDB does much of its work as rows are read. Scope: the statement's shape.
     */
    public static final String STATEMENT = "statement";
    /** Failed statements. Scope: {@code conflict}, {@code constraint}, {@code memory} or {@code other}. */
    public static final String STATEMENT_ERRORS = "statement.errors";
    public static final String CONNECTIONS_OPENED = "connections.opened";
    public static final String CONNECTIONS_DISCARDED = "connections.discarded";
    public static final String CONNECTIONS_IN_USE = "connections.in_use";
    public static final String PREPARE_HITS = "prepare.cache_hits";
    public static final String PREPARE_MISSES = "prepare.cache_misses";
    /** Transactions rolled back after a conflict and tried again. Scope: what was retried. */
    public static final String RETRIES = "retries";

    /** CPU time this process has used, DuckDB's native threads included; see {@link MetricsSnapshot#cpuUtilisation()}. */
    public static final String CPU_TIME = "cpu.process_time_ns";
    /**
     * How busy the whole machine's cores were recently, 0 to 1, whoever was using them. Another
     * process taking the cores starves DuckDB as surely as its own queries do.
     */
    public static final String CPU_MACHINE = "cpu.machine";
    public static final String CORES = "cpu.cores";
    public static final String HEAP_USED = "jvm.heap_used_bytes";
    public static final String DUCKDB_THREADS = "duckdb.threads";
    public static final String DUCKDB_MEMORY = "duckdb.memory_bytes";
    public static final String DUCKDB_MEMORY_LIMIT = "duckdb.memory_limit_bytes";
    public static final String DUCKDB_TEMP = "duckdb.temp_file_bytes";

    /** Distinct statement shapes kept apart; beyond this they share one timer. */
    private static final int MAX_STATEMENT_SHAPES = 256;
    private static final String OTHER_STATEMENTS = "(other statements)";
    /**
     * Long enough to judge a statement by - its joins, its subqueries, its aggregates - and short
     * enough that a few hundred of them stay small. Was 160, which cut real queries off before
     * their FROM.
     */
    static final int MAX_SHAPE_LENGTH = 4000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PLACEHOLDER_LIST = Pattern.compile("\\?(\\s*,\\s*\\?)+");
    private static final Pattern VALUES_LIST = Pattern.compile("(\\(\\?[^()]*\\))(\\s*,\\s*\\(\\?[^()]*\\))+");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_0-9\"])-?\\d+(\\.\\d+)?(?![A-Za-z_0-9\"])");
    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> cumulatives = new ConcurrentHashMap<>();
    /** Raw SQL to its timer, so that a statement seen before costs one map lookup. */
    private final Map<String, Timer> statementTimers = new ConcurrentHashMap<>();

    public QuackMetrics() {
        gauge(CORES, () -> Runtime.getRuntime().availableProcessors());
        gauge(HEAP_USED, () -> {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        });
        cumulative(CPU_TIME, QuackMetrics::processCpuTime);
        gauge(CPU_MACHINE, QuackMetrics::machineCpuLoad);
    }

    public static String scoped(String name, String scope) {
        return scope == null ? name : name + "[" + scope + "]";
    }

    public Timer timer(String name) {
        return timers.computeIfAbsent(name, Timer::new);
    }

    public Timer timer(String name, String scope) {
        return timer(scoped(name, scope));
    }

    public LongAdder counter(String name) {
        return counters.computeIfAbsent(name, k -> new LongAdder());
    }

    public LongAdder counter(String name, String scope) {
        return counter(scoped(name, scope));
    }

    /**
     * Registers a value read when a snapshot is taken. Return {@code Double.NaN} when it cannot be
     * read; a gauge that throws is reported as NaN.
     */
    public void gauge(String name, DoubleSupplier value) {
        gauges.put(name, value);
    }

    /**
     * Registers a running total kept elsewhere - read at each snapshot and, like a counter,
     * subtracted when two snapshots are.
     */
    public void cumulative(String name, LongSupplier total) {
        cumulatives.put(name, total);
    }

    /** The timer for a statement, by its shape: literals and placeholder lists collapsed. */
    public Timer statementTimer(String sql) {
        Timer timer = statementTimers.get(sql);
        if (timer != null) {
            return timer;
        }
        String name = scoped(STATEMENT, shapeOf(sql));
        if (!timers.containsKey(name) && countStatementShapes() >= MAX_STATEMENT_SHAPES) {
            name = scoped(STATEMENT, OTHER_STATEMENTS);
        }
        timer = timer(name);
        if (statementTimers.size() < MAX_STATEMENT_SHAPES * 16) {
            statementTimers.put(sql, timer);
        }
        return timer;
    }

    private int countStatementShapes() {
        int n = 0;
        for (String name : timers.keySet()) {
            if (name.startsWith(STATEMENT + "[")) {
                n++;
            }
        }
        return n;
    }

    /**
     * What a statement is, rather than what it says: {@code SELECT * FROM t WHERE id IN (?, ?, ?)}
     * and the same with forty placeholders are one statement to a person reading a dashboard.
     */
    public static String shapeOf(String sql) {
        String shape = WHITESPACE.matcher(sql).replaceAll(" ").trim();
        shape = STRING.matcher(shape).replaceAll("?");
        shape = NUMBER.matcher(shape).replaceAll("?");
        shape = VALUES_LIST.matcher(shape).replaceAll("$1, ...");
        shape = PLACEHOLDER_LIST.matcher(shape).replaceAll("?, ...");
        return shape.length() > MAX_SHAPE_LENGTH ? shape.substring(0, MAX_SHAPE_LENGTH - 3) + "..." : shape;
    }

    /** Counts a failed statement under the kind of failure it was. */
    public void recordError(Throwable error) {
        counter(STATEMENT_ERRORS, classify(error)).increment();
    }

    static String classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("conflict")) {
                return "conflict";
            }
            if (message.contains("out of memory")) {
                return "memory";
            }
            if (message.contains("constraint") || message.contains("duplicate key")) {
                return "constraint";
            }
        }
        return "other";
    }

    public MetricsSnapshot snapshot() {
        Map<String, TimerSnapshot> timerSnapshots = new TreeMap<>();
        timers.forEach((name, timer) -> timerSnapshots.put(name, timer.snapshot()));
        Map<String, Long> counterValues = new TreeMap<>();
        counters.forEach((name, counter) -> counterValues.put(name, counter.sum()));
        cumulatives.forEach((name, total) -> {
            try {
                counterValues.put(name, total.getAsLong());
            }
            catch (RuntimeException ignored) {
                // Left out rather than reported as zero, which would read as "nothing happened".
            }
        });
        Map<String, Double> gaugeValues = new TreeMap<>();
        gauges.forEach((name, gauge) -> {
            double value;
            try {
                value = gauge.getAsDouble();
            }
            catch (RuntimeException e) {
                value = Double.NaN;
            }
            gaugeValues.put(name, value);
        });
        return new MetricsSnapshot(System.nanoTime(), Instant.now(), 0, timerSnapshots, counterValues, gaugeValues);
    }

    // ---------- Measuring plain connections ----------

    /**
     * Wraps a connection so that every statement made from it is timed by shape and its failures
     * counted. The wrapper adds one reflective call per JDBC method on a statement - not per row.
     */
    public Connection meter(Connection connection) {
        return (Connection) Proxy.newProxyInstance(QuackMetrics.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(connection, args);
                    }
                    catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    String name = method.getName();
                    if (result instanceof CallableStatement) {
                        return result;
                    }
                    if ("prepareStatement".equals(name) && result instanceof PreparedStatement statement) {
                        return meter(statement, (String) args[0]);
                    }
                    if ("createStatement".equals(name) && result instanceof Statement statement) {
                        return meter(statement);
                    }
                    return result;
                });
    }

    /** Times a prepared statement's executions under the shape of its SQL. */
    public PreparedStatement meter(PreparedStatement statement, String sql) {
        StatementMeter meter = new StatementMeter(this, statementTimer(sql));
        return (PreparedStatement) Proxy.newProxyInstance(QuackMetrics.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, meter.handler(statement));
    }

    /** Times a plain statement's executions, each under the shape of the SQL it was given. */
    public Statement meter(Statement statement) {
        StatementMeter meter = new StatementMeter(this, null);
        return (Statement) Proxy.newProxyInstance(QuackMetrics.class.getClassLoader(),
                new Class<?>[]{Statement.class}, meter.handler(statement));
    }

    // ---------- DuckDB gauges ----------

    /**
     * Reports DuckDB's own state - its thread setting, memory in use against its limit, and bytes
     * spilled to temporary files - read through a connection duplicated from this one at each
     * snapshot. Call once per database.
     */
    public QuackMetrics watch(org.duckdb.DuckDBConnection database) {
        DuckDBState state = new DuckDBState(database);
        gauge(DUCKDB_THREADS, () -> state.read()[0]);
        gauge(DUCKDB_MEMORY, () -> state.read()[1]);
        gauge(DUCKDB_MEMORY_LIMIT, () -> state.read()[2]);
        gauge(DUCKDB_TEMP, () -> state.read()[3]);
        return this;
    }

    /** One query for all four gauges, reused for a moment so a snapshot runs it once. */
    private static final class DuckDBState {
        private final org.duckdb.DuckDBConnection database;
        private double[] values;
        private long readAt;

        DuckDBState(org.duckdb.DuckDBConnection database) {
            this.database = database;
        }

        synchronized double[] read() {
            long now = System.nanoTime();
            if (values != null && now - readAt < 250_000_000L) {
                return values;
            }
            double[] fresh = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            try (Connection connection = database.duplicate();
                 Statement statement = connection.createStatement();
                 java.sql.ResultSet row = statement.executeQuery(
                         "SELECT current_setting('threads'), current_setting('memory_limit'),"
                                 + " (SELECT coalesce(sum(memory_usage_bytes), 0) FROM duckdb_memory()),"
                                 + " greatest((SELECT coalesce(sum(temporary_storage_bytes), 0) FROM duckdb_memory()),"
                                 + " (SELECT coalesce(sum(size), 0) FROM duckdb_temporary_files()))")) {
                if (row.next()) {
                    fresh[0] = Double.parseDouble(row.getString(1));
                    fresh[2] = parseBytes(row.getString(2));
                    fresh[1] = row.getLong(3);
                    fresh[3] = row.getLong(4);
                }
            }
            catch (java.sql.SQLException | RuntimeException e) {
                // A closed database reads as unknown, not as zero.
            }
            values = fresh;
            readAt = now;
            return fresh;
        }
    }

    /** DuckDB's own rendering of a size, such as {@code 488.2 MiB} or {@code 2.0 GB}, in bytes. */
    static double parseBytes(String text) {
        if (text == null) {
            return Double.NaN;
        }
        java.util.regex.Matcher m = Pattern.compile("([0-9.]+)\\s*([KMGTP]?i?B|bytes)?", Pattern.CASE_INSENSITIVE)
                .matcher(text.trim());
        if (!m.matches()) {
            return Double.NaN;
        }
        double number = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "B" : m.group(2).toUpperCase(Locale.ROOT);
        if (unit.equals("BYTES") || unit.equals("B")) {
            return number;
        }
        double base = unit.contains("I") ? 1024 : 1000;
        int power = "KMGTP".indexOf(unit.charAt(0)) + 1;
        return number * Math.pow(base, power);
    }

    // ---------- Process gauges ----------

    private static double machineCpuLoad() {
        java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            double load = sun.getCpuLoad();
            return load < 0 ? Double.NaN : load;
        }
        return Double.NaN;
    }

    private static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            long nanos = sun.getProcessCpuTime();
            if (nanos >= 0) {
                return nanos;
            }
        }
        throw new UnsupportedOperationException("This JVM does not report process CPU time");
    }
}
