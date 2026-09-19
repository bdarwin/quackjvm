package io.quackjvm.core.metrics;

/**
 * A {@link Timer} as it stood at one moment, or - from {@link #minus} - over the interval between
 * two such moments. Immutable.
 */
public final class TimerSnapshot {

    private final String name;
    private final long[] counts;
    private final long totalNanos;
    private final long count;

    TimerSnapshot(String name, long[] counts, long totalNanos) {
        this.name = name;
        this.counts = counts;
        this.totalNanos = totalNanos;
        long n = 0;
        for (long c : counts) {
            n += c;
        }
        this.count = n;
    }

    static TimerSnapshot empty(String name) {
        return new TimerSnapshot(name, new long[Timer.BUCKETS], 0);
    }

    public String getName() {
        return name;
    }

    public long count() {
        return count;
    }

    public long totalNanos() {
        return totalNanos;
    }

    public double totalMillis() {
        return totalNanos / 1e6;
    }

    public double meanMillis() {
        return count == 0 ? 0 : totalNanos / 1e6 / count;
    }

    /** The given percentile, 0 to 100, in milliseconds; within about 6% of the true value. */
    public double percentileMillis(double percentile) {
        if (count == 0) {
            return 0;
        }
        long rank = (long) Math.ceil(count * Math.min(100, Math.max(0, percentile)) / 100.0);
        rank = Math.max(1, rank);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return Timer.midpoint(i) / 1e6;
            }
        }
        return Timer.midpoint(counts.length - 1) / 1e6;
    }

    /** The largest recorded duration, to the resolution of the histogram. */
    public double maxMillis() {
        for (int i = counts.length - 1; i >= 0; i--) {
            if (counts[i] > 0) {
                return Timer.midpoint(i) / 1e6;
            }
        }
        return 0;
    }

    /** What was recorded after {@code earlier} and up to this snapshot. */
    public TimerSnapshot minus(TimerSnapshot earlier) {
        if (earlier == null) {
            return this;
        }
        long[] delta = new long[counts.length];
        for (int i = 0; i < counts.length; i++) {
            delta[i] = Math.max(0, counts[i] - earlier.counts[i]);
        }
        return new TimerSnapshot(name, delta, Math.max(0, totalNanos - earlier.totalNanos));
    }

    @Override
    public String toString() {
        return String.format("%s: n=%d mean=%.3fms p50=%.3fms p99=%.3fms max=%.3fms",
                name, count, meanMillis(), percentileMillis(50), percentileMillis(99), maxMillis());
    }
}
