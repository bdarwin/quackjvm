package com.duckcq.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * One row of a raw SQL result, addressed by column name or position (1-based).
 */
public final class SqlRow {

    private final ResultSet resultSet;
    private final List<String> columnNames;

    SqlRow(ResultSet resultSet, List<String> columnNames) {
        this.resultSet = resultSet;
        this.columnNames = columnNames;
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public Object get(int columnIndex) {
        return read(() -> resultSet.getObject(columnIndex));
    }

    public Object get(String columnName) {
        return read(() -> resultSet.getObject(columnName));
    }

    public String getString(int columnIndex) {
        return read(() -> resultSet.getString(columnIndex));
    }

    public String getString(String columnName) {
        return read(() -> resultSet.getString(columnName));
    }

    public long getLong(int columnIndex) {
        return read(() -> resultSet.getLong(columnIndex));
    }

    public long getLong(String columnName) {
        return read(() -> resultSet.getLong(columnName));
    }

    public double getDouble(int columnIndex) {
        return read(() -> resultSet.getDouble(columnIndex));
    }

    public double getDouble(String columnName) {
        return read(() -> resultSet.getDouble(columnName));
    }

    /** The row's values in column order, detached from the result set. */
    public Object[] toArray() {
        Object[] values = new Object[columnNames.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = get(i + 1);
        }
        return values;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columnNames.get(i)).append('=').append(get(i + 1));
        }
        return sb.append('}').toString();
    }

    private interface Read<T> {
        T read() throws SQLException;
    }

    private static <T> T read(Read<T> read) {
        try {
            return read.read();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read a column from a SQL result", e);
        }
    }
}
