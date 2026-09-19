package io.quackjvm.core.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What DuckDB did for one real execution of a statement: what {@code EXPLAIN ANALYZE} would show,
 * taken from a call the application was making anyway rather than from running the query again.
 *
 * <p>DuckDB's profile carries data values - a string written into the statement, and even a value
 * bound to a {@code ?}, appear in its filters. They are removed here, the same way they are removed
 * from statement shapes, so that a profile can be shown and recorded without showing any data.</p>
 */
public final class QueryProfile {

    /** One step of the plan, in the order DuckDB printed it; {@code depth} 0 is the root. */
    public record Operator(int depth, String name, double millis, double rows, double estimatedRows,
                           double rowsScanned, String detail) {
    }

    private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
    /** A number that is a value - not part of a name, a column reference like #0, or a decimal. */
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_0-9#.\"\\]])-?\\d+(\\.\\d+)?(?![A-Za-z_0-9\"])");

    private final String shape;
    private final Instant capturedAt;
    private final double latencyMillis;
    private final double cpuMillis;
    private final double rowsScanned;
    private final double rowsReturned;
    private final double peakMemoryBytes;
    private final double tempBytes;
    private final List<Operator> operators;

    QueryProfile(String shape, Instant capturedAt, double latencyMillis, double cpuMillis, double rowsScanned,
                 double rowsReturned, double peakMemoryBytes, double tempBytes, List<Operator> operators) {
        this.shape = shape;
        this.capturedAt = capturedAt;
        this.latencyMillis = latencyMillis;
        this.cpuMillis = cpuMillis;
        this.rowsScanned = rowsScanned;
        this.rowsReturned = rowsReturned;
        this.peakMemoryBytes = peakMemoryBytes;
        this.tempBytes = tempBytes;
        this.operators = Collections.unmodifiableList(operators);
    }

    /**
     * Reads DuckDB's JSON profile of the last query on a connection.
     *
     * @return null when the profile is not of the expected statement - another statement ran in
     * between, or the results were not read to the end, which leaves DuckDB's profile empty
     */
    static QueryProfile fromDuckDB(String json, String expectedSql, String shape, Instant capturedAt,
                                   double wallMillis) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Object parsed = ProfileJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            return null;
        }
        String queryName = String.valueOf(root.get("query_name"));
        if (!sameStatement(queryName, expectedSql) || !(number(root, "latency") > 0)) {
            return null;
        }
        List<Operator> operators = new ArrayList<>();
        for (Object child : list(root.get("children"))) {
            collect(child, 0, operators);
        }
        // Not DuckDB's own "latency": with the profiler switched on for one statement at a time, it
        // measures from when the profiler was last switched rather than from when the statement
        // began - measured at 300 ms for a 2 ms statement sampled 300 ms after the previous one.
        // The time is the caller's own measurement of this execution.
        return new QueryProfile(shape, capturedAt,
                wallMillis,
                number(root, "cpu_time") * 1000,
                number(root, "cumulative_rows_scanned"),
                number(root, "rows_returned"),
                number(root, "system_peak_buffer_memory"),
                number(root, "system_peak_temp_dir_size"),
                operators);
    }

    private static void collect(Object node, int depth, List<Operator> into) {
        if (!(node instanceof Map<?, ?> operator)) {
            return;
        }
        Object name = operator.get("operator_name");
        if (name == null) {
            name = operator.get("operator_type");
        }
        StringBuilder detail = new StringBuilder();
        double estimated = Double.NaN;
        if (operator.get("extra_info") instanceof Map<?, ?> info) {
            for (Map.Entry<?, ?> entry : info.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.equals("Estimated Cardinality")) {
                    try {
                        estimated = Double.parseDouble(String.valueOf(entry.getValue()));
                    }
                    catch (NumberFormatException ignored) {
                        // Left unknown.
                    }
                    continue;
                }
                String value = entry.getValue() instanceof List<?> values
                        ? String.join(", ", values.stream().map(String::valueOf).toList())
                        : String.valueOf(entry.getValue());
                if (!value.isBlank()) {
                    if (detail.length() > 0) {
                        detail.append("; ");
                    }
                    detail.append(key).append(": ").append(scrub(value));
                }
            }
        }
        into.add(new Operator(depth, String.valueOf(name).trim(), number(operator, "operator_timing") * 1000,
                number(operator, "operator_cardinality"), estimated, number(operator, "operator_rows_scanned"),
                detail.length() > 400 ? detail.substring(0, 397) + "..." : detail.toString()));
        for (Object child : list(operator.get("children"))) {
            collect(child, depth + 1, into);
        }
    }

    /** Removes the values from a profile's text: quoted strings and numbers become {@code ?}. */
    static String scrub(String text) {
        String scrubbed = STRING.matcher(text).replaceAll("?");
        return NUMBER.matcher(scrubbed).replaceAll("?");
    }

    private static boolean sameStatement(String profiled, String expected) {
        return expected != null && squash(profiled).equals(squash(expected));
    }

    private static String squash(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Double d ? d : Double.NaN;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    /** The statement, as its shape - values removed. */
    public String getShape() {
        return shape;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    /**
     * Wall-clock time of the profiled execution, as quackjvm measured it: for a query, from execute
     * until its results were closed, the same as the statement's timer.
     */
    public double getLatencyMillis() {
        return latencyMillis;
    }

    /** CPU time across all of DuckDB's threads for the execution. */
    public double getCpuMillis() {
        return cpuMillis;
    }

    /** How many cores the execution kept busy on average: CPU time over wall-clock time. */
    public double getParallelism() {
        return latencyMillis > 0 ? cpuMillis / latencyMillis : Double.NaN;
    }

    public double getRowsScanned() {
        return rowsScanned;
    }

    public double getRowsReturned() {
        return rowsReturned;
    }

    public double getPeakMemoryBytes() {
        return peakMemoryBytes;
    }

    /** Bytes the execution spilled to temporary files; 0 if it fit in memory. */
    public double getTempBytes() {
        return tempBytes;
    }

    /** The plan, root first, each step with its own time and the rows it produced. */
    public List<Operator> getOperators() {
        return operators;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(String.format(
                "%s%n  %.2f ms, %.1f cores busy, %,.0f rows scanned, %,.0f returned, peak memory %,.0f bytes, spilled %,.0f",
                shape, latencyMillis, getParallelism(), rowsScanned, rowsReturned, peakMemoryBytes, tempBytes));
        for (Operator operator : operators) {
            out.append(String.format("%n  %s%-20s %8.2f ms %,12.0f rows  %s", "  ".repeat(operator.depth()),
                    operator.name(), operator.millis(), operator.rows(), operator.detail()));
        }
        return out.toString();
    }
}
