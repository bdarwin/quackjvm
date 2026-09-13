package io.quackjvm.core.duckdb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the prepared statements of one pooled connection, so that repeating a statement does not
 * repeat the work of preparing it.
 *
 * <p>Preparing a statement costs DuckDB around 200 microseconds - it parses, binds and plans the
 * statement - which on a single-object write is a fifth of the total latency and is paid again on
 * every call. Because the pool hands the same connections back out, a statement prepared for one
 * request can serve the next.</p>
 *
 * <p>The cache is per connection and a connection is only ever borrowed by one thread at a time,
 * so no locking is needed beyond the pool's own. A statement handed out twice without being closed
 * - which would mean one request using the same SQL twice at once - is not served from the cache;
 * the second caller gets a real statement of its own.</p>
 *
 * <p><b>Queries are pooled too, and their result sets are closed for them.</b> Closing a real JDBC
 * statement closes the result set it produced, and a pooled statement has to do the same or a
 * caller who relies on that leaks a result set - which on DuckDB means an open transaction and a
 * checkpoint that cannot run. So the cache remembers the result set each {@code executeQuery}
 * returned and closes it when the statement comes back, which is exactly what a real close does.
 * A statement handed out twice without being closed is not served from the cache, so overlapping
 * result sets on one connection each get a statement of their own.</p>
 *
 * <p><b>Invalidation.</b> DuckDB resolves a prepared statement against the catalog as it stands
 * when the statement is prepared, so a cached statement outlives the table it refers to only until
 * that table is dropped or recreated. Every DDL statement quackjvm issues goes through
 * {@link Connection#createStatement()}, so the cache empties whenever one is created. That is
 * conservative - it also discards the cache for harmless statements - but it is cheap, and DDL is
 * rare next to writes.</p>
 */
final class StatementCache {

    /** Enough for an object table and its indexes several times over; a collection reuses few. */
    private static final int MAX_ENTRIES = 64;

    private final Connection connection;
    private final Map<String, Entry> entries = new HashMap<>();

    StatementCache(Connection connection) {
        this.connection = connection;
    }

    /**
     * @return a statement for the given SQL, prepared now or reused from a previous request.
     * Closing it returns it here rather than closing the underlying statement.
     */
    PreparedStatement prepare(String sql) throws SQLException {
        Entry entry = entries.get(sql);
        if (entry != null && !entry.inUse) {
            entry.inUse = true;
            entry.statement.clearParameters();
            return entry.proxy;
        }
        if (entry != null) {
            // Already handed out and not yet closed; this caller needs its own.
            return connection.prepareStatement(sql);
        }
        if (entries.size() >= MAX_ENTRIES) {
            return connection.prepareStatement(sql);
        }
        Entry created = new Entry(connection.prepareStatement(sql));
        created.inUse = true;
        entries.put(sql, created);
        return created.proxy;
    }

    /** Discards every cached statement; see the note on invalidation above. */
    void clear() {
        for (Entry entry : entries.values()) {
            Sql.closeQuietly(entry.statement);
        }
        entries.clear();
    }

    /**
     * Marks every statement as free again. Called when the connection returns to the pool, so that
     * a statement whose caller failed to close it does not leak out of the cache.
     */
    void releaseAll() {
        for (Entry entry : entries.values()) {
            entry.inUse = false;
        }
    }

    private final class Entry {
        private final PreparedStatement statement;
        private final PreparedStatement proxy;
        private boolean inUse;
        /** The result set of the last executeQuery, closed when the statement is returned. */
        private java.sql.ResultSet openResultSet;

        Entry(PreparedStatement statement) {
            this.statement = statement;
            this.proxy = (PreparedStatement) Proxy.newProxyInstance(
                    StatementCache.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (p, method, args) -> {
                        String name = method.getName();
                        if ("close".equals(name) && (args == null || args.length == 0)) {
                            inUse = false;
                            // A real close would close this; so must we, or the caller leaks it.
                            Sql.closeQuietly(openResultSet);
                            openResultSet = null;
                            try {
                                statement.clearBatch();
                            }
                            catch (SQLException ignored) {
                                // A statement which cannot be reset is dropped rather than reused.
                                evict();
                                Sql.closeQuietly(statement);
                            }
                            return null;
                        }
                        Object result;
                        try {
                            result = method.invoke(statement, args);
                        }
                        catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if ("executeQuery".equals(name)) {
                            openResultSet = (java.sql.ResultSet) result;
                        }
                        return result;
                    });
        }

        private void evict() {
            entries.values().remove(this);
        }
    }
}
