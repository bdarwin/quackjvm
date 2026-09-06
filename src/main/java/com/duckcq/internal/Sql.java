package com.duckcq.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Small helpers for building and running SQL against DuckDB.
 */
public final class Sql {

    /** Diagnostic counter of statements executed; used by benchmarks. */
    public static final java.util.concurrent.atomic.AtomicLong STATEMENTS = new java.util.concurrent.atomic.AtomicLong();

    private Sql() {
    }

    /** Makes an arbitrary attribute name safe to embed in a table name. */
    public static String sanitizeForTableName(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to execute: " + sql, e);
        }
    }

    public static int executeUpdate(Connection connection, String sql, List<Object> parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindAll(statement, parameters, 1);
            return statement.executeUpdate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to execute: " + sql, e);
        }
    }

    public static long queryLong(Connection connection, String sql, List<Object> parameters) {
        STATEMENTS.incrementAndGet();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindAll(statement, parameters, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("No row returned by: " + sql);
                }
                return resultSet.getLong(1);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to execute: " + sql, e);
        }
    }

    public static int bindAll(PreparedStatement statement, List<Object> parameters, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object parameter : parameters) {
            DuckDBTypes.bind(statement, index++, parameter);
        }
        return index;
    }

    /** Renders {@code (?, ?, ...)} with the given number of placeholders. */
    public static String placeholders(int count) {
        StringBuilder sb = new StringBuilder(count * 3 + 2);
        sb.append('(');
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            }
            catch (Exception ignore) {
                // Intentionally ignored.
            }
        }
    }
}
