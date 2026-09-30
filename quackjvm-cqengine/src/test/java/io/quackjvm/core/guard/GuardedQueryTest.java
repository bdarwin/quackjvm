package io.quackjvm.core.guard;

import io.quackjvm.core.duckdb.Sql;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GuardedQueryTest {

    private DuckDBConnection connection;
    private GuardedQuery guard;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        Sql.execute(connection, "CREATE TABLE car (id INTEGER, make VARCHAR, price DOUBLE)");
        Sql.execute(connection, "INSERT INTO car SELECT i, 'm' || (i % 3), 100.0 + i FROM range(100) t(i)");
        guard = GuardedQuery.on(connection, QueryPolicy.readOnly());
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private void rejects(String sql, String expectedInMessage) {
        try {
            guard.run(sql);
            fail("expected to be refused: " + sql);
        }
        catch (QueryRejected rejected) {
            assertTrue("message was: " + rejected.getMessage(),
                    rejected.getMessage().toLowerCase().contains(expectedInMessage.toLowerCase()));
        }
    }

    private boolean tableExists(String name) {
        return Sql.queryLong(connection, "SELECT count(*) FROM duckdb_tables() WHERE table_name = '" + name + "'",
                List.of()) > 0;
    }

    // ---------- what it allows ----------

    @Test
    public void aSelectRunsAndComesBackWithItsColumns() {
        GuardedResult result = guard.run("SELECT make, count(*) AS n FROM car GROUP BY 1 ORDER BY 1");

        assertEquals(List.of("make", "n"), result.getColumns());
        assertEquals(List.of("VARCHAR", "BIGINT"), result.getColumnTypes());
        assertEquals(3, result.getRowCount());
        assertEquals("m0", result.getRows().get(0)[0]);
    }

    @Test
    public void theQueryFormsThatReadAreAllAllowed() {
        for (String sql : List.of(
                "SELECT 1",
                "WITH x AS (SELECT 1 AS a) SELECT * FROM x",
                "FROM car SELECT make LIMIT 1",
                "TABLE car",
                "VALUES (1), (2)",
                "DESCRIBE car",
                "SUMMARIZE car",
                "UNPIVOT car ON price",
                "SELECT * FROM car ORDER BY id LIMIT 3",
                "/* a comment */ SELECT 1",
                "SELECT 1 -- a trailing comment",
                "SELECT 1;")) {
            assertNull("should have been allowed: " + sql, guard.whyRejected(sql));
        }
    }

    @Test
    public void explainIsAllowedAndSaysWhatThePlanIs() {
        GuardedResult result = guard.run("EXPLAIN SELECT count(*) FROM car");

        assertTrue(result.getRowCount() > 0);
        assertTrue(result.toText().toUpperCase().contains("AGGREGATE"));
    }

    @Test
    public void explainAnalyzeIsAllowedBecauseWhatItRunsIsAQuery() {
        assertNull(guard.whyRejected("EXPLAIN ANALYZE SELECT count(*) FROM car"));
    }

    @Test
    public void explainOfSomethingThatWritesIsRefused() {
        rejects("EXPLAIN DELETE FROM car", "not a query");
        assertEquals(100, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
    }

    @Test
    public void explainCanBeTurnedOff() {
        GuardedQuery strict = GuardedQuery.on(connection, QueryPolicy.readOnly().allowExplain(false));
        assertNotNull(strict.whyRejected("EXPLAIN SELECT 1"));
    }

    // ---------- what it refuses ----------

    @Test
    public void statementsThatWriteAreRefused() {
        rejects("INSERT INTO car VALUES (1000, 'x', 1.0)", "not a query");
        rejects("UPDATE car SET price = 0", "not a query");
        rejects("DELETE FROM car", "not a query");
        rejects("CREATE TABLE other (a INTEGER)", "not a query");
        rejects("CREATE OR REPLACE TABLE car AS SELECT 1", "not a query");
        rejects("DROP TABLE car", "not a query");
        rejects("ALTER TABLE car ADD COLUMN x INTEGER", "not a query");
        rejects("TRUNCATE car", "not a query");

        assertEquals(100, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
        assertTrue(tableExists("car"));
    }

    @Test
    public void statementsThatReachOutsideTheDatabaseAreRefused() {
        rejects("ATTACH ':memory:' AS other", "not a query");
        rejects("COPY car TO '/tmp/guard-test.csv'", "not a query");
        rejects("INSTALL httpfs", "not a query");
        rejects("LOAD httpfs", "not a query");
        rejects("EXPORT DATABASE '/tmp/guard-test-db'", "not a query");
    }

    @Test
    public void statementsThatChangeSettingsAreRefused() {
        rejects("SET threads = 1", "not a query");
        rejects("PRAGMA database_list", "not a query");
        rejects("CALL pragma_version()", "not a query");
        rejects("BEGIN TRANSACTION", "not a query");
        rejects("PREPARE p AS SELECT 1", "not a query");
        rejects("CREATE MACRO m(x) AS x + 1", "not a query");
    }

    // ---------- attempts to get past it ----------

    @Test
    public void aSecondStatementAfterASelectIsRefusedAndNotRun() {
        rejects("SELECT 1; DROP TABLE car", "not a query");

        // The point of this test: with this driver, preparing that string would have dropped the
        // table before anyone looked at it.
        assertTrue("car was dropped - something prepared the statement", tableExists("car"));
    }

    @Test
    public void twoQueriesAreStillTwoStatements() {
        rejects("SELECT 1; SELECT 2", "one statement");
    }

    @Test
    public void aSemicolonInsideAStringIsNotASecondStatement() {
        GuardedResult result = guard.run("SELECT 'a;b' AS s, $$c;d$$ AS t");

        assertEquals("a;b", result.getRows().get(0)[0]);
        assertEquals("c;d", result.getRows().get(0)[1]);
    }

    @Test
    public void commentsCannotHideASecondStatement() {
        rejects("SELECT 1 /* hidden */; DROP TABLE car", "not a query");
        rejects("SELECT 1 -- hidden\n; DROP TABLE car", "not a query");
        assertTrue(tableExists("car"));
    }

    @Test
    public void aCteCannotSmuggleAWrite() {
        rejects("WITH x AS (INSERT INTO car VALUES (1000, 'x', 1.0) RETURNING *) SELECT * FROM x", "not a query");
        rejects("WITH x AS (DELETE FROM car RETURNING *) SELECT count(*) FROM x", "not a query");

        assertEquals(100, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
    }

    @Test
    public void breakingOutOfTheRowCapWrapperIsRefused() {
        // The row cap wraps the statement; this tries to close the bracket and add another statement.
        rejects("SELECT 1) AS x; DROP TABLE car; SELECT * FROM (SELECT 1", "not a query");
        assertTrue(tableExists("car"));
    }

    @Test
    public void readingALocalFileIsAQueryButHardeningStopsIt() throws Exception {
        // Left as it is, a read of a file is a legitimate query as far as the parser is concerned -
        // which is exactly why hardening exists.
        Path file = Files.createTempFile("guard", ".csv");
        Files.writeString(file, "a\n1\n");
        try {
            assertNull(guard.whyRejected("SELECT * FROM read_csv('" + file + "')"));

            try (Connection hardened = DriverManager.getConnection("jdbc:duckdb:")) {
                GuardedQuery locked = GuardedQuery.on(hardened, QueryPolicy.readOnly().hardening(Hardening.DATABASE));
                try {
                    locked.run("SELECT * FROM read_csv('" + file + "')");
                    fail("expected file access to be impossible");
                }
                catch (QueryRejected refused) {
                    assertTrue(refused.getMessage(), refused.getMessage().contains("refused to run this"));
                }
            }
        }
        finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void hardeningAlsoTakesAwayTheEnvironmentAndExtensions() throws Exception {
        try (Connection hardened = DriverManager.getConnection("jdbc:duckdb:")) {
            GuardedQuery locked = GuardedQuery.on(hardened, QueryPolicy.readOnly().hardening(Hardening.DATABASE));
            assertTrue(Hardening.isHardened(hardened));
            for (String sql : List.of("SELECT getenv('HOME')", "SELECT * FROM read_csv('/etc/hosts')")) {
                try {
                    locked.run(sql);
                    fail("expected to be impossible: " + sql);
                }
                catch (QueryRejected expected) {
                    // DuckDB itself refuses it, which is the point: the check is not the only thing
                    // standing in the way.
                }
            }
        }
    }

    @Test
    public void aSandboxHasNoWayOutButStillWorksAsADatabase() throws Exception {
        try (Connection sandbox = Hardening.sandbox()) {
            assertTrue(Hardening.isHardened(sandbox));
            Sql.execute(sandbox, "CREATE TABLE t AS SELECT 1 AS a");
            assertEquals(1, Sql.queryLong(sandbox, "SELECT count(*) FROM t", List.of()));
            try {
                Sql.execute(sandbox, "SET enable_external_access = true");
                fail("a locked configuration should not be changeable");
            }
            catch (RuntimeException expected) {
                Throwable root = expected;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertTrue(root.getMessage(), root.getMessage().contains("locked"));
            }
        }
    }

    // ---------- pivots, which the parser will not vouch for ----------

    @Test
    public void aPivotIsRefusedUntilItIsAllowedOnPurpose() {
        rejects("PIVOT car ON make USING sum(price)", "will not serialize a pivot");

        GuardedQuery pivoting = GuardedQuery.on(connection,
                QueryPolicy.readOnly().allowUnverified(QueryPolicy.PIVOT));
        GuardedResult result = pivoting.run("PIVOT (SELECT make, price FROM car) ON make USING sum(price)");
        assertEquals(1, result.getRowCount());
        assertEquals(List.of("m0", "m1", "m2"), result.getColumns());
    }

    @Test
    public void anAllowedPivotStillCannotCarryASecondStatement() {
        GuardedQuery pivoting = GuardedQuery.on(connection,
                QueryPolicy.readOnly().allowUnverified(QueryPolicy.PIVOT));
        try {
            pivoting.run("PIVOT car ON make USING sum(price); DROP TABLE car");
            fail("expected two statements to be refused");
        }
        catch (QueryRejected expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("one statement"));
        }
        assertTrue(tableExists("car"));
    }

    @Test
    public void nothingElseCanBeAllowedUnverified() {
        try {
            QueryPolicy.readOnly().allowUnverified("INSERT");
            fail("expected INSERT to be impossible to allow");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a statement this can allow"));
        }
    }

    // ---------- the caps ----------

    @Test
    public void tooManyRowsIsRefusedRatherThanCutShort() {
        GuardedQuery small = GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(10));
        try {
            small.run("SELECT * FROM car");
            fail("expected the row cap to stop it");
        }
        catch (ResultTruncated expected) {
            assertEquals(10, expected.getLimit());
            assertTrue(expected.isRowLimit());
            assertTrue(expected.getMessage(), expected.getMessage().contains("LIMIT"));
        }
    }

    @Test
    public void exactlyTheRowCapIsFine() {
        GuardedQuery small = GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(10));

        assertEquals(10, small.run("SELECT * FROM car ORDER BY id LIMIT 10").getRowCount());
    }

    @Test
    public void tooManyBytesIsRefused() {
        GuardedQuery tiny = GuardedQuery.on(connection, QueryPolicy.readOnly().maxBytes(64));
        try {
            tiny.run("SELECT repeat('x', 1000) FROM car");
            fail("expected the byte cap to stop it");
        }
        catch (ResultTruncated expected) {
            assertEquals(64, expected.getLimit());
            assertTrue(!expected.isRowLimit());
        }
    }

    @Test
    public void theRowCapBecomesALimitRatherThanReadingEverything() {
        // A query over far more rows than the cap comes back quickly, because the LIMIT is inside
        // the plan: DuckDB never builds the rest.
        GuardedQuery small = GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(5)
                .timeout(Duration.ofSeconds(30)));
        long started = System.nanoTime();
        try {
            small.run("SELECT range FROM range(2000000000)");
            fail("expected the row cap to stop it");
        }
        catch (ResultTruncated expected) {
            long took = (System.nanoTime() - started) / 1_000_000;
            assertTrue("took " + took + " ms, so the limit was not pushed into the plan", took < 5_000);
        }
    }

    @Test
    public void aQueryThatRunsTooLongIsCancelled() {
        GuardedQuery impatient = GuardedQuery.on(connection,
                QueryPolicy.readOnly().timeout(Duration.ofMillis(250)).maxRows(1));
        long started = System.nanoTime();
        try {
            impatient.run("SELECT count(*) FROM range(60000000000) WHERE range % 7 = 0");
            fail("expected the timeout to stop it");
        }
        catch (QueryTimedOut expected) {
            long took = (System.nanoTime() - started) / 1_000_000;
            assertTrue("took " + took + " ms", took < 10_000);
            assertEquals(250, expected.getLimit().toMillis());
        }
        // And the connection is still usable afterwards.
        assertEquals(100, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
    }

    // ---------- what it costs ----------

    @Test
    public void theGuardCostsOneExtraQueryPerStatement() throws SQLException {
        String sql = "SELECT make, count(*) FROM car GROUP BY 1";
        for (int i = 0; i < 200; i++) {
            guard.run(sql);
            Sql.queryLong(connection, "SELECT count(*) FROM car", List.of());
        }
        long guarded = time(() -> guard.run(sql));
        long plain = time(() -> {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(sql);
                 java.sql.ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rows.getObject(1);
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        // Two checks and a wrapper, on a query of a hundred rows: a millisecond or so, not a
        // multiple. Printed rather than asserted tightly, because it is a measurement.
        System.out.printf("guard overhead: guarded %.2f ms, plain %.2f ms%n", guarded / 1e6, plain / 1e6);
        assertTrue("guarded " + guarded / 1e6 + " ms vs plain " + plain / 1e6 + " ms",
                guarded < Math.max(plain * 20, 20_000_000L));
    }

    private static long time(Runnable work) {
        long[] timings = new long[21];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            work.run();
            timings[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(timings);
        return timings[timings.length / 2];
    }

    @Test
    public void whyRejectedSaysWhyWithoutRunningAnything() {
        assertNull(guard.whyRejected("SELECT 1"));
        assertNotNull(guard.whyRejected("DROP TABLE car"));
        assertTrue(tableExists("car"));
    }

    @Test
    public void thereIsAlwaysSomethingToRun() {
        rejects("", "no statement");
        rejects("   ", "no statement");
        rejects("-- only a comment", "no statement to run");
    }

    @Test
    public void nonsenseIsRefusedWithTheParsersOwnWords() {
        try {
            guard.run("SELEKT 1");
            fail("expected a parser error");
        }
        catch (QueryRejected expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("syntax error"));
        }
    }

    @Test
    public void aResultReadsAsATableForWhoeverAskedForIt() {
        String text = guard.run("SELECT make, count(*) AS n FROM car GROUP BY 1 ORDER BY 1").toText();

        assertTrue(text, text.startsWith("make  n"));
        assertEquals(4, text.split("\n").length);
    }

    @Test
    public void theTemporaryFileForTheseTestsIsCleanedUp() throws IOException {
        // Guards against a test above leaving a file behind if it fails early.
        assertTrue(Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .noneMatch(path -> path.getFileName().toString().equals("guard-test.csv")));
    }
}
