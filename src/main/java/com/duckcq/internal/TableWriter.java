package com.duckcq.internal;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes rows into a DuckDB table, choosing between two strategies:
 *
 * <ul>
 *     <li><b>Small batches</b> (the usual case for {@code collection.add(object)}) go through a
 *     JDBC batch of prepared INSERTs, preceded by a DELETE of the keys being written so that
 *     re-adding an object is idempotent.</li>
 *     <li><b>Large batches</b> (bulk loads) are streamed into a per-connection temporary table
 *     through DuckDB's Appender - roughly an order of magnitude faster than prepared INSERTs -
 *     and then moved into the target table with a single set-based statement.</li>
 * </ul>
 */
public final class TableWriter {

    /** Batches at or below this size use prepared statements rather than the Appender. */
    public static final int DEFAULT_APPENDER_THRESHOLD = 1024;

    /**
     * How many rows are staged before being moved into the target table.
     *
     * <p>This bounds how much memory a bulk load needs: without it, loading a million objects
     * would build a million-row staging table and then copy it in one statement, which exceeds a
     * modest DuckDB {@code memory_limit}.</p>
     */
    public static final int DEFAULT_STAGING_CHUNK_ROWS = 131_072;

    private final String tableName;
    private final String stagingTableName;
    private final List<ColumnDef> columns;
    private final String columnList;
    private final boolean orReplace;
    private final int appenderThreshold;
    private final int stagingChunkRows;

    /**
     * @param tableName the target table; its first column is treated as the key column
     * @param columns   all columns of the table, key column first
     * @param orReplace whether inserts should replace rows which violate a primary key
     */
    public TableWriter(String tableName, List<ColumnDef> columns, boolean orReplace, int appenderThreshold) {
        this(tableName, columns, orReplace, appenderThreshold, DEFAULT_STAGING_CHUNK_ROWS);
    }

