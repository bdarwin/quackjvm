package io.quackjvm.core.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads an interval of metrics and says where quackjvm is choking, and what to change.
 *
 * <p>Each rule looks for one cause that has been seen to make quackjvm slow, and only fires on
 * evidence that tells it apart from the others: queueing for a write lock looks like slowness
 * from outside, and so does DuckDB running short of CPU, but one shows as wait time on the lock
 * and the other as busy cores with several statements running at once. The thresholds were
 * checked against a load test that produces each cause on purpose - see
 * {@code MetricsDiagnosisTest}.</p>
 *
 * <pre>
 * MetricsSnapshot before = database.metrics().snapshot();
 * Thread.sleep(10_000);
 * for (Diagnosis.Finding finding : Diagnosis.of(database.metrics().snapshot().minus(before))) {
 *     System.out.println(finding);
 * }
 * </pre>
 */
public final class Diagnosis {

    public enum Cause {
        /** Writes queueing for a collection's write lock. */
        WRITE_LOCK,
        /** DuckDB's statements competing for the machine's cores. */
        CPU,
        /** Transactions failing because another touched the same rows. */
        CONFLICTS,
        /** DuckDB spilling to disk, or near its memory limit. */
        MEMORY,
        /** Statements prepared from scratch rather than reused. */
        PREPARES,
        /** Connections opened and closed rather than pooled. */
        CONNECTION_CHURN,
        /** Statements failing for reasons other than the above. */
        ERRORS
    }

    /**
     * One cause, how much it matters (0 to 1, for ranking), what was seen and what to do.
     */
    public record Finding(Cause cause, double severity, String headline, String evidence, String advice) {
        @Override
        public String toString() {
            return cause + ": " + headline + "\n    seen: " + evidence + "\n    do:   " + advice;
        }
    }

    static final double LOCK_WAIT_SHARE = 0.25;
    static final double LOCK_WAIT_P99_MILLIS = 1.0;
    static final double CPU_BUSY = 0.7;
    static final double CPU_CONCURRENCY = 1.5;
    /** Statements at least this long on average count as heavy: DuckDB runs them in parallel. */
    public static final double HEAVY_STATEMENT_MILLIS = 1.0;
    /** DuckDB's cost of preparing a statement, measured; what a cache miss throws away. */
    static final double PREPARE_MILLIS = 0.2;

    private Diagnosis() {
    }

    /** The causes found in the interval, most severe first; empty when nothing is choking. */
    public static List<Finding> of(MetricsSnapshot interval) {
        List<Finding> findings = new ArrayList<>();
        writeLocks(interval, findings);
        cpu(interval, findings);
        conflicts(interval, findings);
        memory(interval, findings);
        prepares(interval, findings);
        connectionChurn(interval, findings);
        errors(interval, findings);
        findings.sort(Comparator.comparingDouble(Finding::severity).reversed());
        return findings;
    }

    private static void writeLocks(MetricsSnapshot interval, List<Finding> findings) {
        for (Map.Entry<String, TimerSnapshot> entry : interval.scopes(QuackMetrics.WRITE_LOCK_WAIT).entrySet()) {
            String collection = entry.getKey();
            TimerSnapshot wait = entry.getValue();
            TimerSnapshot held = interval.timer(QuackMetrics.REQUEST_WRITE, collection);
            double total = wait.totalNanos() + held.totalNanos();
            if (wait.count() < 10 || total == 0) {
                continue;
            }
            double share = wait.totalNanos() / total;
            if (share < LOCK_WAIT_SHARE || wait.percentileMillis(99) < LOCK_WAIT_P99_MILLIS) {
                continue;
            }
            findings.add(new Finding(Cause.WRITE_LOCK, share,
                    String.format("writes to %s are queueing for its write lock", collection),
                    String.format("%.0f%% of write time spent waiting for the lock; %,d writes, wait p50 %.2f ms,"
                                    + " p99 %.2f ms; each held it %.2f ms on average",
                            share * 100, wait.count(), wait.percentileMillis(50), wait.percentileMillis(99),
                            held.meanMillis()),
                    "write fewer, larger batches - addAll(...) or a DuckDBBulkWriter take the lock once for"
                            + " many objects. If writers never touch the same objects, serializeWrites(false)"
                            + " lets them run together; if they do, it turns this wait into conflicts."));
        }
    }

