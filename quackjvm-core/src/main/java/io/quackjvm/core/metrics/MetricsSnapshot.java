package io.quackjvm.core.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every metric of a {@link QuackMetrics} at one moment - or, from {@link #minus}, over the interval
 * between two moments, which is what a dashboard and a {@link Diagnosis} want. Immutable.
 *
 * <p>Timers and counters are subtracted over an interval; gauges are what the later snapshot saw.</p>
 */
public final class MetricsSnapshot {

    private final long takenAtNanos;
    private final Instant takenAt;
    private final long intervalNanos;
    private final Map<String, TimerSnapshot> timers;
    private final Map<String, Long> counters;
    private final Map<String, Double> gauges;

    MetricsSnapshot(long takenAtNanos, Instant takenAt, long intervalNanos, Map<String, TimerSnapshot> timers,
                    Map<String, Long> counters, Map<String, Double> gauges) {
        this.takenAtNanos = takenAtNanos;
        this.takenAt = takenAt;
        this.intervalNanos = intervalNanos;
        this.timers = Collections.unmodifiableMap(timers);
        this.counters = Collections.unmodifiableMap(counters);
        this.gauges = Collections.unmodifiableMap(gauges);
    }

    /** The activity between {@code earlier} and this snapshot. */
    public MetricsSnapshot minus(MetricsSnapshot earlier) {
        Map<String, TimerSnapshot> timerDelta = new TreeMap<>();
        timers.forEach((name, timer) -> timerDelta.put(name, timer.minus(earlier.timers.get(name))));
        Map<String, Long> counterDelta = new TreeMap<>();
        counters.forEach((name, value) -> counterDelta.put(name, value - earlier.counters.getOrDefault(name, 0L)));
        return new MetricsSnapshot(takenAtNanos, takenAt, takenAtNanos - earlier.takenAtNanos,
                timerDelta, counterDelta, gauges);
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    /** The length of the interval this covers, or 0 for a snapshot taken on its own. */
    public long getIntervalNanos() {
        return intervalNanos;
    }

    public double getIntervalSeconds() {
        return intervalNanos / 1e9;
    }

    public Map<String, TimerSnapshot> getTimers() {
        return timers;
    }

    public Map<String, Long> getCounters() {
        return counters;
    }

    public Map<String, Double> getGauges() {
        return gauges;
    }

    public TimerSnapshot timer(String name) {
        TimerSnapshot timer = timers.get(name);
        return timer != null ? timer : TimerSnapshot.empty(name);
    }

    public TimerSnapshot timer(String name, String scope) {
        return timer(QuackMetrics.scoped(name, scope));
    }

    public long counter(String name) {
        return counters.getOrDefault(name, 0L);
    }

    public long counter(String name, String scope) {
        return counter(QuackMetrics.scoped(name, scope));
    }

    public double gauge(String name) {
        return gauges.getOrDefault(name, Double.NaN);
    }

    /** Every timer of the given name, one per scope, keyed by scope. */
    public Map<String, TimerSnapshot> scopes(String name) {
        Map<String, TimerSnapshot> result = new TreeMap<>();
        String prefix = name + "[";
        timers.forEach((key, timer) -> {
            if (key.startsWith(prefix) && key.endsWith("]")) {
                result.put(key.substring(prefix.length(), key.length() - 1), timer);
            }
        });
        return result;
    }

    /** Every counter of the given name, one per scope, keyed by scope. */
    public Map<String, Long> counterScopes(String name) {
        Map<String, Long> result = new TreeMap<>();
        String prefix = name + "[";
        counters.forEach((key, value) -> {
            if (key.startsWith(prefix) && key.endsWith("]")) {
                result.put(key.substring(prefix.length(), key.length() - 1), value);
            }
        });
        return result;
    }

    /** The sum of a counter over all its scopes. */
    public long counterTotal(String name) {
        long total = counter(name);
        for (long value : counterScopes(name).values()) {
            total += value;
        }
        return total;
    }

    /**
     * How busy the machine's cores were with this process over the interval, 0 to 1: CPU time used
     * divided by the CPU time there was. Includes DuckDB's native threads, which is the point - a
     * JVM profiler cannot see them. NaN for a snapshot that is not an interval.
     */
    public double cpuUtilisation() {
        Long cpu = counters.get(QuackMetrics.CPU_TIME);
        double cores = gauge(QuackMetrics.CORES);
        if (intervalNanos <= 0 || cpu == null || !(cores > 0)) {
            return Double.NaN;
        }
        return cpu / (intervalNanos * cores);
    }

    /**
     * On average, how many statements were executing at once over the interval - their total time
     * divided by the interval (Little's law). Needs no gauge sampled at the right moment, and cannot
     * miss a burst between samples.
     */
    public double statementConcurrency() {
        if (intervalNanos <= 0) {
            return Double.NaN;
        }
        long total = 0;
        for (TimerSnapshot timer : scopes(QuackMetrics.STATEMENT).values()) {
            total += timer.totalNanos();
        }
        return total / (double) intervalNanos;
    }

    /** Statement shapes by the time they took over the interval, most first. */
    public List<TimerSnapshot> statementsByTotalTime() {
        List<TimerSnapshot> all = new ArrayList<>(scopes(QuackMetrics.STATEMENT).values());
        all.removeIf(timer -> timer.count() == 0);
        all.sort((a, b) -> Long.compare(b.totalNanos(), a.totalNanos()));
        return all;
    }
}
