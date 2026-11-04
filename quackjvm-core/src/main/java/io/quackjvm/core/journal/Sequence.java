package io.quackjvm.core.journal;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A number column a journal fills: one more than the highest so far within its group. */
final class Sequence {

    private final String column;
    private final int at;
    private final boolean isInt;
    private final List<String> within;
    private final int[] withinAt;

    Sequence(String column, List<String> within, ColumnarLayout<?> layout) {
        this.column = column;
        this.at = indexOf(layout, column);
        Class<?> type = layout.getColumn(column).getType();
        this.isInt = type == Integer.class || type == int.class;
        this.within = List.copyOf(within);
        this.withinAt = new int[within.size()];
        for (int i = 0; i < withinAt.length; i++) {
            withinAt[i] = indexOf(layout, within.get(i));
        }
    }

    static void check(ColumnarLayout<?> layout, String column, List<String> within) {
        Class<?> type = layout.getColumn(column).getType();
        if (type != Long.class && type != long.class && type != Integer.class && type != int.class) {
            throw new IllegalArgumentException("A sequence is a Long or Integer column; " + column + " is "
                    + type.getSimpleName());
        }
        if (within.contains(column)) {
            throw new IllegalArgumentException("A sequence cannot count within itself: " + column);
        }
    }

    /**
     * Numbers the row if its sequence column is null or 0. {@code numbered} remembers the highest
     * number of each group so far in this transaction, so a batch numbers on from itself.
     *
     * @return whether it gave the row a number
     */
    boolean number(Connection connection, String table, Object[] row, Map<List<Object>, Long> numbered) {
        List<Object> group = new ArrayList<>(withinAt.length);
        for (int i : withinAt) {
            group.add(row[i]);
        }
        long highest = numbered.computeIfAbsent(group, g -> highest(connection, table, g));
        Object value = row[at];
        if (value == null || ((Number) value).longValue() == 0) {
            long next = highest + 1;
            if (isInt && next > Integer.MAX_VALUE) {
                throw new IllegalStateException("The sequence " + column + " of " + table + " is past the largest"
                        + " Integer in group " + group);
            }
            row[at] = isInt ? Integer.valueOf((int) next) : Long.valueOf(next);
            numbered.put(group, next);
            return true;
        }
        numbered.put(group, Math.max(highest, ((Number) value).longValue()));
        return false;
    }

    private long highest(Connection connection, String table, List<Object> group) {
        StringBuilder sql = new StringBuilder("SELECT coalesce(max(").append(Sql.quote(column)).append("), 0) FROM ")
                .append(Sql.quote(table));
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < within.size(); i++) {
            sql.append(i == 0 ? " WHERE " : " AND ").append(Sql.quote(within.get(i)));
            if (group.get(i) == null) {
                sql.append(" IS NULL");
            }
            else {
                sql.append(" = ?");
                values.add(group.get(i));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            Sql.bindAll(statement, values, 1);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to find the next " + column + " of " + table + ": "
                    + e.getMessage(), e);
        }
    }

    private static int indexOf(ColumnarLayout<?> layout, String column) {
        List<? extends ColumnarLayout.Column<?, ?>> columns = layout.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No column " + column);
    }
}
