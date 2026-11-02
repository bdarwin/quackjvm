package io.quackjvm.core.sql;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.internal.Deadline;
import io.quackjvm.core.internal.ReadCheck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Runs raw SQL and streams the rows back.
 *
 * <p><b>The stream takes ownership of the connection</b> and closes it along with the statement and
 * result set. Do not hand it a connection you intend to use again - running two queries on one
 * connection this way fails on the second with "Connection was closed". Pass a fresh connection each
 * time; {@code DuckDBConnection.duplicate()} is cheap, and
 * {@code DuckDBDatabase.sql(...)} does this for you.</p>
 *
 * <p>It reads: a statement that returns no rows, or more than one statement, is refused before it
 * runs - see {@link Rows}.</p>
 */
public final class SqlQuery {

    private SqlQuery() {
    }

    /**
     * @param connection a connection the returned stream takes ownership of and closes
     * @return a lazy stream of rows; closing it closes the result set, statement and connection
     */
    public static Stream<SqlRow> stream(Connection connection, String sql, Object... parameters) {
        return stream(connection, sql, null, parameters);
    }

    /** As {@link #stream(Connection, String, Object...)}, cancelled if still open after the timeout. */
    static Stream<SqlRow> stream(Connection connection, String sql, Duration timeout, Object... parameters) {
        PreparedStatement statement = null;
        Deadline deadline = null;
        try {
            ReadCheck.beforePreparing(sql);
            statement = connection.prepareStatement(sql);
            ReadCheck.afterPreparing(statement, sql);
            Sql.bindAll(statement, Arrays.asList(parameters), 1);
            deadline = Deadline.start(statement, timeout);
            ResultSet resultSet = statement.executeQuery();
            Deadline running = deadline;

            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                columnNames.add(resultSet.getMetaData().getColumnLabel(i));
            }
            // One view over the cursor, reused per row; callers keeping a row past the iteration
            // should copy it with toArray().
            SqlRow row = new SqlRow(resultSet, columnNames);
            PreparedStatement toClose = statement;

            Iterator<SqlRow> iterator = new Iterator<>() {
                private Boolean hasNext;

                @Override
                public boolean hasNext() {
                    if (hasNext == null) {
                        try {
                            hasNext = resultSet.next();
                        }
                        catch (SQLException e) {
                            if (running.hasExpired()) {
                                throw new QueryTimedOut(timeout, e);
                            }
                            throw new IllegalStateException("Failed to read a SQL result row", e);
                        }
                    }
                    return hasNext;
                }

                @Override
                public SqlRow next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    hasNext = null;
                    return row;
                }
            };

            return StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(iterator,
                                    Spliterator.ORDERED | Spliterator.NONNULL), false)
                    .onClose(() -> {
                        running.close();
                        Sql.closeQuietly(resultSet);
                        Sql.closeQuietly(toClose);
                        Sql.closeQuietly(connection);
                    });
        }
        catch (SQLException | RuntimeException e) {
            if (deadline != null) {
                deadline.close();
            }
            Sql.closeQuietly(statement);
            Sql.closeQuietly(connection);
            if (deadline != null && deadline.hasExpired()) {
                throw new QueryTimedOut(timeout, e);
            }
            if (e instanceof ReadCheck.Refused refused) {
                throw refused;
            }
            throw new IllegalStateException("Failed to run SQL: " + sql, e);
        }
    }
}
