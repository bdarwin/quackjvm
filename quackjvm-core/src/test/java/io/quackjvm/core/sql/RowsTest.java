package io.quackjvm.core.sql;

import io.quackjvm.core.Quack;
import io.quackjvm.core.metrics.QuackMetrics;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A query reads: it refuses what would change something, before it runs, and it can be stopped. */
public class RowsTest {

    /**
     * Long enough to be cancelled many times over: a cross join of 90 billion rows. The tests that
     * run it have a JUnit timeout, so that a timeout which does nothing fails rather than hangs.
     */
    private static final String SLOW = "SELECT sum(a.range * b.range) FROM range(300000) a, range(300000) b";

    public record Item(Long id, String name) {
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private QuackMetrics metrics;
    private Quack db;

    @Before
    public void open() {
        metrics = new QuackMetrics();
        db = Quack.builder(new File(folder.getRoot(), "rows.duckdb").getPath()).metrics(metrics).connections(2).open();
        db.execute("CREATE TABLE car AS SELECT range AS id, 'make' || range AS name FROM range(10)");
    }

    @After
    public void close() {
        db.close();
    }

    private String path(String name) {
        return new File(folder.getRoot(), name).getPath();
    }

    private long cars() {
        return db.query("SELECT count(*) FROM car WHERE id < 10").scalar(Long.class);
    }

    private double inUse() {
        return metrics.snapshot().gauge(QuackMetrics.CONNECTIONS_IN_USE);
    }

    /** Every way of reading a query, so that each is checked. */
    private static final List<Consumer<Rows>> READS = List.of(
            rows -> rows.scalar(Long.class),
            rows -> rows.scalarOptional(Long.class),
            rows -> rows.list(Long.class),
            rows -> rows.records(Item.class),
            rows -> rows.forEachRow(row -> { }),
            Rows::count,
            rows -> {
                try (Stream<SqlRow> stream = rows.stream()) {
                    stream.forEach(row -> { });
                }
            });

    private void assertRefusedAndNotRun(String sql) {
        for (int i = 0; i < READS.size(); i++) {
            try {
                READS.get(i).accept(db.query(sql));
                fail("read " + i + " ran " + sql);
            }
            catch (IllegalStateException expected) {
                assertTrue("read " + i + ": " + expected.getMessage(), expected.getMessage().startsWith("Not run:"));
            }
            assertEquals("read " + i + " of " + sql + " changed the table", 10, cars());
        }
        assertEquals(0.0, inUse(), 0.0);
    }

    // ---------- #1: a read refuses what is not one, before it runs ----------

    @Test
    public void aStatementThatReturnsNoRowsIsRefusedBeforeItRuns() {
        assertRefusedAndNotRun("DROP TABLE car");
        assertRefusedAndNotRun("UPDATE car SET id = id + 100");
        assertRefusedAndNotRun("DELETE FROM car");
        assertRefusedAndNotRun("CREATE TABLE other AS SELECT 1");

        assertRefusedAndNotRun("COPY car TO '" + path("car.csv") + "'");
        assertFalse(new File(path("car.csv")).exists());

        assertRefusedAndNotRun("ATTACH '" + path("other.duckdb") + "' AS other");
        assertFalse(new File(path("other.duckdb")).exists());

        assertRefusedAndNotRun("SET default_order = 'DESC'");
        assertEquals("ASCENDING", db.query("SELECT current_setting('default_order')").scalar(String.class));
    }

    @Test
    public void moreThanOneStatementIsRefusedWithoutAnyOfItRunning() {
        assertRefusedAndNotRun("SELECT 1; DROP TABLE car");
        // Preparing this would run the DROP, so it is refused before it is prepared.
        assertRefusedAndNotRun("DROP TABLE car; SELECT 1");
        assertRefusedAndNotRun("SELECT 1; SELECT 2");
    }

    @Test
    public void anExportIsRefusedBeforePreparingItWritesTheFiles() {
        assertRefusedAndNotRun("EXPORT DATABASE '" + path("dump") + "'");
        assertFalse(new File(path("dump")).exists());
    }

    @Test
    public void whatReturnsRowsStillRuns() {
        assertEquals(Long.valueOf(1), db.query("SELECT 1;").scalar(Long.class));
        assertEquals("a;b", db.query("SELECT 'a;b' -- a comment; with a semicolon\n").scalar(String.class));
        assertEquals(Long.valueOf(10), db.query("FROM car SELECT count(*)").scalar(Long.class));
        assertEquals(2, db.query("DESCRIBE car").list(String.class).size());
        assertEquals(2, db.query("SUMMARIZE car").list(String.class).size());
        assertEquals(2, db.query("PRAGMA table_info('car')").list(Integer.class).size());
        assertEquals(1, db.query("PIVOT (SELECT name FROM car) ON name IN ('make1', 'make2') USING count(*)").count());
        assertFalse(db.query("EXPLAIN SELECT * FROM car").list(String.class).isEmpty());
        assertFalse(db.query("CALL pragma_version()").list(String.class).isEmpty());
        assertEquals(List.of("car"), db.query("SHOW TABLES").list(String.class));
        assertEquals(Long.valueOf(3), db.query("SELECT id FROM car WHERE id = ?", 3).scalar(Long.class));
        // It returns rows, so it runs, and writes: a guard against mistakes, not against SQL you did not write.
        assertEquals(Long.valueOf(99), db.query("INSERT INTO car VALUES (99, 'x') RETURNING id").scalar(Long.class));
    }