    private static void cpu(MetricsSnapshot interval, List<Finding> findings) {
        double own = interval.cpuUtilisation();
        double machine = interval.gauge(QuackMetrics.CPU_MACHINE);
        // The busier of the two: DuckDB is as short of cores when another process holds them.
        double busy = Double.isNaN(machine) ? own : Double.isNaN(own) ? machine : Math.max(own, machine);
        // Only statements heavy enough for DuckDB to run in parallel: the choke is several of those
        // each asking for every core. Sub-millisecond writes keep a machine busy too, but on one
        // thread each, and lowering DuckDB's threads would do nothing for them.
        double concurrency = interval.statementConcurrency(HEAVY_STATEMENT_MILLIS);
        if (!(busy >= CPU_BUSY) || !(concurrency >= CPU_CONCURRENCY)) {
            return;
        }
        double cores = interval.gauge(QuackMetrics.CORES);
        double threads = interval.gauge(QuackMetrics.DUCKDB_THREADS);
        List<TimerSnapshot> hottest = interval.statementsByTotalTime();
        long statementNanos = 0;
        for (TimerSnapshot timer : hottest) {
            statementNanos += timer.totalNanos();
        }
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < Math.min(3, hottest.size()); i++) {
            TimerSnapshot timer = hottest.get(i);
            top.append(String.format("%n      %4.0f%%  %,6d x %8.2f ms  %s",
                    100.0 * timer.totalNanos() / Math.max(1, statementNanos), timer.count(), timer.meanMillis(),
                    timer.getName().substring(QuackMetrics.STATEMENT.length() + 1, timer.getName().length() - 1)));
        }
        String threadAdvice = threads > cores / 2 + 0.5
                ? String.format("SET threads = %.0f (half the cores): each statement asks DuckDB for %.0f threads,"
                        + " so %.1f at once ask for %.0f cores out of %.0f. ", cores / 2, threads, concurrency,
                        threads * concurrency, cores)
                : "";
        findings.add(new Finding(Cause.CPU, Math.min(1, busy),
                "statements are competing for CPU",
                String.format("CPU %.0f%% busy across %.0f cores (this process %.0f%%, the whole machine %.0f%%);"
                                + " %.1f heavy statements running at once on average; DuckDB threads = %.0f."
                                + " Where the time went:%s",
                        busy * 100, cores, own * 100, machine * 100, concurrency, threads, top),
                (machine - own >= 0.3 ? "Other processes on this machine are using "
                        + String.format("%.0f%%", (machine - own) * 100) + " of the cores - see what else is running. " : "")
                        + threadAdvice + "Materialize the hottest statement if it is an aggregate many users repeat, and"
                        + " cap how many heavy queries run at once - a semaphore in front of them trims the tail."));
    }

    private static void conflicts(MetricsSnapshot interval, List<Finding> findings) {
        long conflicts = interval.counter(QuackMetrics.STATEMENT_ERRORS, "conflict");
        long retries = interval.counterTotal(QuackMetrics.RETRIES);
        if (conflicts == 0 && retries == 0) {
            return;
        }
        long writes = interval.timer(QuackMetrics.REQUEST_WRITE).count();
        for (TimerSnapshot timer : interval.scopes(QuackMetrics.REQUEST_WRITE).values()) {
            writes += timer.count();
        }
        double rate = conflicts / (double) Math.max(1, writes + conflicts);
        findings.add(new Finding(Cause.CONFLICTS, Math.min(1, 0.5 + rate),
                "transactions are failing on conflicts",
                String.format("%,d statements failed with a conflict and %,d transactions were retried,"
                        + " against %,d write requests", conflicts, retries, writes),
                "two writers are changing the same rows at once and DuckDB fails one of them. Nothing is"
                        + " half-applied, but the work is lost unless retried. Keep serializeWrites on for"
                        + " collections whose writers overlap, and run SparseTable.optimize() through the"
                        + " writers' own instance."));
    }

    private static void memory(MetricsSnapshot interval, List<Finding> findings) {
        double temp = interval.gauge(QuackMetrics.DUCKDB_TEMP);
        double used = interval.gauge(QuackMetrics.DUCKDB_MEMORY);
        double limit = interval.gauge(QuackMetrics.DUCKDB_MEMORY_LIMIT);
        long failures = interval.counter(QuackMetrics.STATEMENT_ERRORS, "memory");
        boolean spilling = temp > 0;
        boolean nearLimit = used > 0 && limit > 0 && used >= 0.9 * limit;
        if (!spilling && !nearLimit && failures == 0) {
            return;
        }
        findings.add(new Finding(Cause.MEMORY, failures > 0 ? 0.9 : 0.6,
                failures > 0 ? "statements are running out of memory" : "DuckDB is short of memory",
                String.format("using %s of a %s limit; %s in temporary files; %,d out-of-memory failures",
                        bytes(used), bytes(limit), bytes(temp), failures),
                "raise memory_limit if the machine has room. Otherwise the large sorts, joins and"
                        + " aggregates spill to disk - correct, but far slower - and pre-aggregating them into"
                        + " a materialization moves that work off the request path."));
    }

    private static void prepares(MetricsSnapshot interval, List<Finding> findings) {
        long hits = interval.counter(QuackMetrics.PREPARE_HITS);
        long allMisses = interval.counter(QuackMetrics.PREPARE_MISSES);
        // Misses with other causes, which have findings of their own. DuckDB's driver closes a
        // prepared statement whose execution fails, so every failure costs one re-prepare; and a
        // new connection starts with an empty cache, so churn makes every prepare a miss.
        if (interval.counter(QuackMetrics.CONNECTIONS_OPENED) >= 0.5 * allMisses) {
            return;
        }
        long misses = allMisses - interval.counterTotal(QuackMetrics.STATEMENT_ERRORS);
        if (misses < 50 || misses < 0.2 * (hits + allMisses)) {
            return;
        }
        double requestMillis = 0;
        for (String name : new String[]{QuackMetrics.REQUEST_READ, QuackMetrics.REQUEST_WRITE}) {
            for (TimerSnapshot timer : interval.scopes(name).values()) {
                requestMillis += timer.totalMillis();
            }
        }
        double wasted = misses * PREPARE_MILLIS;
        double share = requestMillis > 0 ? Math.min(1, wasted / requestMillis) : 0.3;
        findings.add(new Finding(Cause.PREPARES, share * 0.8,
                "statements are being prepared from scratch",
                String.format("%,d of %,d prepares missed the statement cache (%.0f%%), not counting those"
                                + " that re-prepare a failed statement: about %,.0f ms of DuckDB parsing and planning",
                        misses, hits + allMisses, 100.0 * misses / (hits + allMisses), wasted),
                "usually SQL built with values written into it rather than bound with ? placeholders - every"
                        + " distinct string is a new statement to prepare. Bind the values instead."));
    }

    private static void connectionChurn(MetricsSnapshot interval, List<Finding> findings) {
        long opened = interval.counter(QuackMetrics.CONNECTIONS_OPENED);
        long requests = 0;
        for (String name : new String[]{QuackMetrics.REQUEST_READ, QuackMetrics.REQUEST_WRITE}) {
            for (TimerSnapshot timer : interval.scopes(name).values()) {
                requests += timer.count();
            }
        }
        if (opened < 20 || opened < 0.1 * requests) {
            return;
        }
        double ratio = opened / (double) Math.max(1, requests);
        findings.add(new Finding(Cause.CONNECTION_CHURN, Math.min(0.7, ratio * 0.5),
                "connections are being opened rather than reused",
                String.format("%,d connections opened for %,d requests; %.0f in use at the last sample",
                        opened, requests, interval.gauge(QuackMetrics.CONNECTIONS_IN_USE)),
                "raise maxPooledConnections to at least the number of threads using the database at once."
                        + " Each new connection also starts with an empty statement cache."));
    }

    private static void errors(MetricsSnapshot interval, List<Finding> findings) {
        long constraint = interval.counter(QuackMetrics.STATEMENT_ERRORS, "constraint");
        long other = interval.counter(QuackMetrics.STATEMENT_ERRORS, "other");
        if (constraint + other == 0) {
            return;
        }
        findings.add(new Finding(Cause.ERRORS, 0.3,
                "statements are failing",
                String.format("%,d constraint violations and %,d other failures", constraint, other),
                "these are errors rather than slowness - check the application's logs for the exceptions."));
    }

    static String bytes(double value) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if (value >= 1L << 30) {
            return String.format("%.1f GB", value / (1L << 30));
        }
        if (value >= 1L << 20) {
            return String.format("%.0f MB", value / (1L << 20));
        }
        return String.format("%.0f KB", value / 1024);
    }
}
