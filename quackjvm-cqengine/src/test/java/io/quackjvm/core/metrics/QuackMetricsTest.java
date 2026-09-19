package io.quackjvm.core.metrics;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class QuackMetricsTest {

    @Test
    public void everyDurationLandsInABucketThatContainsIt() {
        Random random = new Random(1);
        for (int i = 0; i < 100_000; i++) {
            long nanos = random.nextInt(4) == 0 ? random.nextInt(64) : (long) Math.exp(random.nextDouble() * 40);
            int bucket = Timer.bucketOf(nanos);
            assertTrue(nanos + " below its bucket", Timer.lowerBound(bucket) <= nanos);
            assertTrue(nanos + " above its bucket", bucket + 1 >= Timer.BUCKETS || nanos < Timer.lowerBound(bucket + 1));
        }
    }

    @Test
    public void percentilesAreWithinSixPercent() {
        Timer timer = new QuackMetrics().timer("t");
        for (int micros = 1; micros <= 10_000; micros++) {
            timer.record(micros * 1_000L);
        }
        TimerSnapshot snapshot = timer.snapshot();
        assertEquals(10_000, snapshot.count());
        for (double p : new double[]{50, 90, 99, 99.9}) {
            double exact = p * 10_000 / 100 / 1000.0;
            assertEquals("p" + p, exact, snapshot.percentileMillis(p), exact * 0.0625);
        }
        assertEquals(5.0005, snapshot.meanMillis(), 1e-9);
    }

    @Test
    public void subtractingSnapshotsGivesJustTheInterval() {
        QuackMetrics metrics = new QuackMetrics();
        for (int i = 0; i < 1_000; i++) {
            metrics.timer("t").record(1_000_000);
        }
        metrics.counter("c").add(5);
        MetricsSnapshot before = metrics.snapshot();
        for (int i = 0; i < 10; i++) {
            metrics.timer("t").record(100_000_000);
        }
        metrics.counter("c").add(2);
        MetricsSnapshot interval = metrics.snapshot().minus(before);

        assertEquals(10, interval.timer("t").count());
        assertEquals(100, interval.timer("t").percentileMillis(50), 6.25);
        assertEquals(2, interval.counter("c"));
        assertTrue(interval.getIntervalNanos() > 0);
    }

    @Test
    public void statementsWithDifferentValuesHaveOneShape() {
        assertEquals(QuackMetrics.shapeOf("SELECT * FROM t WHERE id IN (?, ?, ?)"),
                QuackMetrics.shapeOf("SELECT *  FROM t\n WHERE id IN (?,?,?,?,?,?,?)"));
        assertEquals(QuackMetrics.shapeOf("SELECT name FROM c WHERE id = 17 AND name <> 'x'"),
                QuackMetrics.shapeOf("SELECT name FROM c WHERE id = 4 AND name <> 'it''s'"));
        assertEquals(QuackMetrics.shapeOf("INSERT INTO t VALUES (?, ?), (?, ?)"),
                QuackMetrics.shapeOf("INSERT INTO t VALUES (?, ?), (?, ?), (?, ?)"));
        // Digits inside identifiers are names, not values.
        assertTrue(QuackMetrics.shapeOf("SELECT col1 FROM \"t2\"").contains("col1"));
    }

    @Test
    public void duckdbSizesAreParsed() {
        assertEquals(488.2 * 1024 * 1024, QuackMetrics.parseBytes("488.2 MiB"), 1);
        assertEquals(2e9, QuackMetrics.parseBytes("2.0 GB"), 1);
        assertEquals(512, QuackMetrics.parseBytes("512 bytes"), 0);
        assertTrue(Double.isNaN(QuackMetrics.parseBytes("lots")));
    }

    @Test
    public void failuresAreClassifiedByCause() {
        assertEquals("conflict", QuackMetrics.classify(new RuntimeException("x",
                new java.sql.SQLException("TransactionContext Error: Conflict on tuple deletion!"))));
        assertEquals("constraint", QuackMetrics.classify(new java.sql.SQLException(
                "Constraint Error: Duplicate key \"id: 1\" violates primary key constraint.")));
        assertEquals("memory", QuackMetrics.classify(new java.sql.SQLException("Out of Memory Error: failed to allocate")));
        assertEquals("other", QuackMetrics.classify(new java.sql.SQLException("Parser Error")));
    }
}