    @Test
    public void theLowerLevelRefusesAsWell() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute("CREATE TABLE car AS SELECT range AS id FROM range(10)");
            for (String sql : List.of("DROP TABLE car", "SELECT 1; DROP TABLE car")) {
                try {
                    Rows.of(connection.duplicate(), sql).forEachRow(row -> { });
                    fail("ran " + sql);
                }
                catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().startsWith("Not run:"));
                }
                try {
                    SqlQuery.stream(connection.duplicate(), sql).close();
                    fail("ran " + sql);
                }
                catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().startsWith("Not run:"));
                }
            }
            assertEquals(Long.valueOf(10), Rows.of(connection.duplicate(), "SELECT count(*) FROM car").scalar(Long.class));
        }
    }

    @Test
    public void aRefusalClosesTheConnectionItWasGiven() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute("CREATE TABLE car AS SELECT range AS id FROM range(10)");
            for (int i = 0; i < READS.size(); i++) {
                java.sql.Connection given = connection.duplicate();
                try {
                    READS.get(i).accept(Rows.of(given, "DROP TABLE car"));
                    fail("read " + i + " ran it");
                }
                catch (IllegalStateException expected) {
                    // refused
                }
                assertTrue("read " + i + " left its connection open", given.isClosed());
            }
        }
    }

    /** The check costs nothing on a count that works: one prepare, as before. */
    @Test
    public void aCountThatWorksIsPreparedOnce() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute("CREATE TABLE car AS SELECT range AS id FROM range(10)");
            java.sql.Connection target = connection.duplicate();
            java.util.concurrent.atomic.AtomicInteger prepares = new java.util.concurrent.atomic.AtomicInteger();
            java.sql.Connection counted = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            prepares.incrementAndGet();
                        }
                        try {
                            return method.invoke(target, args);
                        }
                        catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });

            assertEquals(10, Rows.of(counted, "SELECT * FROM car WHERE id < ?", 10).count());
            assertEquals(1, prepares.get());
        }
    }

    /** A connection that counts what is prepared on it and what runs as a plain statement. */
    private static java.sql.Connection counting(java.sql.Connection target, java.util.List<String> prepared,
                                                java.util.List<String> plain) {
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                RowsTest.class.getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        prepared.add((String) args[0]);
                    }
                    if (method.getName().equals("createStatement")) {
                        plain.add("statement");
                    }
                    try {
                        return method.invoke(target, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * A SELECT with no parameters runs on a plain statement, not a prepared one: DuckDB runs some
     * queries far slower prepared (an ASOF join returning many rows with no ORDER BY), and a SELECT
     * needs no preparing to know it returns rows. One with parameters, and anything else, is
     * prepared - and checked - as before.
     */
    @Test
    public void aSelectWithNoParametersIsNotPrepared() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            connection.createStatement().execute("CREATE TABLE car AS SELECT range AS id, 'make' || range AS name FROM range(10)");
            // count() wraps the query in a count of its own, and is left as it was.
            List<Consumer<Rows>> reads = READS.stream()
                    .filter(read -> read != READS.get(5)).toList();
            for (String sql : List.of("SELECT id, name FROM car ORDER BY id", "FROM car SELECT id, name ORDER BY id",
                    "  -- leading comment\n select id, name from car order by id")) {
                for (int i = 0; i < reads.size(); i++) {
                    List<String> prepared = new java.util.ArrayList<>();
                    List<String> plain = new java.util.ArrayList<>();
                    reads.get(i).accept(Rows.of(counting(connection.duplicate(), prepared, plain), sql));
                    assertEquals("read " + i + " of " + sql + " prepared " + prepared, 0, prepared.size());
                    assertEquals("read " + i + " of " + sql, 1, plain.size());
                }
            }
            List<String> prepared = new java.util.ArrayList<>();
            List<String> plain = new java.util.ArrayList<>();
            assertEquals(List.of("make3"), Rows.of(counting(connection.duplicate(), prepared, plain),
                    "SELECT name FROM car WHERE id = ?", 3).list(String.class));
            assertEquals(List.of("make2", "make3"), Rows.of(counting(connection.duplicate(), prepared, plain),
                    "SELECT name FROM car WHERE id BETWEEN 2 AND 3 ORDER BY id").list(String.class));
            assertEquals(List.of(new Item(4L, "make4")), Rows.of(counting(connection.duplicate(), prepared, plain),
                    "SELECT id, name FROM car WHERE id = 4").records(Item.class));
            assertEquals(Long.valueOf(45), Rows.of(counting(connection.duplicate(), prepared, plain),
                    "SELECT sum(id) FROM car").scalar(Long.class));
            assertEquals(2, Rows.of(counting(connection.duplicate(), prepared, plain), "DESCRIBE car").list(String.class).size());
            assertEquals(List.of("SELECT name FROM car WHERE id = ?", "DESCRIBE car"), prepared);
            assertEquals(3, plain.size());
        }
    }

    /** Through the pool and a transaction alike, and with a timeout. */
    @Test
    public void anUnpreparedSelectReadsAsBefore() {
        assertEquals(List.of(0L, 1L, 2L), db.query("SELECT id FROM car WHERE id < 3 ORDER BY id").list(Long.class));
        assertEquals(List.of(new Item(1L, "make1")), db.query("SELECT id, name FROM car WHERE id = 1").records(Item.class));
        List<Object> seen = new java.util.ArrayList<>();
        db.query("SELECT name, id FROM car WHERE id = 7").timeout(Duration.ofSeconds(30)).forEachRow(row -> {
            seen.add(row.getString("name"));
            seen.add(row.getLong(2));
        });
        assertEquals(List.of("make7", 7L), seen);
        db.inTransaction(tx -> {
            tx.execute("INSERT INTO car VALUES (10, 'make10')");
            // The plain statement runs on the transaction's own connection, so it sees the insert.
            assertEquals(Long.valueOf(11), tx.query("SELECT count(*) FROM car").scalar(Long.class));
        });
        assertEquals(Long.valueOf(11), db.query("SELECT count(*) FROM car").scalar(Long.class));
        try (Stream<SqlRow> rows = db.query("SELECT id FROM car ORDER BY id DESC").stream()) {
            assertEquals(10L, rows.findFirst().orElseThrow().getLong(1));
        }
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void aCallersOwnExceptionComesOutAsItWasThrown() {
        IllegalArgumentException mine = new IllegalArgumentException("mine");
        try {
            db.query("SELECT id FROM car").forEachRow(row -> {
                throw mine;
            });
            fail("the consumer's exception was swallowed");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e == mine);
        }
        assertEquals(0.0, inUse(), 0.0);
    }

    // ---------- #2: a query can be stopped ----------

    private void assertTimesOutPromptly(Consumer<Rows> read) {
        long started = System.nanoTime();
        try {
            read.accept(db.query(SLOW).timeout(Duration.ofMillis(200)));
            fail("the query should have timed out");
        }
        catch (QueryTimedOut expected) {
            assertEquals(Duration.ofMillis(200), expected.getLimit());
            assertTrue(expected.getMessage(), expected.getMessage().contains("200 ms"));
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertTrue("took " + ms + " ms to give control back", ms < 5_000);
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test(timeout = 60_000)
    public void aQueryPastItsTimeoutIsCancelledAndSaysSo() {
        for (Consumer<Rows> read : READS) {
            assertTimesOutPromptly(read);
        }
    }

    @Test(timeout = 60_000)
    public void theConnectionAnswersNormallyAfterATimeout() {
        assertTimesOutPromptly(rows -> rows.scalar(Long.class));
        // Both pooled connections, more than once each.
        for (int i = 0; i < 6; i++) {
            assertEquals(Long.valueOf(10), db.query("SELECT count(*) FROM car").scalar(Long.class));
        }
        db.inTransaction(tx -> assertEquals(Long.valueOf(10), tx.query("SELECT count(*) FROM car").scalar(Long.class)));
    }

    @Test
    public void aQueryThatFinishesInTimeIsUntouched() {
        for (int i = 0; i < 200; i++) {
            assertEquals(Long.valueOf(45),
                    db.query("SELECT sum(id) FROM car").timeout(Duration.ofSeconds(30)).scalar(Long.class));
        }
    }

    @Test
    public void aTimeoutMustBePositive() {
        for (Duration bad : List.of(Duration.ZERO, Duration.ofMillis(-1))) {
            try {
                db.query("SELECT 1").timeout(bad);
                fail("accepted " + bad);
            }
            catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test(timeout = 60_000)
    public void theLowerLevelTimesOutAsWell() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            try {
                Rows.of(connection.duplicate(), SLOW).timeout(Duration.ofMillis(200)).scalar(Long.class);
                fail("the query should have timed out");
            }
            catch (QueryTimedOut expected) {
                // cancelled
            }
            assertEquals(Long.valueOf(1), Rows.of(connection.duplicate(), "SELECT 1").scalar(Long.class));
        }
    }
}
