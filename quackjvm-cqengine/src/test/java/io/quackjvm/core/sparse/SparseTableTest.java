package io.quackjvm.core.sparse;

import io.quackjvm.core.sql.Materialization;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SparseTableTest {

    private DuckDBConnection connection;
    private SparseTable samples;

    @Before
    public void open() throws SQLException {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        samples = SparseTable.named("sample");
        samples.create(connection);
    }

    @After
    public void close() throws SQLException {
        connection.close();
    }

    /** Random sparse data, and the same data as plain maps to check the table against. */
    private static Map<Long, Map<String, Double>> randomData(int records, int possible, int perRecord, long seed) {
        Random random = new Random(seed);
        Map<Long, Map<String, Double>> data = new TreeMap<>();
        for (long r = 0; r < records; r++) {
            Map<String, Double> points = new TreeMap<>();
            while (points.size() < perRecord) {
                double u = random.nextDouble();
                points.put("p" + (int) (possible * u * u), Math.round(random.nextDouble() * 10_000) / 100.0);
            }
            data.put(r, points);
        }
        return data;
    }

    private static SparseBatch batchOf(Map<Long, Map<String, Double>> data) {
        SparseBatch.Builder builder = SparseBatch.builder();
        data.forEach((id, points) -> {
            builder.record(id);
            points.forEach(builder::put);
        });
        return builder.build();
    }

    private long count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // ---------- Round trip ----------

    @Test
    public void everyValueComesBackExactly() {
        Map<Long, Map<String, Double>> data = randomData(300, 400, 20, 1);
        samples.append(connection, batchOf(data));

        for (Map.Entry<Long, Map<String, Double>> expected : data.entrySet()) {
            assertEquals("record " + expected.getKey(), expected.getValue(),
                    new TreeMap<>(samples.read(connection, expected.getKey()).toMap()));
        }
        assertEquals(300 * 20, samples.valueCount(connection));
        assertEquals(300, samples.recordCount(connection));
    }

    @Test
    public void aRecordThatWasNeverWrittenReadsAsEmpty() {
        SparseRecord nothing = samples.read(connection, 42);
        assertTrue(nothing.isEmpty());
        assertFalse(nothing.get("anything").isPresent());
    }

    @Test
    public void onlyPresentValuesAreStored() throws SQLException {
        samples.append(connection, SparseBatch.builder()
                .record(1).put("temperature", 21.5).put("pressure", 101.3)
                .record(2).put("humidity", 40.0)
                .build());
        assertEquals("three values, not two records times three data points",
                3, count("SELECT count(*) FROM sample_point"));
        assertEquals("and nothing stored is NULL",
                0, count("SELECT count(*) FROM sample_point WHERE value IS NULL"));
    }

    // ---------- The dictionary ----------

    @Test
    public void newDataPointsGrowTheDictionaryNotTheSchema() throws SQLException {
        samples.append(connection, SparseBatch.builder().record(1).put("a", 1).put("b", 2).build());
        long columnsBefore = count("SELECT count(*) FROM duckdb_columns() WHERE table_name = 'sample_point'");

        samples.append(connection, SparseBatch.builder().record(2).put("b", 3).put("c", 4).put("d", 5).build());

        assertEquals("a, b, c and d, each once", 4, samples.attributeCount(connection));
        assertEquals("a new data point is a dictionary row, never a column",
                columnsBefore, count("SELECT count(*) FROM duckdb_columns() WHERE table_name = 'sample_point'"));
    }

    @Test
    public void thousandsOfNewNamesInOneBatch() {
        SparseBatch.Builder builder = SparseBatch.builder();
        for (int record = 0; record < 3; record++) {
            builder.record(record);
            for (int point = 0; point < 2_500; point++) {
                builder.put("p" + (record * 1_000 + point), point);   // overlapping ranges
            }
        }
        samples.append(connection, builder.build());
        assertEquals(4_500, samples.attributeCount(connection));
        assertEquals(1_234.0, samples.read(connection, 1).get("p2234").getAsDouble(), 0);
    }

    // ---------- Append and replace ----------

    @Test
    public void replaceMakesARecordHoldExactlyTheNewValues() {
        samples.append(connection, SparseBatch.builder()
                .record(1).put("a", 1).put("b", 2)
                .record(2).put("a", 9)
                .build());
        samples.replace(connection, SparseBatch.builder().record(1).put("b", 20).put("c", 30).build());

        assertEquals(Map.of("b", 20.0, "c", 30.0), samples.read(connection, 1).toMap());
        assertEquals("a record not in the batch is untouched", Map.of("a", 9.0), samples.read(connection, 2).toMap());
    }

    @Test
    public void replacingWithNoValuesRemovesTheRecord() {
        samples.append(connection, SparseBatch.builder().record(1).put("a", 1).record(2).put("a", 2).build());
        samples.replace(connection, SparseBatch.builder().record(1).build());
        assertTrue(samples.read(connection, 1).isEmpty());
        assertEquals(1, samples.recordCount(connection));
    }

    @Test
    public void replacingMoreRecordsThanFitInOneStatement() {
        Map<Long, Map<String, Double>> first = randomData(3_000, 50, 5, 3);
        samples.append(connection, batchOf(first));
        Map<Long, Map<String, Double>> second = randomData(3_000, 50, 3, 4);
        samples.replace(connection, batchOf(second));

        assertEquals(3_000 * 3, samples.valueCount(connection));
        assertEquals(second.get(1234L), new TreeMap<>(samples.read(connection, 1234).toMap()));
    }

    // ---------- The batch builder ----------

    @Test
    public void aRecordCannotHaveTheSameDataPointTwice() {
        try {
            SparseBatch.builder().record(1).put("a", 1).put("a", 2);
            fail("a repeated data point within a record should be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already has a value for 'a'"));
        }
        // But the same data point in two different records is ordinary.
        assertEquals(2, SparseBatch.builder().record(1).put("a", 1).record(2).put("a", 2).build().valueCount());
    }

    @Test
    public void aBatchCannotHoldTheSameRecordTwice() {
        try {
            SparseBatch.builder().record(1).put("a", 1).record(2).record(1).put("b", 2).build();
            fail("a repeated record should be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("appears twice"));
        }
    }

    // ---------- Views ----------

    @Test
    public void theViewMatchesAPivotOfTheSameData() throws SQLException {
        Map<Long, Map<String, Double>> data = randomData(500, 200, 15, 5);
        samples.append(connection, batchOf(data));
        List<String> chosen = List.of("p0", "p3", "p17", "p42", "p150");

        Map<Long, List<Double>> fromView = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(samples.viewSql(connection, chosen))) {
            while (rows.next()) {
                List<Double> values = new ArrayList<>();
                for (int i = 0; i < chosen.size(); i++) {
                    double v = rows.getDouble(i + 2);
                    values.add(rows.wasNull() ? null : v);
                }
                fromView.put(rows.getLong(1), values);
            }
        }
        Map<Long, List<Double>> expected = new TreeMap<>();
        data.forEach((id, points) -> {
            List<Double> values = new ArrayList<>();
            boolean any = false;
            for (String name : chosen) {
                values.add(points.get(name));
                any |= points.containsKey(name);
            }
            if (any) {
                expected.put(id, values);
            }
        });
        assertEquals(expected, fromView);
    }

    @Test
    public void aDataPointNeverSeenGivesAColumnOfNulls() throws SQLException {
        samples.append(connection, SparseBatch.builder().record(1).put("a", 1).build());
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(samples.viewSql(connection, List.of("a", "not_yet")))) {
            assertTrue(rows.next());
            assertEquals("not_yet", rows.getMetaData().getColumnLabel(3));
            rows.getDouble(3);
            assertTrue(rows.wasNull());
        }
    }

    @Test
    public void namesWithAwkwardCharactersSurviveAsColumnHeaders() throws SQLException {
        samples.append(connection, SparseBatch.builder().record(1)
                .put("temp (°C)", 21.5).put("a \"quoted\" name", 2).build());
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     samples.viewSql(connection, List.of("temp (°C)", "a \"quoted\" name")))) {
            assertTrue(rows.next());
            assertEquals("temp (°C)", rows.getMetaData().getColumnLabel(2));
            assertEquals(21.5, rows.getDouble("temp (°C)"), 0);
            assertEquals(2.0, rows.getDouble("a \"quoted\" name"), 0);
        }
    }

    @Test
    public void aMaterializedWideTableMatchesTheView() throws SQLException {
        samples.append(connection, batchOf(randomData(400, 100, 10, 6)));
        List<String> hot = List.of("p0", "p1", "p2", "p5");
        Materialization wide = samples.materializeWide(connection, "sample_hot", hot);

        String view = samples.viewSql(connection, hot);
        assertEquals("the materialization holds exactly the view's rows", 0,
                count("SELECT count(*) FROM ((" + view + ") EXCEPT ALL (SELECT * FROM sample_hot))"));
        assertEquals(count("SELECT count(*) FROM (" + view + ")"), wide.rowCount(connection));
    }

    // ---------- Atomicity ----------

    /**
     * A write that fails part-way must leave nothing behind - not the new dictionary entries it
     * added, and not the values it had already appended for other records.
     */
    @Test
    public void aFailedWriteLeavesNoTrace() throws SQLException {
        // A primary key the Appender enforces - it ignores CHECK constraints - so that a write can
        // be made to fail after it has extended the dictionary and appended some values.
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE sample_point");
            statement.execute("CREATE TABLE sample_point (record_id BIGINT NOT NULL, attr INTEGER NOT NULL,"
                    + " value DOUBLE, PRIMARY KEY (record_id, attr))");
        }
        samples.append(connection, SparseBatch.builder().record(1).put("a", 1).build());

        try {
            samples.append(connection, SparseBatch.builder()
                    .record(5).put("brand_new", 5).put("also_new", 6)   // appended first
                    .record(1).put("a", 2)                              // then this collides
                    .build());
            fail("the write should have failed on the primary key");
        }
        catch (RuntimeException expected) {
            // fine
        }
        assertEquals("the new dictionary entries must be rolled back", 1, samples.attributeCount(connection));
        assertTrue("the values already appended for record 5 must be rolled back",
                samples.read(connection, 5).isEmpty());
        assertEquals(Map.of("a", 1.0), samples.read(connection, 1).toMap());

        // And the connection must still work, transactionally, afterwards.
        samples.append(connection, SparseBatch.builder().record(6).put("a", 6).build());
        assertEquals(Map.of("a", 6.0), samples.read(connection, 6).toMap());
        assertTrue("left in auto-commit mode", connection.getAutoCommit());
    }

    @Test
    public void insideTheCallersTransactionTheCallerDecides() throws SQLException {
        connection.setAutoCommit(false);
        samples.append(connection, SparseBatch.builder().record(1).put("a", 1).build());
        assertFalse("a write inside the caller's transaction must not commit it", connection.getAutoCommit());
        connection.rollback();
        connection.setAutoCommit(true);
        assertEquals("rolled back with the caller's transaction", 0, samples.valueCount(connection));
        assertEquals(0, samples.attributeCount(connection));
    }

    // ---------- optimize ----------

    @Test
    public void optimizeKeepsEveryValue() {
        Map<Long, Map<String, Double>> data = randomData(1_000, 300, 20, 7);
        samples.append(connection, batchOf(data));
        samples.optimize(connection);

        assertEquals(1_000 * 20, samples.valueCount(connection));
        for (long id : new long[]{0, 1, 500, 999}) {
            assertEquals(data.get(id), new TreeMap<>(samples.read(connection, id).toMap()));
        }
    }

    /**
     * The reason optimize deletes in place rather than swapping in a sorted copy: with a swap, a
     * write committing mid-optimize vanishes without an error. Writers here run through their own
     * SparseTable instances, so the instance lock does not keep them apart from the optimize.
     */
    @Test(timeout = 120_000)
    public void optimizeNeverLosesAConcurrentWrite() throws Exception {
        samples.append(connection, batchOf(randomData(2_000, 200, 20, 8)));
        long before = samples.valueCount(connection);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong written = new AtomicLong();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService writers = Executors.newFixedThreadPool(2);
        CountDownLatch running = new CountDownLatch(2);
        for (int w = 0; w < 2; w++) {
            long base = 1_000_000L * (w + 1);
            writers.submit(() -> {
                SparseTable other = SparseTable.named("sample");
                try (Connection mine = connection.duplicate()) {
                    running.countDown();
                    for (long id = base; !stop.get(); id++) {
                        try {
                            other.append(mine, SparseBatch.builder().record(id).put("late", id).build());
                            written.incrementAndGet();
                        }
                        catch (RuntimeException conflict) {
                            // A write that conflicts with an optimize fails loudly; it is not lost
                            // silently, which is the property under test. It is not counted.
                        }
                    }
                }
                catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        running.await();
        for (int i = 0; i < 5; i++) {
            samples.optimize(connection);
        }
        stop.set(true);
        writers.shutdown();
        assertTrue(writers.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals("" + failures, 0, failures.size());
        assertTrue("the writers should have written something during the optimizes", written.get() > 0);
        assertEquals("every write that reported success must still be there",
                before + written.get(), samples.valueCount(connection));
    }

    /**
     * A replace deletes rows, and so does optimize: through two instances they conflict. Whatever
     * the outcome of each optimize, every replace that reported success must be exactly what its
     * record holds afterwards - a conflict may fail an operation, never half-apply one.
     */
    @Test(timeout = 120_000)
    public void optimizeRacingReplacesThroughAnotherInstanceLosesNothing() throws Exception {
        assertEveryReplaceSurvivesOptimize(SparseTable.named("sample"), false);
    }

    /** Through the same instance the lock orders them, so nothing even conflicts. */
    @Test(timeout = 120_000)
    public void optimizeThroughTheWritersInstanceNeverConflicts() throws Exception {
        assertEveryReplaceSurvivesOptimize(samples, true);
    }

    private void assertEveryReplaceSurvivesOptimize(SparseTable writerInstance, boolean optimizeMustSucceed)
            throws Exception {
        int records = 1_000;
        samples.append(connection, batchOf(randomData(records, 200, 20, 9)));
        double[] expected = new double[records];
        java.util.Arrays.fill(expected, Double.NaN);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong replaced = new AtomicLong();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        CountDownLatch running = new CountDownLatch(1);
        writer.submit(() -> {
            try (Connection mine = connection.duplicate()) {
                running.countDown();
                for (int n = 0; !stop.get(); n++) {
                    int record = n % records;
                    try {
                        writerInstance.replace(mine, SparseBatch.builder()
                                .record(record).put("a", n).put("b", -n).build());
                        expected[record] = n;
                        replaced.incrementAndGet();
                    }
                    catch (IllegalStateException conflict) {
                        // Failed loudly and rolled back; the record keeps its previous value.
                    }
                    Thread.sleep(1);
                }
            }
            catch (Throwable e) {
                failures.add(e);
            }
            return null;
        });
        running.await();
        int optimized = 0;
        try {
            for (int i = 0; i < 5; i++) {
                try {
                    samples.optimize(connection);
                    optimized++;
                }
                catch (IllegalStateException conflict) {
                    if (optimizeMustSucceed) {
                        throw conflict;
                    }
                }
            }
        }
        finally {
            stop.set(true);
            writer.shutdown();
            assertTrue(writer.awaitTermination(60, TimeUnit.SECONDS));
        }

        assertEquals("" + failures, 0, failures.size());
        assertTrue("the writer should have replaced something during the optimizes", replaced.get() > 0);
        if (optimizeMustSucceed) {
            assertEquals(5, optimized);
        }
        for (int record = 0; record < records; record++) {
            if (!Double.isNaN(expected[record])) {
                SparseRecord stored = samples.read(connection, record);
                assertEquals("record " + record + " size", 2, stored.size());
                assertEquals("record " + record, expected[record], stored.get("a").getAsDouble(), 0);
                assertEquals("record " + record, -expected[record], stored.get("b").getAsDouble(), 0);
            }
        }
    }

    // ---------- Concurrency ----------

    /**
     * Writers introducing the same new names at the same moment, through separate instances: the
     * dictionary must end with each name exactly once, and no value may point at an id the
     * dictionary does not have.
     */
    @Test(timeout = 120_000)
    public void concurrentWritersAgreeOnTheDictionary() throws Exception {
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Map<Integer, Integer> recordsWritten = new HashMap<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            pool.submit(() -> {
                SparseTable mine = SparseTable.named("sample");
                try (Connection own = connection.duplicate()) {
                    go.await();
                    for (int batch = 0; batch < 10; batch++) {
                        SparseBatch.Builder builder = SparseBatch.builder();
                        long id = writer * 100_000L + batch;
                        builder.record(id);
                        for (int p = 0; p < 50; p++) {
                            builder.put("shared_" + (batch * 10 + p), p);   // every writer adds these
                        }
                        mine.append(own, builder.build());
                    }
                    synchronized (recordsWritten) {
                        recordsWritten.put(writer, 10);
                    }
                }
                catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(90, TimeUnit.SECONDS));

        if (!failures.isEmpty()) {
            StringBuilder why = new StringBuilder();
            for (Throwable t = failures.get(0); t != null; t = t.getCause()) {
                why.append("\n  ").append(t.getClass().getSimpleName()).append(": ")
                        .append(String.valueOf(t.getMessage()).lines().findFirst().orElse(""));
            }
            fail(failures.size() + " writers failed:" + why);
        }
        assertEquals("each name exactly once", 140, samples.attributeCount(connection));
        assertEquals("no duplicate names", 0,
                count("SELECT count(*) FROM (SELECT name FROM sample_attribute GROUP BY name HAVING count(*) > 1)"));
        assertEquals("no value may point at an id the dictionary lacks", 0,
                count("SELECT count(*) FROM sample_point p ANTI JOIN sample_attribute a ON a.id = p.attr"));
        assertEquals(writers * 10 * 50, samples.valueCount(connection));
    }

    @Test
    public void tableNamesAreValidated() {
        try {
            SparseTable.named("drop table x; --");
            fail("an unsafe table name should be rejected");
        }
        catch (IllegalArgumentException expected) {
            assertNull(null);
        }
    }
}