    public TableWriter(String tableName, List<ColumnDef> columns, boolean orReplace, int appenderThreshold,
                       int stagingChunkRows) {
        this.stagingChunkRows = Math.max(appenderThreshold, stagingChunkRows);
        this.tableName = tableName;
        this.stagingTableName = "cqstg_" + tableName;
        this.columns = columns;
        this.columnList = renderColumnList(columns);
        this.orReplace = orReplace;
        this.appenderThreshold = appenderThreshold;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    public String getKeyColumn() {
        return columns.get(0).getName();
    }

    /** {@code ("a", "b", ...)} - naming the columns keeps inserts correct if the table's column
     * order ever differs from the order this writer produces values in. */
    private static String renderColumnList(List<ColumnDef> columns) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Sql.quote(columns.get(i).getName()));
        }
        return sb.append(')').toString();
    }

    /** Verifies that an existing table has the columns this writer expects. */
    public void validateSchema(Connection connection) {
        List<String> actual = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT column_name FROM duckdb_columns() WHERE table_name = ? ORDER BY column_index")) {
            statement.setString(1, tableName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actual.add(resultSet.getString(1));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the schema of table " + tableName, e);
        }
        List<String> expected = columns.stream().map(ColumnDef::getName).toList();
        if (!actual.isEmpty() && !actual.equals(expected)) {
            throw new IllegalStateException("The DuckDB table '" + tableName + "' has columns " + actual
                    + " but this collection expects " + expected + ". The database was written with a different "
                    + "object layout; migrate it, or use a different database file.");
        }
    }

    /** The outcome of a write: how many rows were inserted, and how many existing rows they displaced. */
    public static final class WriteResult {
        public final long rowsWritten;
        public final long rowsReplaced;

        WriteResult(long rowsWritten, long rowsReplaced) {
            this.rowsWritten = rowsWritten;
            this.rowsReplaced = rowsReplaced;
        }
    }

    public void createTable(Connection connection, boolean primaryKeyOnKeyColumn) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(Sql.quote(tableName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append(columns.get(i).toDdl());
        }
        if (primaryKeyOnKeyColumn) {
            ddl.append(", PRIMARY KEY (").append(Sql.quote(getKeyColumn())).append(")");
        }
        ddl.append(')');
        Sql.execute(connection, ddl.toString());
    }

    public boolean tableExists(Connection connection) {
        return Sql.queryLong(connection,
                "SELECT count(*) FROM duckdb_tables() WHERE table_name = ?",
                List.of(tableName)) > 0;
    }

    public void dropTable(Connection connection) {
        Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(tableName));
    }

    public void clear(Connection connection) {
        Sql.execute(connection, "DELETE FROM " + Sql.quote(tableName));
    }

    public long count(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(tableName), List.of());
    }

    /**
     * Writes the given rows (each an array of column values in column order).
     *
     * @param deleteExistingKeys when true, rows already stored under the keys being written are
     *                           deleted first, which makes re-adding an existing object a no-op.
     *                           Bulk imports can skip this.
     */
    public WriteResult write(Connection connection, Iterator<Object[]> rows, boolean deleteExistingKeys) {
        List<Object[]> buffered = new ArrayList<>(Math.min(appenderThreshold, 256));
        while (rows.hasNext() && buffered.size() < appenderThreshold) {
            buffered.add(rows.next());
        }
        if (buffered.isEmpty()) {
            return new WriteResult(0, 0);
        }
        return rows.hasNext()
                ? writeViaStagingTable(connection, buffered, rows, deleteExistingKeys)
                : writeViaPreparedStatements(connection, buffered, deleteExistingKeys);
    }

    private WriteResult writeViaPreparedStatements(Connection connection, List<Object[]> rows, boolean deleteExistingKeys) {
        if (orReplace && deleteExistingKeys) {
            return insertIgnoringExistingKeys(connection, rows);
        }
        long replaced = 0;
        if (deleteExistingKeys) {
            Set<Object> keys = new LinkedHashSet<>(rows.size());
            for (Object[] row : rows) {
                keys.add(row[0]);
            }
            replaced = deleteKeys(connection, keys);
        }
        StringBuilder sql = new StringBuilder("INSERT ");
        if (orReplace) sql.append("OR REPLACE ");
        sql.append("INTO ").append(Sql.quote(tableName)).append(' ').append(columnList)
                .append(" VALUES ").append(Sql.placeholders(columns.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    DuckDBTypes.bind(statement, i + 1, row[i]);
                }
                statement.addBatch();
            }
            long written = 0;
            for (int updateCount : statement.executeBatch()) {
                written += Math.max(updateCount, 0);
            }
            return new WriteResult(written, replaced);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to insert " + rows.size() + " rows into " + tableName, e);
        }
    }

    /**
     * Writes rows into a table with a primary key, without paying for a delete when the keys turn
     * out to be new - which is the common case for {@code collection.add(object)}.
     *
     * <p>An {@code INSERT OR IGNORE} both writes the new rows and reports how many keys were
     * already present. Only if some were does a second statement run to replace them, so the
     * usual path is one round trip to DuckDB instead of two.</p>
     */
    private WriteResult insertIgnoringExistingKeys(Connection connection, List<Object[]> rows) {
        long inserted = executeInsertBatch(connection, rows, "INSERT OR IGNORE INTO ");
        if (inserted == rows.size()) {
            return new WriteResult(inserted, 0);
        }
        // Some keys already existed and were skipped; overwrite them. Re-writing the rows which
        // were just inserted is harmless, as they are replaced by identical values.
        executeInsertBatch(connection, rows, "INSERT OR REPLACE INTO ");
        return new WriteResult(rows.size(), rows.size() - inserted);
    }

    private long executeInsertBatch(Connection connection, List<Object[]> rows, String insertPrefix) {
        String sql = insertPrefix + Sql.quote(tableName) + ' ' + columnList
                + " VALUES " + Sql.placeholders(columns.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    DuckDBTypes.bind(statement, i + 1, row[i]);
                }
                statement.addBatch();
            }
            long written = 0;
            for (int updateCount : statement.executeBatch()) {
                written += Math.max(updateCount, 0);
            }
            return written;
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to insert " + rows.size() + " rows into " + tableName, e);
        }
    }

    /**
     * Streams the rows through a temporary staging table, a chunk at a time, so that the memory a
     * bulk load needs is bounded by the chunk size rather than by the size of the whole batch.
     */
    private WriteResult writeViaStagingTable(Connection connection, List<Object[]> buffered,
                                             Iterator<Object[]> remaining, boolean deleteExistingKeys) {
        createStagingTable(connection);
        long written = 0;
        long replaced = 0;
        Iterator<Object[]> rows = concat(buffered.iterator(), remaining);
        while (rows.hasNext()) {
            long chunkRows = appendChunkToStagingTable(connection, rows);
            if (chunkRows == 0) {
                break;
            }
            written += chunkRows;
            replaced += moveStagedRowsIntoTable(connection, deleteExistingKeys);
        }
        return new WriteResult(written, replaced);
    }

    private long moveStagedRowsIntoTable(Connection connection, boolean deleteExistingKeys) {
        long replaced = 0;
        if (deleteExistingKeys) {
            replaced = Sql.executeUpdate(connection,
                    "DELETE FROM " + Sql.quote(tableName) + " WHERE " + Sql.quote(getKeyColumn())
                            + " IN (SELECT " + Sql.quote(getKeyColumn()) + " FROM " + Sql.quote(stagingTableName) + ")",
                    List.of());
        }
        Sql.execute(connection, "INSERT " + (orReplace ? "OR REPLACE " : "") + "INTO " + Sql.quote(tableName)
                + " " + columnList + " SELECT " + columnList.substring(1, columnList.length() - 1)
                + " FROM " + Sql.quote(stagingTableName));
        Sql.execute(connection, "DELETE FROM " + Sql.quote(stagingTableName));
        return replaced;
    }

    private static Iterator<Object[]> concat(Iterator<Object[]> first, Iterator<Object[]> second) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return first.hasNext() || second.hasNext();
            }

            @Override
            public Object[] next() {
                return first.hasNext() ? first.next() : second.next();
            }
        };
    }

    private void createStagingTable(Connection connection) {
        StringBuilder ddl = new StringBuilder("CREATE TEMP TABLE IF NOT EXISTS ")
                .append(Sql.quote(stagingTableName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append(columns.get(i).toDdl());
        }
        Sql.execute(connection, ddl.append(')').toString());
        Sql.execute(connection, "DELETE FROM " + Sql.quote(stagingTableName));
    }

    /** Appends up to {@link #stagingChunkRows} rows to the staging table. */
    private long appendChunkToStagingTable(Connection connection, Iterator<Object[]> rows) {
        DuckDBConnection duckDBConnection = Connections.duckDB(connection);
        long written = 0;
        try (DuckDBAppender appender = duckDBConnection.createAppender("temp", "main", stagingTableName)) {
            while (rows.hasNext() && written < stagingChunkRows) {
                appendRow(appender, rows.next());
                written++;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to bulk-append rows to " + tableName, e);
        }
        return written;
    }

    private void appendRow(DuckDBAppender appender, Object[] row) throws SQLException {
        appender.beginRow();
        for (int i = 0; i < row.length; i++) {
            DuckDBTypes.append(appender, row[i], columns.get(i).getJavaType());
        }
        appender.endRow();
    }

    /**
     * A long-lived appender on this table, for streaming inserts.
     *
     * <p>Rows are buffered and become visible to other connections as DuckDB flushes them, which
     * it does automatically every couple of thousand rows and whenever {@link #flush()} is called.
     * Constraint violations - a duplicate primary key, for instance - surface at flush time rather
     * than when the row is appended.</p>
     */
    public final class AppenderHandle implements AutoCloseable {

        private final DuckDBAppender appender;

        private AppenderHandle(DuckDBAppender appender) {
            this.appender = appender;
        }

        public void appendRow(Object[] row) {
            try {
                TableWriter.this.appendRow(appender, row);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to append a row to " + tableName, e);
            }
        }

        /** Makes every row appended so far visible to other connections. */
        public void flush() {
            try {
                appender.flush();
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to flush appended rows to " + tableName, e);
            }
        }

        @Override
        public void close() {
            try {
                appender.close();
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to close the appender on " + tableName, e);
            }
        }
    }

    /** Opens an appender which writes directly into this table. */
    public AppenderHandle openAppender(Connection connection) {
        try {
            return new AppenderHandle(Connections.duckDB(connection)
                    .createAppender(DuckDBConnection.DEFAULT_SCHEMA, tableName));
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open an appender on " + tableName, e);
        }
    }

    /** Deletes every row whose key column matches one of the given keys. */
    public int deleteKeys(Connection connection, Iterable<?> keys) {
        Iterator<?> iterator = keys.iterator();
        int deleted = 0;
        List<Object> chunk = new ArrayList<>(appenderThreshold);
        while (iterator.hasNext()) {
            chunk.clear();
            while (iterator.hasNext() && chunk.size() < appenderThreshold) {
                chunk.add(iterator.next());
            }
            if (chunk.isEmpty()) {
                break;
            }
            deleted += Sql.executeUpdate(connection,
                    "DELETE FROM " + Sql.quote(tableName) + " WHERE " + Sql.quote(getKeyColumn())
                            + " IN " + Sql.placeholders(chunk.size()),
                    chunk);
        }
        return deleted;
    }

}
