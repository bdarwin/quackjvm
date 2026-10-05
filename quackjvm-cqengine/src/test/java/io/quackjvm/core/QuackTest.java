package io.quackjvm.core;

import io.quackjvm.core.guard.Hardening;
import io.quackjvm.core.guard.QueryPolicy;
import io.quackjvm.core.guard.QueryTimedOut;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.live.LiveTable;
import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuackTest {

    private static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-09-30T10:00:00Z");

    public record Car(int carId, String make, double price) {
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private QuackMetrics metrics;
    private Quack db;
    private MeasureTable measure;

    @Before
    public void open() throws Exception {
        metrics = new QuackMetrics();
        db = Quack.builder(folder.getRoot().toPath().resolve("live.duckdb").toString())
                .history(folder.getRoot().toPath().resolve("history.duckdb").toString())
                .metrics(metrics)
                .connections(2)
                .open();
        db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, count INTEGER)");
        db.execute("INSERT INTO item VALUES (1, 10), (2, 20)");
        measure = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();
        db.create(measure);
    }

    @After
    public void close() {
        db.close();
    }

    private MeasureBatch batch(long record, double at5y, double at10y) {
        return measure.batch().record(record).put(at5y, "x", "5y").put(at10y, "x", "10y").build();
    }

    private double inUse() {
        return metrics.snapshot().gauge(QuackMetrics.CONNECTIONS_IN_USE);
    }

    private List<String> state() {
        return db.query("SELECT v.record_id || ' ' || k.point || '=' || v.value FROM " + measure.getValueTable()
                + " v JOIN " + measure.getKeyTable() + " k ON k.id = v.key_id ORDER BY 1").list(String.class);
    }

    // ---------- reading and writing without a connection ----------

    @Test
    public void aQueryAnswersWhatTheLowerLevelAnswers() throws Exception {
        assertEquals(Long.valueOf(30), db.query("SELECT sum(count) FROM item").scalar(Long.class));
        assertEquals(List.of(10, 20), db.query("SELECT count FROM item WHERE id IN (?, ?) ORDER BY 1", 1, 2)
                .list(Integer.class));
        try (Connection connection = db.connection()) {
            assertEquals(Long.valueOf(30), Rows.of(connection, "SELECT sum(count) FROM item").scalar(Long.class));
        }
    }

    @Test
    public void everyConnectionIsGivenBackOnFailureAsWellAsSuccess() {
        for (int i = 0; i < 50; i++) {
            db.query("SELECT count(*) FROM item").scalar(Long.class);
            try {
                db.query("SELECT * FROM no_such_table").list(Object[].class);
                fail("the query should have failed");
            }
            catch (RuntimeException expected) {
                // the connection it borrowed still has to come back
            }
            try {
                db.execute("INSERT INTO item VALUES (1, 0)");
                fail("a duplicate key should have failed");
            }
            catch (RuntimeException expected) {
                // and so does this one
            }
        }
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void manyThreadsShareOneDatabase() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(8);
        List<Future<Long>> sums = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int id = i;
            sums.add(threads.submit(() -> {
                db.execute("INSERT INTO item VALUES (?, ?)", 100 + id, 1);
                return db.query("SELECT count(*) FROM item WHERE id = ?", 100 + id).scalar(Long.class);
            }));
        }
        for (Future<Long> sum : sums) {
            assertEquals(Long.valueOf(1), sum.get());
        }
        threads.shutdown();
        assertEquals(Long.valueOf(66), db.query("SELECT count(*) FROM item").scalar(Long.class));
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void aSlowQueryDoesNotHoldUpAQuickOne() throws Exception {
        // One connection runs one query at a time; through the entry point each runs on its own.
        String slow = "SELECT count(*) FROM range(15000) a, range(15000) b WHERE (a.range * 7 + b.range) % 1000003 = 1";
        ExecutorService other = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();
        Future<Long> running = other.submit(() -> db.query(slow).scalar(Long.class));
        Thread.sleep(50);
        long quick = System.nanoTime();
        assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM item").scalar(Long.class));
        long quickMs = (System.nanoTime() - quick) / 1_000_000;
        running.get();
        long slowMs = (System.nanoTime() - started) / 1_000_000;
        other.shutdown();
        assertTrue("the slow query must outlast the quick one by far to prove anything; took " + slowMs + " ms",
                slowMs > 300);
        assertTrue("the quick query waited " + quickMs + " ms behind the slow one", quickMs < slowMs / 3);
    }

    @Test
    public void aGuardedQueryTimesOutAndGivesItsConnectionBack() {
        try {
            db.guarded(QueryPolicy.readOnly().timeout(Duration.ofMillis(100)))
                    .run("SELECT count(*) FROM range(15000) a, range(15000) b WHERE (a.range * 7 + b.range) % 1000003 = 1");
            fail("the guarded query should have timed out");
        }
        catch (QueryTimedOut expected) {
            // interrupted
        }
        assertEquals(0.0, inUse(), 0.0);
        assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM item").scalar(Long.class));
    }

    // ---------- transactions ----------

    @Test
    public void liveStateAndARefreshCommitTogether() {
        db.inTransaction(tx -> {
            tx.execute("UPDATE item SET count = count + ? WHERE id = ?", 5, 1);
            assertTrue(tx.commit(MeasureRefresh.at(NINE, "r1").full(measure, batch(3, 10, 20))));
            // What the transaction wrote, it sees.
            assertEquals(Integer.valueOf(15), tx.query("SELECT count FROM item WHERE id = 1").scalar(Integer.class));
        });
        assertEquals(Integer.valueOf(15), db.query("SELECT count FROM item WHERE id = 1").scalar(Integer.class));
        assertEquals(List.of("3 10y=20.0", "3 5y=10.0"), state());
    }

    @Test
    public void aThrowRollsBackLiveStateAndTheRefreshAlike() {
        try {
            db.inTransaction(tx -> {
                tx.execute("UPDATE item SET count = count + ? WHERE id = ?", 5, 1);
                tx.commit(MeasureRefresh.at(NINE, "r1").full(measure, batch(3, 10, 20)));
                throw new IllegalStateException("the work changed its mind");
            });
            fail("the failure should have come through");
        }
        catch (IllegalStateException expected) {
            assertEquals("the work changed its mind", expected.getMessage());
        }
        assertEquals(Integer.valueOf(10), db.query("SELECT count FROM item WHERE id = 1").scalar(Integer.class));
        assertEquals(List.of(), state());

        // The id was never committed, so it can be committed now - and the key cache holds nothing stale.
        assertTrue(db.commit(MeasureRefresh.at(NINE, "r1").full(measure, batch(3, 11, 21))));
        assertEquals(List.of("3 10y=21.0", "3 5y=11.0"), state());
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void aTransactionReturnsWhatItsWorkReturned() {
        long count = db.fromTransaction(tx -> {
            tx.execute("INSERT INTO item VALUES (3, 30)");
            return tx.query("SELECT count(*) FROM item").scalar(Long.class);
        });
        assertEquals(3, count);
    }

    @Test
    public void aTransactionCannotBeEndedFromInside() {
        db.inTransaction(tx -> {
            for (String attempt : List.of("commit", "rollback", "autocommit")) {
                try {
                    Connection connection = tx.connection();
                    switch (attempt) {
                        case "commit" -> connection.commit();
                        case "rollback" -> connection.rollback();
                        default -> connection.setAutoCommit(true);
                    }
                    fail(attempt + " ended the transaction from inside");
                }
                catch (UnsupportedOperationException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("entry point")
                            || expected.getMessage().contains("auto-commit"));
                }
            }
            tx.connection().close();   // does nothing
            tx.execute("INSERT INTO item VALUES (4, 40)");
        });
        assertEquals(Long.valueOf(3), db.query("SELECT count(*) FROM item").scalar(Long.class));
    }

    @Test
    public void aTransactionCannotBeUsedOnceItHasEnded() {
        Tx[] kept = new Tx[1];
        Connection[] connection = new Connection[1];
        db.inTransaction(tx -> {
            kept[0] = tx;
            connection[0] = tx.connection();
        });
        assertFalse(kept[0].isOpen());
        try {
            kept[0].execute("INSERT INTO item VALUES (5, 50)");
            fail("an ended transaction ran a statement");
        }
        catch (IllegalStateException expected) {
            // as it should
        }
        try {
            connection[0].createStatement();
            fail("an ended transaction's connection ran a statement");
        }
        catch (Exception expected) {
            // as it should
        }
    }

    @Test
    public void whatRunsItsOwnTransactionsAgainstTwoDatabasesIsNotOfferedInsideOne() {
        for (Method method : Tx.class.getMethods()) {
            assertFalse("Tx offers " + method.getName(),
                    List.of("ship", "export", "compact", "prune", "rebuild").contains(method.getName()));
        }
    }

    // ---------- measures and their history ----------

    @Test
    public void aMeasureIsWrittenShippedAndReadAsOfAPoint() {
        db.commit(MeasureRefresh.at(NINE, "r1").full(measure, batch(3, 10, 20)));
        db.commit(MeasureRefresh.at(TEN, "r2").increment(measure, batch(3, 12, 20)));
        assertEquals(2, db.ship(measure).entries());

        List<String> atNine = new ArrayList<>();
        db.query(db.asOf(measure, NINE).rows("point")).forEachRow((SqlRow row) ->
                atNine.add(row.get("point") + "=" + row.get("total")));
        List<String> atTen = new ArrayList<>();
        db.query(db.asOf(measure, TEN).rows("point")).forEachRow((SqlRow row) ->
                atTen.add(row.get("point") + "=" + row.get("total")));
        atNine.sort(null);
        atTen.sort(null);

        assertEquals(List.of("10y=20.0", "5y=10.0"), atNine);
        assertEquals(List.of("10y=20.0", "5y=12.0"), atTen);
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void aDatabaseOpenedWithoutAHistorySaysSo() {
        try (Quack plain = Quack.inMemory()) {
            plain.history();
            fail("a history appeared from nowhere");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("without a history"));
        }
    }

    @Test
    public void aHardenedDatabaseStillShipsToTheHistoryItWasOpenedWith() throws Exception {
        try (Quack hardened = Quack.builder(folder.getRoot().toPath().resolve("hard.duckdb").toString())
                .history(folder.getRoot().toPath().resolve("hard-history.duckdb").toString())
                .hardening(Hardening.DATABASE)
                .open()) {
            try (Connection connection = hardened.connection()) {
                assertTrue(Hardening.isHardened(connection));
            }
            hardened.create(measure);
            hardened.commit(MeasureRefresh.at(NINE, "r1").full(measure, batch(3, 10, 20)));
            assertEquals(1, hardened.ship(measure).entries());
        }
    }

    // ---------- what lives as long as the database ----------

    @Test
    public void liveTablesAndFunctionsAreSeenEverywhereAndGoWithTheDatabase() throws Exception {
        List<Car> cars = List.of(new Car(1, "a", 1.0), new Car(2, "b", 2.0));
        LiveTable table = db.live("car", cars, ColumnarLayout.ofRecord(Car.class));
        db.function("twice", Double.class, Double.class, x -> x * 2);

        assertEquals(Double.valueOf(6.0), db.query("SELECT sum(twice(price)) FROM car").scalar(Double.class));
        db.inTransaction(tx -> assertEquals(Long.valueOf(2),
                tx.query("SELECT count(*) FROM car").scalar(Long.class)));

        db.close();
        assertFalse(table.isOpen());
    }

    @Test
    public void twoDatabasesInOneJvmKeepTheirOwnLiveTables() {
        try (Quack other = Quack.inMemory()) {
            db.live("car", List.of(new Car(1, "a", 1.0)), ColumnarLayout.ofRecord(Car.class));
            other.live("car", List.of(new Car(1, "a", 1.0), new Car(2, "b", 2.0), new Car(3, "c", 3.0)),
                    ColumnarLayout.ofRecord(Car.class));
            assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM car").scalar(Long.class));
            assertEquals(Long.valueOf(3), other.query("SELECT count(*) FROM car").scalar(Long.class));
        }
        assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM car").scalar(Long.class));
    }

    @Test
    public void aSandboxIsADifferentDatabaseThatCannotReachFiles() {
        db.live("car", List.of(new Car(1, "a", 1.0)), ColumnarLayout.ofRecord(Car.class));
        try (Quack sandbox = Quack.sandbox()) {
            try {
                sandbox.query("SELECT count(*) FROM car").scalar(Long.class);
                fail("the sandbox saw the application's table");
            }
            catch (RuntimeException expected) {
                // a different database
            }
            try {
                sandbox.query("SELECT * FROM read_csv('" + folder.getRoot() + "/x.csv')").list(Object[].class);
                fail("the sandbox read a file");
            }
            catch (RuntimeException expected) {
                // hardened
            }
            assertEquals(0, sandbox.guarded(QueryPolicy.readOnly()).run("SELECT 1 WHERE false").getRowCount());
        }
    }

    // ---------- the way down, and the way out ----------

    @Test
    public void aBorrowedConnectionIsGivenBackWithItsLeftoversRolledBack() throws Exception {
        try (Connection connection = db.connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO item VALUES (6, 60)");
            }
            assertEquals(1.0, inUse(), 0.0);
        }
        assertEquals(0.0, inUse(), 0.0);
        assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM item").scalar(Long.class));
    }

    @Test
    public void wrappingAnExistingConnectionNeverTouchesIt() throws Exception {
        try (DuckDBConnection mine = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            mine.setAutoCommit(false);
            try (Statement statement = mine.createStatement()) {
                statement.execute("CREATE TABLE t AS SELECT 1 AS x");
            }
            mine.commit();
            try (Quack wrapped = Quack.on(mine)) {
                assertEquals(Long.valueOf(1), wrapped.query("SELECT count(*) FROM t").scalar(Long.class));
            }
            assertFalse(mine.isClosed());
            assertFalse(mine.getAutoCommit());
            try (Statement statement = mine.createStatement(); ResultSet rows = statement.executeQuery("SELECT x FROM t")) {
                assertTrue(rows.next());
            }
        }
    }

    @Test
    public void aClosedDatabaseRefusesWork() {
        db.close();
        for (Runnable call : List.<Runnable>of(
                () -> db.query("SELECT 1"),
                () -> db.execute("SELECT 1"),
                () -> db.catalog(),
                () -> db.inTransaction(tx -> { }),
                () -> db.connection())) {
            try {
                call.run();
                fail("a closed database did work");
            }
            catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("closed"));
            }
        }
        db.close();   // and closing twice is harmless
    }
}
