package io.quackjvm.core.metrics;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Durations of one kind of operation: how many, how long in total, and how they are distributed.
 *
 * <p>The distribution is a log-linear histogram - eight buckets per power of two, so any
 * percentile read from it is within 6% of the true value - kept in a fixed array of counters.
 * Recording is two atomic increments and no allocation, about 20 ns uncontended; the cheapest
 * DuckDB query costs 48 µs. Because the buckets are plain counts, two snapshots can be subtracted
 * to get the percentiles of just the interval between them, which is what makes "p99 over the last
 * ten seconds" possible without a sliding window.</p>
 */
public final class Timer {

    /** Sub-buckets per power of two. */
    private static final int SUB = 8;
    private static final int SUB_BITS = 3;
    static final int BUCKETS = (63 - SUB_BITS + 1) * SUB + SUB;

    private final String name;
    private final AtomicLongArray buckets = new AtomicLongArray(BUCKETS);
    private final LongAdder totalNanos = new LongAdder();
    /** Kept apart from the buckets so the mean is cheap enough to ask for on every execute. */
    private final LongAdder count = new LongAdder();
    /** When this statement may next be profiled; see {@link QuackMetrics#setProfiling}. */
    private final java.util.concurrent.atomic.AtomicLong nextProfileAt = new java.util.concurrent.atomic.AtomicLong();

    Timer(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void record(long nanos) {
        if (nanos < 0) {
            nanos = 0;
        }
        buckets.incrementAndGet(bucketOf(nanos));
        totalNanos.add(nanos);
        count.increment();
    }

    /** Mean of everything recorded so far, in nanoseconds; 0 before anything was. */
    public long meanNanos() {
        long n = count.sum();
        return n == 0 ? 0 : totalNanos.sum() / n;
    }

    /**
     * Claims the right to profile this statement's next execution: true at most once per interval,
     * and only once the statement has a history - enough calls to have a mean worth trusting.
     */
    boolean claimProfile(long now, long intervalNanos, long minMeanNanos) {
        long due = nextProfileAt.get();
        if (now - due < 0 || count.sum() < 5 || meanNanos() < minMeanNanos) {
            return false;
        }
        return nextProfileAt.compareAndSet(due, now + intervalNanos);
    }

    /** Starts timing; pass the result to {@link #stop}. */
    public long start() {
        return System.nanoTime();
    }

    public void stop(long startedAt) {
        record(System.nanoTime() - startedAt);
    }

    public TimerSnapshot snapshot() {
        long[] counts = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            counts[i] = buckets.get(i);
        }
        return new TimerSnapshot(name, counts, totalNanos.sum());
    }

    static int bucketOf(long nanos) {
        if (nanos < SUB) {
            return (int) nanos;
        }
        int exponent = 63 - Long.numberOfLeadingZeros(nanos);
        int sub = (int) ((nanos >>> (exponent - SUB_BITS)) & (SUB - 1));
        return (exponent - SUB_BITS + 1) * SUB + sub;
    }

    /** The smallest duration that lands in this bucket. */
    static long lowerBound(int bucket) {
        if (bucket < SUB) {
            return bucket;
        }
        int exponent = bucket / SUB + SUB_BITS - 1;
        int sub = bucket % SUB;
        return (long) (SUB + sub) << (exponent - SUB_BITS);
    }

    /** A value representative of the bucket: the middle of its range. */
    static long midpoint(int bucket) {
        if (bucket < SUB) {
            return bucket;
        }
        long lower = lowerBound(bucket);
        long width = 1L << (bucket / SUB - 1);
        return lower + width / 2;
    }
}
