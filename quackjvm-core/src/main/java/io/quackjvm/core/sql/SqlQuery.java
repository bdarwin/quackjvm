package io.quackjvm.core.sql;

import io.quackjvm.core.duckdb.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
 */
public final class SqlQuery {

    private SqlQuery() {
    }

    /**
     * @param connection a connection the returned stream takes ownership of and closes
     * @return a lazy stream of rows; closing it closes the result set, statement and connection
     */
    public static Stream<SqlRow> stream(Connection connection, String sql, Object... parameters) {
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            Sql.bindAll(statement, List.of(parameters), 1);
            ResultSet resultSet = statement.executeQuery();

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
                        Sql.closeQuietly(resultSet);
                        Sql.closeQuietly(toClose);
                        Sql.closeQuietly(connection);
                    });
        }
        catch (SQLException | RuntimeException e) {
            Sql.closeQuietly(statement);
            Sql.closeQuietly(connection);
            throw new IllegalStateException("Failed to run SQL: " + sql, e);
        }
    }
}
