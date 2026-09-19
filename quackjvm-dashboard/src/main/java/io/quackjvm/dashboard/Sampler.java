package io.quackjvm.dashboard;

import io.quackjvm.core.metrics.Diagnosis;
import io.quackjvm.core.metrics.MetricsSnapshot;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.metrics.TimerSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Takes a snapshot every second and keeps what the page needs: a short per-second history of a
 * few headline numbers, and just enough whole snapshots to compute the last ten seconds and the
 * last minute.
 *
 * <p>Whole snapshots are not kept for the full history: each holds a histogram per timer, and with
 * a few hundred statement shapes a snapshot runs to a megabyte. Eleven one-second snapshots and
 * seven ten-second ones cover both windows.</p>
 */
final class Sampler {

    /** The window the diagnosis and the headline numbers are computed over. */
    static final int SHORT_WINDOW = 10;
    /** The window the statement table is computed over. */
    static final int LONG_WINDOW = 60;

    private final QuackMetrics metrics;
    private final int historySeconds;
    private final Deque<MetricsSnapshot> fine = new ArrayDeque<>();
    private final Deque<MetricsSnapshot> coarse = new ArrayDeque<>();
    private final Deque<Point> points = new ArrayDeque<>();
    private long samples;

    Sampler(QuackMetrics metrics, int historySeconds) {
        this.metrics = metrics;
        this.historySeconds = historySeconds;
    }

    /** Takes a snapshot; returns the second since the last one, or null for the very first. */
    synchronized MetricsSnapshot sample() {
        MetricsSnapshot now = metrics.snapshot();
        MetricsSnapshot previous = fine.peekLast();
        MetricsSnapshot second = previous == null ? null : now.minus(previous);
        if (second != null) {
            points.addLast(Point.of(second));
            while (points.size() > historySeconds) {
                points.removeFirst();
            }
        }
        fine.addLast(now);
        while (fine.size() > SHORT_WINDOW + 1) {
            fine.removeFirst();
        }
        if (samples++ % 10 == 0) {
            coarse.addLast(now);
            while (coarse.size() > LONG_WINDOW / 10 + 1) {
                coarse.removeFirst();
            }
        }
        return second;
    }

    /** Roughly the last ten seconds, or null before there are two snapshots. */
    synchronized MetricsSnapshot shortWindow() {
        return fine.size() < 2 ? null : fine.peekLast().minus(fine.peekFirst());
    }

    /** Roughly the last minute - less while the dashboard is younger than that. */
    synchronized MetricsSnapshot longWindow() {
        MetricsSnapshot latest = fine.peekLast();
        MetricsSnapshot earliest = coarse.peekFirst();
        if (latest == null || earliest == null || latest == earliest) {
            return shortWindow();
        }
        return latest.minus(earliest);
    }

    synchronized List<Point> points() {
        return new ArrayList<>(points);
    }

    /** The headline numbers of one second, kept for the charts. */
    record Point(long epochMillis, double readsPerSecond, double writesPerSecond, double readP99, double writeP99,
                 double lockWaitShare, double cpu, double heavyStatements, double memoryBytes, double tempBytes,
                 double conflictsPerSecond) {

        static Point of(MetricsSnapshot interval) {
            double seconds = Math.max(1e-9, interval.getIntervalSeconds());
            TimerSnapshot reads = total(interval, QuackMetrics.REQUEST_READ);
            TimerSnapshot writes = total(interval, QuackMetrics.REQUEST_WRITE);
            TimerSnapshot waits = total(interval, QuackMetrics.WRITE_LOCK_WAIT);
            double waitAndWork = waits.totalNanos() + writes.totalNanos();
            return new Point(interval.getTakenAt().toEpochMilli(),
                    reads.count() / seconds, writes.count() / seconds,
                    reads.count() == 0 ? Double.NaN : reads.percentileMillis(99),
                    writes.count() == 0 ? Double.NaN : writes.percentileMillis(99),
                    waitAndWork == 0 ? 0 : waits.totalNanos() / waitAndWork,
                    interval.cpuUtilisation(),
                    interval.statementConcurrency(Diagnosis.HEAVY_STATEMENT_MILLIS),
                    interval.gauge(QuackMetrics.DUCKDB_MEMORY),
                    interval.gauge(QuackMetrics.DUCKDB_TEMP),
                    interval.counter(QuackMetrics.STATEMENT_ERRORS, "conflict") / seconds);
        }
    }

    /** One timer's recordings summed over all its scopes. */
    static TimerSnapshot total(MetricsSnapshot interval, String name) {
        TimerSnapshot sum = TimerSnapshot.none(name);
        for (Map.Entry<String, TimerSnapshot> entry : interval.scopes(name).entrySet()) {
            sum = sum.plus(entry.getValue());
        }
        return sum;
    }
}
