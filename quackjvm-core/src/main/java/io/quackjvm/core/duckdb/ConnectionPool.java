package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small pool of DuckDB connections duplicated from one root connection.
 *
 * <p>CQEngine opens a connection at the start of every request and closes it at the end. Creating
 * a DuckDB connection is not free (tens of microseconds, plus the cost of starting and ending a
 * transaction), and the per-connection temporary staging table used for bulk writes has to be
 * recreated each time. Handing the same connections back out avoids both.</p>
 */
public final class ConnectionPool {

    private final DuckDBConnection rootConnection;
    private final int maxIdle;
    private final Deque<Connection> idle = new ConcurrentLinkedDeque<>();
    private final AtomicInteger idleCount = new AtomicInteger();
    /** One statement cache per pooled connection, kept across borrows - that is the point of it. */
    private final Map<Connection, StatementCache> statementCaches = new ConcurrentHashMap<>();
    /** Set by {@link #close}, so a request still in flight discards its connection rather than
     * returning it to a pool nobody will ever borrow from again. */
    private volatile boolean closed;

    public ConnectionPool(DuckDBConnection rootConnection, int maxIdle) {
        this.rootConnection = rootConnection;
        this.maxIdle = maxIdle;
    }

    /** Takes a connection from the pool, or duplicates a new one if the pool is empty. */
    public Connection borrow() {
        Connection connection = idle.pollFirst();
        if (connection != null) {
            idleCount.decrementAndGet();
            return connection;
        }
        try {
            return rootConnection.duplicate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open a DuckDB connection", e);
        }
    }

    /** The prepared-statement cache of a connection this pool handed out. */
    StatementCache statementCacheFor(Connection connection) {
        return statementCaches.computeIfAbsent(connection, StatementCache::new);
    }

    /**
     * Returns a connection to the pool, rolling back anything the caller left uncommitted.
     * The connection is closed rather than pooled if the pool is full or the connection is broken.
     */
    public void release(Connection connection) {
        release(connection, false);
    }

    /**
     * @param committed whether the borrower's last act on this connection was to commit, so there
     *                  is nothing left to roll back. DuckDB charges about 146 microseconds for a
     *                  rollback even of an empty transaction, which on a single-object write is a
     *                  quarter of the request.
     */
    public void release(Connection connection, boolean committed) {
        StatementCache cache = statementCaches.get(connection);
        if (cache != null) {
            cache.releaseAll();
        }
        try {
            if (closed || connection.isClosed()) {
                // A connection which is broken, or whose pool has shut down, is not coming back:
                // drop its statement cache with it rather than retaining both for the life of
                // the pool.
                discard(connection);
                return;
            }
            if (!connection.getAutoCommit()) {
                if (!committed) {
                    // CQEngine commits before closing; this ends any transaction it did not.
                    connection.rollback();
                }
                connection.setAutoCommit(true);
            }
            if (idleCount.get() < maxIdle) {
                idleCount.incrementAndGet();
                idle.addFirst(connection);
                return;
            }
        }
        catch (SQLException e) {
            discard(connection);
            return;
        }
        discard(connection);
    }

    private void discard(Connection connection) {
        StatementCache cache = statementCaches.remove(connection);
        if (cache != null) {
            cache.clear();
        }
        Sql.closeQuietly(connection);
    }

    /**
     * How many prepares this pool's connections served from cache, and how many reached DuckDB.
     *
     * <p>A prepare costs DuckDB about 200 microseconds, and the difference between a hit and a miss
     * is invisible to anything that only counts JDBC calls - which is why it is counted here, so a
     * test can assert the cache is working rather than infer it from a stopwatch.</p>
     *
     * @return {@code {hits, misses}}
     */
    public long[] getStatementCacheStats() {
        long hits = 0;
        long misses = 0;
        for (StatementCache cache : statementCaches.values()) {
            hits += cache.getHits();
            misses += cache.getMisses();
        }
        return new long[]{hits, misses};
    }

    /** Closes every pooled connection, and the statements cached against them. */
    public void close() {
        closed = true;
        Connection connection;
        while ((connection = idle.pollFirst()) != null) {
            idleCount.decrementAndGet();
            discard(connection);
        }
        for (StatementCache cache : statementCaches.values()) {
            cache.clear();
        }
        statementCaches.clear();
    }
}
