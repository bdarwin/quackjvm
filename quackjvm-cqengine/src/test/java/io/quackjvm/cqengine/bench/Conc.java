package io.quackjvm.cqengine.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * A small fixed-duration concurrency harness: N threads hammer an operation, and the run reports
 * aggregate throughput plus a latency distribution. Deliberately not JMH - JMH's thread handling
 * makes a per-thread-count sweep over shared mutable state awkward, and every operation here is
 * hundreds of microseconds of native work, so harness overhead is irrelevant.
 */
public final class Conc {

    private Conc() {
    }

    /** One unit of work. Throwing is counted rather than fatal, so conflicts can be measured. */
    public interface Op {
        void run(int thread, long iteration) throws Exception;
    }

    public static final class Result {
        public final int threads;
        public final long ops;
        public final long errors;
        public final double seconds;
        public final long[] latenciesNanos;
        public final List<String> errorKinds;

        Result(int threads, long ops, long errors, double seconds, long[] latenciesNanos, List<String> errorKinds) {
            this.threads = threads;
            this.ops = ops;
            this.errors = errors;
            this.seconds = seconds;
            this.latenciesNanos = latenciesNanos;
            this.errorKinds = errorKinds;
        }

        public double opsPerSecond() {
            return ops / seconds;
        }

        public double meanMicros() {
            if (latenciesNanos.length == 0) return Double.NaN;
            double total = 0;
            for (long l : latenciesNanos) total += l;
            return total / latenciesNanos.length / 1000.0;
        }

        public double percentileMicros(double p) {
            if (latenciesNanos.length == 0) return Double.NaN;
            int index = (int) Math.min(latenciesNanos.length - 1L,
                    Math.round(p / 100.0 * (latenciesNanos.length - 1)));
            return latenciesNanos[index] / 1000.0;
        }

        @Override
        public String toString() {
            return String.format("%2d thr  %9.0f ops/s  mean %8.1f us  p50 %8.1f  p99 %9.1f  errors %d",
                    threads, opsPerSecond(), meanMicros(), percentileMicros(50), percentileMicros(99), errors);
        }
    }

    /** Runs warmup then measurement, returning the best of {@code rounds} measurement rounds. */
    public static Result best(int threads, long warmupMs, long measureMs, int rounds, IntFunction<Op> opFactory) {
        run(threads, warmupMs, opFactory);
        Result best = null;
        for (int r = 0; r < rounds; r++) {
            Result result = run(threads, measureMs, opFactory);
            if (best == null || result.opsPerSecond() > best.opsPerSecond()) {
                best = result;
            }
        }
        return best;
    }

    public static Result run(int threads, long durationMs, IntFunction<Op> opFactory) {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();
        List<String> errorKinds = java.util.Collections.synchronizedList(new ArrayList<>());
        long[][] samples = new long[threads][];
        long[] counts = new long[threads];
        int sampleCap = 200_000;

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            workers[t] = new Thread(() -> {
                Op op = opFactory.apply(thread);
                long[] mine = new long[sampleCap];
                int sampled = 0;
                long count = 0;
                ready.countDown();
                try {
                    go.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (!stop.get()) {
                    long start = System.nanoTime();
                    try {
                        op.run(thread, count);
                    }
                    catch (Throwable e) {
                        errors.incrementAndGet();
                        if (errorKinds.size() < 40) {
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            errorKinds.add(root.getClass().getSimpleName() + ": " + root.getMessage());
                        }
                    }
                    long elapsed = System.nanoTime() - start;
                    if (sampled < sampleCap) mine[sampled++] = elapsed;
                    count++;
                }
                samples[thread] = Arrays.copyOf(mine, sampled);
                counts[thread] = count;
                done.countDown();
            }, "bench-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }

        try {
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            Thread.sleep(durationMs);
            stop.set(true);
            done.await();
            double seconds = (System.nanoTime() - start) / 1e9;
            long totalOps = 0;
            int totalSamples = 0;
            for (int t = 0; t < threads; t++) {
                totalOps += counts[t];
                totalSamples += samples[t] == null ? 0 : samples[t].length;
            }
            long[] all = new long[totalSamples];
            int at = 0;
            for (long[] s : samples) {
                if (s != null) {
                    System.arraycopy(s, 0, all, at, s.length);
                    at += s.length;
                }
            }
            Arrays.sort(all);
            return new Result(threads, totalOps, errors.get(), seconds, all, errorKinds);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Runs N threads a fixed number of operations each, for writes where the work is finite. */
    public static Result fixedCount(int threads, int opsPerThread, IntFunction<Op> opFactory) {
        AtomicLong errors = new AtomicLong();
        List<String> errorKinds = java.util.Collections.synchronizedList(new ArrayList<>());
        long[][] samples = new long[threads][];
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            workers[t] = new Thread(() -> {
                Op op = opFactory.apply(thread);
                long[] mine = new long[opsPerThread];
                ready.countDown();
                try {
                    go.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < opsPerThread; i++) {
                    long start = System.nanoTime();
                    try {
                        op.run(thread, i);
                    }
                    catch (Throwable e) {
                        errors.incrementAndGet();
                        if (errorKinds.size() < 40) {
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            errorKinds.add(root.getClass().getSimpleName() + ": " + root.getMessage());
                        }
                    }
                    mine[i] = System.nanoTime() - start;
                }
                samples[thread] = mine;
            }, "bench-" + t);
            workers[t].start();
        }
        try {
            ready.await();
            long start = System.nanoTime();
            go.countDown();
            for (Thread w : workers) w.join();
            double seconds = (System.nanoTime() - start) / 1e9;
            long[] all = new long[threads * opsPerThread];
            int at = 0;
            for (long[] s : samples) {
                if (s != null) {
                    System.arraycopy(s, 0, all, at, s.length);
                    at += s.length;
                }
            }
            all = Arrays.copyOf(all, at);
            Arrays.sort(all);
            return new Result(threads, (long) threads * opsPerThread, errors.get(), seconds, all, errorKinds);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
