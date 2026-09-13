package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBConnection;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The statement cache and the {@code pending} flag are written for a single thread, and are safe
 * only because of one invariant: <b>a pooled connection is borrowed by at most one thread at a
 * time</b>. These tests hammer the pool from many threads and check that invariant directly,
 * including on the paths where it is least obvious - a pool too small to hold every connection, so
 * that {@code borrow()} duplicates a fresh one which {@code release()} then either pools or
 * discards.
 *
 * <p>Lives in {@code io.quackjvm.core.duckdb} so it can see the package-private
 * {@link StatementCache}; the class under test is in quackjvm-core.</p>
 */
public class ConnectionPoolStressTest {

    private static DuckDBConnection root() throws Exception {
        return (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
    }

    /** The invariant the statement cache depends on, across every pool size that matters. */
    @Test
    public void aPooledConnectionIsNeverHeldByTwoThreadsAtOnce() throws Exception {
        for (int maxIdle : new int[]{0, 1, 2, 8, 64}) {
            try (DuckDBConnection root = root()) {
                try (java.sql.Statement s = root.createStatement()) {
                    s.execute("CREATE TABLE t AS SELECT i AS id FROM range(1000) tbl(i)");
                }
                ConnectionPool pool = new ConnectionPool(root, maxIdle);
                Map<Connection, String> holders = new ConcurrentHashMap<>();
                List<String> violations = new CopyOnWriteArrayList<>();
                int threads = 16;
                int iterations = 300;
                CountDownLatch go = new CountDownLatch(1);
                Thread[] workers = new Thread[threads];
                for (int t = 0; t < threads; t++) {
                    workers[t] = new Thread(() -> {
                        String me = Thread.currentThread().getName();
                        try {
                            go.await();
                        }
                        catch (InterruptedException e) {
                            return;
                        }
                        for (int i = 0; i < iterations; i++) {
                            Connection managed = Connections.managed(pool.borrow(), pool, null);
                            Connection delegate = ((Connections.Unwrappable) managed).getDelegate();
                            String previous = holders.putIfAbsent(delegate, me);
                            if (previous != null) {
                                violations.add("connection held by " + previous + " and " + me);
                            }
                            try {
                                // Exercise the cached-statement path: same SQL every time, so it
                                // is prepared once and reused - and closed, returning it to the
                                // cache along with its result set.
                                try (PreparedStatement ps = managed.prepareStatement(
                                        "SELECT id FROM t WHERE id = ?")) {
                                    ps.setInt(1, i % 1000);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        if (!rs.next()) violations.add("row missing");
                                    }
                                }
                            }
                            catch (Exception e) {
                                violations.add(e.toString());
                            }
                            finally {
                                holders.remove(delegate, me);
                                try {
                                    managed.close();
                                }
                                catch (Exception e) {
                                    violations.add(e.toString());
                                }
                            }
                        }
                    }, "pool-stress-" + t);
                    workers[t].start();
                }
                go.countDown();
                for (Thread w : workers) w.join(120_000);
                pool.close();
                assertEquals("maxIdle=" + maxIdle + " violations: " + violations, List.of(), violations);
            }
        }
    }

    /**
     * A cached statement handed out while still in use must not be the same object, or two
     * overlapping users of one connection would share parameters and a result set.
     */
    @Test
    public void aStatementAlreadyInUseIsNotServedFromTheCacheAgain() throws Exception {
        try (DuckDBConnection root = root()) {
            ConnectionPool pool = new ConnectionPool(root, 4);
            Connection managed = Connections.managed(pool.borrow(), pool, null);
            PreparedStatement first = managed.prepareStatement("SELECT 1");
            PreparedStatement second = managed.prepareStatement("SELECT 1");
            assertTrue("a statement still in use must not be handed out twice", first != second);
            first.close();
            PreparedStatement third = managed.prepareStatement("SELECT 1");
            assertTrue("once closed, the cached statement comes back", first == third);
            third.close();
            second.close();
            managed.close();
            pool.close();
        }
    }

    /**
     * A caller which leaks a result set must not leak a DuckDB transaction with it: the cache
     * closes the result set when the statement is returned. If it did not, a forced checkpoint
     * would fail with an active transaction.
     */
    @Test
    public void resultSetsAreClosedWhenAPooledStatementIsReturned() throws Exception {
        java.io.File file = java.io.File.createTempFile("pooltest_", ".duckdb");
        assertTrue(file.delete());
        file.deleteOnExit();
        try (DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection(
                "jdbc:duckdb:" + file.getAbsolutePath())) {
            try (java.sql.Statement s = root.createStatement()) {
                s.execute("CREATE TABLE t AS SELECT i AS id FROM range(1000) tbl(i)");
            }
            ConnectionPool pool = new ConnectionPool(root, 4);
            for (int i = 0; i < 200; i++) {
                Connection managed = Connections.managed(pool.borrow(), pool, null);
                PreparedStatement ps = managed.prepareStatement("SELECT id FROM t WHERE id = ?");
                ps.setInt(1, i);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                // Deliberately NOT closing rs: the statement's close has to do it.
                ps.close();
                managed.close();
            }
            try (java.sql.Statement s = root.createStatement()) {
                s.execute("FORCE CHECKPOINT");
            }
            pool.close();
        }
        finally {
            file.delete();
            new java.io.File(file.getAbsolutePath() + ".wal").delete();
        }
    }

    // ---------- Lifecycle: a connection must not outlive the pool, nor its statement cache ----------

    @Test
    public void aConnectionClosedElsewhereDoesNotLeaveItsStatementCacheBehind() throws Exception {
        try (DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            ConnectionPool pool = new ConnectionPool(root, 8);
            for (int i = 0; i < 50; i++) {
                Connection connection = pool.borrow();
                // Touch it so a cache exists, then break it behind the pool's back.
                try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                    statement.executeQuery().close();
                }
                pool.statementCacheFor(connection);
                connection.close();
                pool.release(connection);
            }
            assertEquals("a closed connection must take its statement cache with it",
                    0, cacheCount(pool));
            pool.close();
        }
    }

    @Test
    public void aReleaseAfterCloseDiscardsTheConnectionRatherThanPoolingIt() throws Exception {
        try (DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            ConnectionPool pool = new ConnectionPool(root, 8);
            Connection inFlight = pool.borrow();
            pool.close();
            // The request that was still running when the pool shut down now finishes.
            pool.release(inFlight);
            assertTrue("a connection released after close() must be closed, not pooled",
                    inFlight.isClosed());
            assertEquals(0, cacheCount(pool));
        }
    }

    @SuppressWarnings("unchecked")
    private static int cacheCount(ConnectionPool pool) throws Exception {
        java.lang.reflect.Field field = ConnectionPool.class.getDeclaredField("statementCaches");
        field.setAccessible(true);
        return ((java.util.Map<Connection, ?>) field.get(pool)).size();
    }
}
