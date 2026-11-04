package io.quackjvm.agent.session;

import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.guard.QueryTimedOut;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A query's whole result, read a window at a time - issue #15. */
public class ResultWindowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private QuerySession session;

    @Before
    public void open() throws Exception {
        session = QuerySession.open(Hardening.options().tempFolder(folder.newFolder("temp").getPath()),
                QueryPolicy.readOnly().timeout(Duration.ofMinutes(1)));
        session.register("big", "SELECT x AS id, (x * 7919) % 1000003 AS v, 'text ' || x AS s FROM range(2000000) r(x)");
        session.register("small", "SELECT * FROM (VALUES (1, 'b', 10), (2, 'a', NULL), (3, 'b', 30), (4, NULL, 20),"
                + " (5, 'a', 10)) t(id, label, n)");
    }

    @After
    public void close() {
        session.close();
    }

    private static List<Long> ids(List<Object[]> rows) {
        List<Long> ids = new ArrayList<>();
        for (Object[] row : rows) {
            ids.add(((Number) row[0]).longValue());
        }
        return ids;
    }

    private static double best(Runnable work, int times) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < times; i++) {
            long started = System.nanoTime();
            work.run();
            best = Math.min(best, (System.nanoTime() - started) / 1e6);
        }
        return best;
    }

    @Test
    public void twoMillionRowsAWindowAnywhereInMillisecondsAndAResortUnderASecond() {
        try (ResultWindow window = session.window("SELECT * FROM big ORDER BY v, id", null)) {
            assertEquals(2_000_000, window.rowCount());
            assertEquals(List.of("id", "v", "s"), window.columns());
            assertEquals(List.of("BIGINT", "BIGINT", "VARCHAR"), window.columnTypes());

            List<Object[]> expected = session.run("SELECT * FROM big ORDER BY v, id LIMIT 100 OFFSET 1500000")
                    .getRows();
            List<Object[]> got = window.rows(1_500_000, 100);
            assertEquals(100, got.size());
            for (int i = 0; i < 100; i++) {
                assertArrayEquals(expected.get(i), got.get(i));
            }
            double read = best(() -> window.rows(1_500_000, 100), 20);

            long started = System.nanoTime();
            window.sort("s", false);
            double sort = (System.nanoTime() - started) / 1e6;
            List<Object[]> sorted = window.rows(0, 3);
            assertEquals("text 999999", sorted.get(0)[2]);
            assertEquals("text 999998", sorted.get(1)[2]);
            double sortedRead = best(() -> window.rows(1_500_000, 100), 20);

            System.out.printf("window read %.2f ms, sorted read %.2f ms, re-sort %.0f ms%n", read, sortedRead, sort);
            assertTrue("read " + read + " ms", read < 20);
            assertTrue("sorted read " + sortedRead + " ms", sortedRead < 20);
            assertTrue("sort " + sort + " ms", sort < 1000);
        }
    }

    @Test
    public void aSortKeepsTheQuerysOrderForTiesAndPutsNullsLast() {
        try (ResultWindow window = session.window("SELECT * FROM small ORDER BY id", null)) {
            window.sort("label", true);
            assertEquals(List.of(2L, 5L, 1L, 3L, 4L), ids(window.rows(0, 10)));
            window.sort("LABEL", false);
            assertEquals("label", window.sortedBy());
            assertFalse(window.isAscending());
            assertEquals(List.of(1L, 3L, 2L, 5L, 4L), ids(window.rows(0, 10)));
            window.sort("n", true);
            assertEquals(List.of(1L, 5L, 4L, 3L, 2L), ids(window.rows(0, 10)));
            window.unsort();
            assertNull(window.sortedBy());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), ids(window.rows(0, 10)));
        }
    }

    @Test
    public void theQuerysOwnOrderIsKeptAndWindowsEndAtTheEnd() {
        try (ResultWindow window = session.window("SELECT * FROM small ORDER BY id DESC", null)) {
            assertEquals(List.of(5L, 4L, 3L, 2L, 1L), ids(window.rows(0, 5)));
            assertEquals(List.of(2L, 1L), ids(window.rows(3, 50)));
            assertTrue(window.rows(5, 10).isEmpty());
            assertTrue(window.rows(1000, 10).isEmpty());
            assertTrue(window.rows(0, 0).isEmpty());
        }
    }

    @Test
    public void noRowCapButTheChecksStillApply() {
        try (ResultWindow window = session.window("SELECT * FROM big", null,
                QueryPolicy.readOnly().maxRows(10))) {
            assertEquals("the row cap is for what is returned, and nothing is", 2_000_000, window.rowCount());
        }
        for (String sql : new String[] {"DROP TABLE big", "CREATE TABLE x AS SELECT 1", "SELECT 1; SELECT 2",
                "EXPLAIN SELECT * FROM big", "COPY big TO 'x.csv'"}) {
            try {
                session.window(sql, null);
                fail(sql);
            }
            catch (QueryRejected expected) {
                assertFalse(sql, expected instanceof QueryFailed);
            }
        }
    }

    @Test
    public void aFailureSaysWhichOfTheCallersLinesItIsOn() {
        try {
            session.window("SELECT id,\n       nope\nFROM small", null);
            fail();
        }
        catch (QueryFailed failed) {
            assertTrue(failed.getMessage(), failed.getMessage().contains("LINE 2:        nope"));
            assertEquals("Binder", failed.getErrorType());
        }
        assertEquals("nothing left behind", 0, storedTables());
    }

    @Test
    public void aStopAlreadyTrueOrTurningTrueAndATimeoutLeaveNothingBehind() {
        String slow = "SELECT count(*) AS c FROM range(100000000000) t(x) WHERE x % 7 = 3";
        try {
            session.window(slow, CancelSignal.from(() -> true));
            fail();
        }
        catch (QueryCancelled expected) {
            // before it ran
        }
        AtomicBoolean stop = new AtomicBoolean();
        new Thread(() -> {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException ignored) {
                return;
            }
            stop.set(true);
        }).start();
        try {
            session.window(slow, CancelSignal.from(stop::get));
            fail();
        }
        catch (QueryCancelled expected) {
            // stopped while running
        }
        try {
            session.window(slow, null, QueryPolicy.readOnly().timeout(Duration.ofMillis(200)));
            fail();
        }
        catch (QueryTimedOut expected) {
            // out of time
        }
        assertEquals(0, storedTables());
    }

    @Test
    public void storedResultsAreNotAmongTheSessionsTables() {
        try (ResultWindow window = session.window("SELECT * FROM small", null)) {
            assertEquals(List.of("big", "small"), session.names());
            assertTrue(session.examples("small").stream().noneMatch(e -> e.sql().contains("quackjvm")));
            assertEquals(1, storedTables());
            assertEquals(5, window.rowCount());
        }
        assertEquals("closing drops it", 0, storedTables());
    }

    @Test
    public void aWindowSaysSoOnceTheSessionHasOpenedItsDatabaseAgain() throws Exception {
        ResultWindow window = session.window("SELECT * FROM small", null);
        assertTrue(window.isReadable());
        session.allowFolders(List.of(folder.newFolder("more").toPath()));
        assertFalse(window.isReadable());
        try {
            window.rows(0, 1);
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Make the window again"));
        }
        window.close();
        try (ResultWindow again = session.window("SELECT * FROM small", null)) {
            assertEquals(5, again.rows(0, 10).size());
        }
    }

    @Test
    public void aClosedWindowOrSessionCannotBeRead() {
        ResultWindow window = session.window("SELECT * FROM small", null);
        window.close();
        window.close();
        try {
            window.rows(0, 1);
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
        ResultWindow open = session.window("SELECT * FROM small", null);
        session.close();
        assertFalse(open.isReadable());
        try {
            open.rows(0, 1);
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
        open.close();
    }

    @Test
    public void aPivotAllowedUnverifiedCanBeStored() {
        try {
            session.window("PIVOT small ON label USING sum(n)", null);
            fail();
        }
        catch (QueryRejected expected) {
            assertTrue(expected.getMessage().contains("PIVOT"));
        }
        try (ResultWindow window = session.window("PIVOT small ON label USING sum(n) GROUP BY id", null,
                QueryPolicy.readOnly().allowUnverified(QueryPolicy.PIVOT))) {
            assertEquals(5, window.rowCount());
        }
    }

    @Test
    public void manyThreadsReadWindowsAtOnce() throws Exception {
        ResultWindow window = session.window("SELECT * FROM big ORDER BY id", null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> reads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int start = t * 100_000;
                reads.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        long first = start + i * 97L;
                        List<Long> got = ids(window.rows(first, 20));
                        List<Long> expected = new ArrayList<>();
                        for (long id = first; id < first + 20; id++) {
                            expected.add(id);
                        }
                        if (!expected.equals(got)) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> read : reads) {
                assertTrue(read.get(1, TimeUnit.MINUTES));
            }
        }
        finally {
            pool.shutdownNow();
            window.close();
        }
    }

    private long storedTables() {
        return ((Number) session.run("SELECT count(*) FROM duckdb_tables() WHERE database_name = 'quackjvm_windows'")
                .getRows().get(0)[0]).longValue();
    }
}
