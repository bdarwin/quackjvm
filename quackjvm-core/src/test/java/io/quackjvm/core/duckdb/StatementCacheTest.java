package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StatementCacheTest {

    private DuckDBConnection connection;
    private StatementCache cache;

    @Before
    public void open() throws SQLException {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        cache = new StatementCache(connection);
    }

    @After
    public void close() throws SQLException {
        cache.clear();
        connection.close();
    }

    private void run(String sql) throws SQLException {
        try (PreparedStatement statement = cache.prepare(sql)) {
            statement.executeQuery().close();
        }
    }

    /**
     * Found by the dashboard: a burst of one-off statements - SQL with values written into it -
     * filled the cache, and since nothing was ever evicted, every statement after that was prepared
     * from scratch for the life of the connection. 100% misses on a write path that repeats the same
     * two statements.
     */
    @Test
    public void oneOffStatementsDoNotStopTheCacheWorking() throws SQLException {
        for (int i = 0; i < 1_000; i++) {
            run("SELECT " + i);
        }
        long hitsBefore = cache.getHits();
        for (int i = 0; i < 100; i++) {
            run("SELECT 'the statement that matters'");
        }
        assertEquals("all but the first should be served from the cache", 99, cache.getHits() - hitsBefore);
    }

    @Test
    public void theStatementsInUseAreKept() throws SQLException {
        run("SELECT 'hot'");
        for (int i = 0; i < 1_000; i++) {
            run("SELECT " + i);
            if (i % 10 == 0) {
                run("SELECT 'hot'");
            }
        }
        long hitsBefore = cache.getHits();
        run("SELECT 'hot'");
        assertEquals("a statement used every few calls should never have been evicted", 1, cache.getHits() - hitsBefore);
    }

    @Test
    public void aStatementHandedOutIsNeverEvictedFromUnderItsCaller() throws SQLException {
        try (PreparedStatement held = cache.prepare("SELECT ? + 1")) {
            for (int i = 0; i < 1_000; i++) {
                run("SELECT " + i);
            }
            held.setInt(1, 41);
            try (java.sql.ResultSet row = held.executeQuery()) {
                assertTrue(row.next());
                assertEquals(42, row.getInt(1));
            }
        }
    }
}
