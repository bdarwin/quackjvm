package io.quackjvm.agent.guard;

import io.quackjvm.agent.guard.QueryPolicy.Over;
import io.quackjvm.core.duckdb.Sql;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The first rows of a large result, said to be the first; and a query stopped from outside. */
public class GuardedQueryFirstRowsTest {

    /** Runs for minutes unless something stops it. */
    private static final String SLOW =
            "SELECT count(*) FROM range(100000) a, range(100000) b WHERE (a.range * 7 + b.range) % 1000003 = 1";

    private DuckDBConnection connection;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        Sql.execute(connection, "CREATE TABLE car (id INTEGER, make VARCHAR, price DOUBLE)");
        Sql.execute(connection, "INSERT INTO car SELECT i, 'm' || (i % 3), 100.0 + i FROM range(100) t(i)");
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private GuardedQuery firstRows(int rows) {
        return GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(rows).whenOver(Over.TRUNCATE));
    }

    // ---------- the first rows ----------

    @Test
    public void moreRowsThanAllowedGiveTheFirstOnesAndSaySo() {
        GuardedResult result = firstRows(50).run("SELECT * FROM range(1000) ORDER BY 1");

        assertEquals(50, result.getRowCount());
        assertTrue(result.isTruncated());
        assertEquals(0L, result.getRows().get(0)[0]);
        assertEquals(49L, result.getRows().get(49)[0]);
    }

    @Test
    public void exactlyAsManyRowsAsAllowedIsNotTruncated() {
        GuardedResult exactly = firstRows(50).run("SELECT * FROM range(50)");
        assertEquals(50, exactly.getRowCount());
        assertFalse(exactly.isTruncated());

        GuardedResult fewer = firstRows(50).run("SELECT * FROM range(10)");
        assertEquals(10, fewer.getRowCount());
        assertFalse(fewer.isTruncated());
    }

    @Test
    public void aStatementThatCannotBeWrappedIsCutShortAsItsRowsArrive() {
        // A pivot cannot be checked inside the LIMIT wrapper, so the cap is applied while reading.
        GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(10)
                .whenOver(Over.TRUNCATE).allowUnverified(QueryPolicy.PIVOT));

        GuardedResult result = guard.run("PIVOT car ON make USING count(*) GROUP BY id");

        assertEquals(10, result.getRowCount());
        assertTrue(result.isTruncated());
    }

    @Test
    public void theByteLimitCutsShortTheSameWay() {
        GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly().maxBytes(1_000).whenOver(Over.TRUNCATE));

        GuardedResult result = guard.run("SELECT repeat('x', 100) FROM range(100)");

        assertTrue(result.isTruncated());
        assertTrue(result.getRowCount() > 0 && result.getRowCount() < 100);
        assertTrue("held " + result.getBytes() + " bytes", result.getBytes() <= 1_000);
    }

    @Test
    public void refusingIsStillTheDefault() {
        try {
            GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(50)).run("SELECT * FROM range(1000)");
            fail("more rows than allowed came back");
        }
        catch (ResultTruncated expected) {
            assertTrue(expected.isRowLimit());
        }
        assertEquals(Over.REFUSE, QueryPolicy.readOnly().getWhenOver());
    }

    // ---------- stopping it from outside ----------

    @Test(timeout = 60_000)
    public void aCancelFromAnotherThreadStopsItPromptlyAndTheConnectionStaysUsable() throws Exception {
        GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly().timeout(Duration.ofMinutes(5)));
        CancelSignal stop = new CancelSignal();
        CompletableFuture<GuardedResult> running = CompletableFuture.supplyAsync(() -> guard.run(SLOW, stop));
        Thread.sleep(300);

        long cancelledAt = System.nanoTime();
        stop.cancel();
        try {
            running.get(30, TimeUnit.SECONDS);
            fail("the query finished although it was cancelled");
        }
        catch (ExecutionException e) {
            assertTrue("threw " + e.getCause(), e.getCause() instanceof QueryCancelled);
            assertFalse(e.getCause() instanceof QueryTimedOut);
        }
        long ms = (System.nanoTime() - cancelledAt) / 1_000_000;
        assertTrue("took " + ms + " ms to stop after the cancel", ms < 2_000);

        assertEquals(1, guard.run("SELECT 1").getRowCount());
    }

    @Test(timeout = 60_000)
    public void aSignalCancelledBeforehandStopsItBeforeItRuns() {
        CancelSignal stop = new CancelSignal();
        stop.cancel();
        long started = System.nanoTime();
        try {
            GuardedQuery.on(connection, QueryPolicy.readOnly()).run(SLOW, stop);
            fail("ran although cancelled");
        }
        catch (QueryCancelled expected) {
            // stopped before it started
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000);
    }

    @Test
    public void aCancelAfterItFinishedChangesNothing() {
        GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly());
        CancelSignal stop = new CancelSignal();
        assertEquals(3, guard.run("SELECT DISTINCT make FROM car", stop).getRowCount());

        stop.cancel();

        assertTrue(stop.isCancelled());
        assertEquals(1, guard.run("SELECT 1").getRowCount());
    }
}
