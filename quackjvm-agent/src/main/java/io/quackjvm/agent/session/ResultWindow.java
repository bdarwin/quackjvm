package io.quackjvm.agent.session;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A guarded query's whole result, kept in the session's database and read a window at a time - for
 * a grid that scrolls through two million rows while showing fifty.
 *
 * <pre>
 * try (ResultWindow window = session.window(sql, CancelSignal.from(stop::get))) {
 *     long total = window.rowCount();             // for the scrollbar
 *     List&lt;Object[]&gt; visible = window.rows(1_500_000, 50);
 *     window.sort("level", true);                 // re-sorted, the query is not run again
 * }
 * </pre>
 *
 * <p>The query runs once, into a table of an in-memory database attached to the session's - so it
 * does not show among the session's tables - and what does not fit in memory spills into the
 * session's temp folder. A window read finds its rows by position, and never runs the query again.
 * Measured on two million rows: a window anywhere in them in 0.5 ms, a re-sort in 47-123 ms.</p>
 *
 * <p>The result is the database's, so it does not survive the session opening its database again,
 * which {@link QuerySession#allowFolders} and registering a file from a new folder can do. Reading
 * a window after that throws, saying so; make it again with {@link QuerySession#window}.</p>
 */
public final class ResultWindow implements AutoCloseable {

    private final QuerySession session;
    private final int generation;
    private final String sql;
    private final String table;
    private final long rows;
    private final List<String> columns;
    private final List<String> types;
    private String sortedBy;
    private boolean ascending = true;
    private boolean closed;

    ResultWindow(QuerySession session, int generation, String sql, String table, long rows, List<String> columns,
                 List<String> types) {
        this.session = session;
        this.generation = generation;
        this.sql = sql;
        this.table = table;
        this.rows = rows;
        this.columns = List.copyOf(columns);
        this.types = List.copyOf(types);
    }

    /** How many rows the query returned. */
    public long rowCount() {
        return rows;
    }

    public List<String> columns() {
        return columns;
    }

    /** The SQL type of each column, as DuckDB names it. */
    public List<String> columnTypes() {
        return types;
    }

    public String getSql() {
        return sql;
    }

    /**
     * Up to {@code count} rows starting at {@code first}, counted from 0, in the query's order or
     * the last {@link #sort}'s. Fewer, or none, past the end.
     */
    public synchronized List<Object[]> rows(long first, int count) {
        if (first < 0 || count < 0) {
            throw new IllegalArgumentException("A window starts at 0 or later and holds 0 rows or more: " + first
                    + ", " + count);
        }
        checkOpen();
        String from = sortedBy == null ? qualified(table) : qualified(sortedTable());
        return session.inDatabase(generation, database -> {
            try (Connection connection = database.connection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + from
                         + " WHERE rowid >= ? AND rowid < ? ORDER BY rowid")) {
                statement.setLong(1, first);
                statement.setLong(2, first + count);
                try (ResultSet result = statement.executeQuery()) {
                    List<Object[]> window = new ArrayList<>(count);
                    int width = columns.size();
                    while (result.next()) {
                        Object[] row = new Object[width];
                        for (int i = 0; i < width; i++) {
                            row[i] = result.getObject(i + 1);
                        }
                        window.add(row);
                    }
                    return window;
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to read the window: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Orders the rows by one column, without running the query again. Rows that tie keep the query's
     * order, and nulls come last either way.
     */
    public synchronized void sort(String column, boolean ascending) {
        checkOpen();
        String name = column(column);
        String sorted = sortedTable();
        session.inDatabase(generation, database -> {
            Sql.execute(database.sharedConnection(), "CREATE OR REPLACE TABLE " + qualified(sorted) + " AS SELECT * FROM "
                    + qualified(table) + " ORDER BY " + Sql.quote(name) + (ascending ? " ASC" : " DESC")
                    + " NULLS LAST, rowid");
            return null;
        });
        this.sortedBy = name;
        this.ascending = ascending;
    }

    /** Back to the query's own order. */
    public synchronized void unsort() {
        checkOpen();
        if (sortedBy != null) {
            dropQuietly(sortedTable());
            sortedBy = null;
            ascending = true;
        }
    }

    /** The column the rows are sorted by, or null in the query's order. */
    public synchronized String sortedBy() {
        return sortedBy;
    }

    public synchronized boolean isAscending() {
        return ascending;
    }

    /** Whether it can still be read: not closed, and its session has not opened its database again. */
    public synchronized boolean isReadable() {
        return !closed && session.isCurrent(generation);
    }

    /** Drops the stored result. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (session.isCurrent(generation)) {
            dropQuietly(sortedTable());
            dropQuietly(table);
        }
    }

    private void dropQuietly(String name) {
        try {
            session.inDatabase(generation, database -> {
                Sql.execute(database.sharedConnection(), "DROP TABLE IF EXISTS " + qualified(name));
                return null;
            });
        }
        catch (IllegalStateException gone) {
            // The session closed or moved on, and the table went with its database.
        }
    }

    private String column(String column) {
        for (String name : columns) {
            if (name.toLowerCase(Locale.ROOT).equals(column.toLowerCase(Locale.ROOT))) {
                return name;
            }
        }
        throw new IllegalArgumentException("No column " + column + " in " + columns);
    }

    private String sortedTable() {
        return table + "_sorted";
    }

    static String qualified(String table) {
        return Sql.quote(QuerySession.WINDOWS) + ".main." + Sql.quote(table);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("This window has been closed");
        }
    }

    /** Reads what a stored result holds, for a new window. */
    static ResultWindow describe(QuerySession session, int generation, Quack database, String sql, String table,
                                 long rows) {
        List<String> columns = new ArrayList<>();
        List<String> types = new ArrayList<>();
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + qualified(table) + " LIMIT 0");
             ResultSet result = statement.executeQuery()) {
            ResultSetMetaData meta = result.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.add(meta.getColumnLabel(i));
                types.add(meta.getColumnTypeName(i));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the stored result: " + e.getMessage(), e);
        }
        return new ResultWindow(session, generation, sql, table, rows, columns, types);
    }

    @Override
    public String toString() {
        return "ResultWindow[" + rows + " rows, " + columns.size() + " columns"
                + (sortedBy == null ? "" : ", by " + sortedBy + (ascending ? "" : " desc")) + (closed ? ", closed" : "")
                + "]";
    }
}
