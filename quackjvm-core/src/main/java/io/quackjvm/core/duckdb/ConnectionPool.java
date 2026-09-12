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
        StatementCache cache = statementCaches.get(connection);
        if (cache != null) {
            cache.releaseAll();
        }
        try {
            if (connection.isClosed()) {
                return;
            }
            if (!connection.getAutoCommit()) {
                // CQEngine commits before closing; this ends any transaction it did not.
                connection.rollback();
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

    /** Closes every pooled connection, and the statements cached against them. */
    public void close() {
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
