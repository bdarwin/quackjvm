package io.quackjvm.core.page;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A query read a page at a time, for scrolling: the page before a row, the page after it, the rows
 * around one. Each page is found from the row next to it - a keyset - not by counting rows from
 * the start, so a page deep in a long result costs what the first one does, and rows added while
 * someone scrolls neither repeat nor go missing.
 *
 * <pre>
 * Pages pages = Pages.of(quack, "SELECT * FROM message WHERE session = ?", sessionId)
 *         .orderBy("ts", "id")         // together unique, and never null
 *         .size(50);
 *
 * Page page = pages.last();                      // the newest 50
 * Page older = pages.before(page.firstKey());    // scrolling up
 * Page hit = pages.around(PageKey.of(ts, id), 25);  // a search hit, with 25 rows each side
 * long rows = pages.count();                     // for the scrollbar
 * </pre>
 *
 * <h2>What the order columns must be</h2>
 *
 * <p>Together they must tell every row apart - a time alone is not enough when two rows can share
 * one, so add an id - and none may be null. The query must return them. Its own {@code ORDER BY}
 * is overridden: the rows come in the order of these columns, ascending, so "last" is the end of
 * that order.</p>
 *
 * <p>Each page is one query: the rows on the far side of the key, in order, with a {@code LIMIT}.
 * Measured on DuckDB 2.0: 4 ms for a page of 51 out of two million rows.</p>
 */
public final class Pages {

    private static final String WRAPPER = "quackjvm_page";

    private final Connection connection;
    private final String sql;
    private final List<Object> parameters;
    private final List<String> order;
    private final int size;
    private volatile Long count;
    /** How far the first order column ran across the last page read, to bound the next search. */
    private volatile Span span;

    private Pages(Connection connection, String sql, List<Object> parameters, List<String> order, int size) {
        this.connection = connection;
        this.sql = sql;
        this.parameters = parameters;
        this.order = order;
        this.size = size;
    }

    /** Pages of a query on a connection, with its parameters. */
    public static Pages of(Connection connection, String sql, List<?> parameters) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("No query to page");
        }
        return new Pages(connection, sql.replaceAll("[;\\s]+$", ""),
                Collections.unmodifiableList(new ArrayList<>(parameters)), List.of(), 50);
    }

    /** Pages of a query on a {@link Quack}'s database, borrowing a connection per page. */
    public static Pages of(Quack quack, String sql, Object... parameters) {
        return of(quack.sharedConnection(), sql, Arrays.asList(parameters));
    }

    /** The columns the rows are ordered by, ascending: together unique, and never null. */
    public Pages orderBy(String... columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("Order by at least one column");
        }
        return new Pages(connection, sql, parameters, List.of(columns), size);
    }

    /** How many rows a page holds. 50 unless set. */
    public Pages size(int rows) {
        if (rows < 1) {
            throw new IllegalArgumentException("A page holds at least one row: " + rows);
        }
        return new Pages(connection, sql, parameters, order, rows);
    }

    public int getSize() {
        return size;
    }

    // ---------- pages ----------

    /** The first rows. */
    public Page first() {
        Fetched rows = fetch(null, Side.AFTER_OR_AT, size + 1);
        return page(rows, false, false, rows.rows.size() > size);
    }

    /** The last rows - the newest, for a log or a conversation. */
    public Page last() {
        Fetched rows = fetch(null, Side.BEFORE, size + 1);
        return page(rows, true, rows.rows.size() > size, false);
    }

    /** The rows just before a key, not including its row. {@code hasAfter()} is true: the key's row is after. */
    public Page before(PageKey key) {
        Fetched rows = fetch(require(key), Side.BEFORE, size + 1);
        return page(rows, true, rows.rows.size() > size, true);
    }

    /** The rows just after a key, not including its row. {@code hasBefore()} is true: the key's row is before. */
    public Page after(PageKey key) {
        Fetched rows = fetch(require(key), Side.AFTER, size + 1);
        return page(rows, false, true, rows.rows.size() > size);
    }

    /**
     * The row with this key and up to {@code each} rows on each side of it - for jumping to a search
     * hit. If no row has the key, the rows on each side of where it would be.
     */
    public Page around(PageKey key, int each) {
        if (each < 0) {
            throw new IllegalArgumentException("Rows on each side cannot be negative: " + each);
        }
        require(key);
        Fetched before = fetch(key, Side.BEFORE, each + 1);
        Fetched after = fetch(key, Side.AFTER_OR_AT, each + 2);
        boolean found = !after.rows.isEmpty() && after.keyOf(0).equals(key);
        int keep = found ? each + 1 : each;
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> earlier = before.rows.subList(0, Math.min(each, before.rows.size()));
        for (int i = earlier.size() - 1; i >= 0; i--) {
            rows.add(earlier.get(i));
        }
        rows.addAll(after.rows.subList(0, Math.min(keep, after.rows.size())));
        return new Page(after.columns, rows, after.keyColumns, before.rows.size() > each, after.rows.size() > keep);
    }

    /**
     * The rows starting at a position, counted from the first - for a scrollbar dragged to the middle.
     * Unlike the others this counts rows to get there, so it costs more the further it goes.
     */
    public Page at(long position) {
        if (position < 0) {
            throw new IllegalArgumentException("A position cannot be negative: " + position);
        }
        String query = "SELECT * FROM (\n" + sql + "\n) AS " + WRAPPER + " ORDER BY " + orderList(false)
                + " LIMIT " + (size + 1) + " OFFSET " + position;
        Fetched rows = run(query, parameters);
        return page(rows, false, position > 0, rows.rows.size() > size);
    }

    // ---------- counting ----------

    /** How many rows the query has, counted the first time it is asked and remembered. */
    public long count() {
        Long known = count;
        if (known != null) {
            return known;
        }
        return recount();
    }

    /** Counts the rows again, and remembers that. */
    public long recount() {
        String query = "SELECT count(*) FROM (\n" + sql + "\n) AS " + WRAPPER;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            Sql.bindAll(statement, parameters, 1);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                long counted = result.getLong(1);
                Sql.finishReading(result);
                count = counted;
                return counted;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to count: " + e.getMessage(), e);
        }
    }

    /** How many rows come before this key: its row's position, counted from 0. */
    public long position(PageKey key) {
        require(key);
        List<Object> all = new ArrayList<>(parameters);
        String condition = condition(key, Side.BEFORE, all);
        String query = "SELECT count(*) FROM (\n" + sql + "\n) AS " + WRAPPER + " WHERE " + condition;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            Sql.bindAll(statement, all, 1);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                long before = result.getLong(1);
                Sql.finishReading(result);
                return before;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to find a position: " + e.getMessage(), e);
        }
    }

    // ---------- underneath ----------

    private enum Side { BEFORE, AFTER, AFTER_OR_AT }

    /** Rows as they came, the key columns' places among them. */
    private record Fetched(List<String> columns, List<Object[]> rows, int[] keyColumns) {
        PageKey keyOf(int row) {
            Object[] values = new Object[keyColumns.length];
            for (int i = 0; i < keyColumns.length; i++) {
                values[i] = rows.get(row)[keyColumns[i]];
            }
            return PageKey.of(values);
        }
    }

    /** A page from rows fetched; those fetched backwards are turned round, and the extra row dropped. */
    private Page page(Fetched fetched, boolean backwards, boolean hasBefore, boolean hasAfter) {
        List<Object[]> rows = fetched.rows;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
        }
        if (backwards) {
            List<Object[]> forwards = new ArrayList<>(rows);
            Collections.reverse(forwards);
            rows = forwards;
        }
        return new Page(fetched.columns, rows, fetched.keyColumns, hasBefore, hasAfter);
    }

    private PageKey require(PageKey key) {
        if (key == null) {
            throw new IllegalArgumentException("No key: an empty page has none - ask for first() or last()");
        }
        if (order.isEmpty()) {
            throw new IllegalStateException("Say which columns order the rows first: orderBy(...)");
        }
        if (key.values().size() != order.size()) {
            throw new IllegalArgumentException("The key has " + key.values().size() + " values, for "
                    + order.size() + " order columns " + order);
        }
        return key;
    }

    /** The rows on one side of the key - or from either end, with no key - nearest first. */
    private Fetched fetch(PageKey key, Side side, int limit) {
        if (order.isEmpty()) {
            throw new IllegalStateException("Say which columns order the rows first: orderBy(...)");
        }
        Object bound = key == null ? null : bound(key, side, limit);
        if (bound != null) {
            Fetched near = fetch(key, side, limit, bound);
            if (near.rows.size() == limit) {
                remember(near);
                return near;
            }
            // Fewer than asked for near the key: there may be more beyond the bound, so look everywhere.
        }
        Fetched rows = fetch(key, side, limit, null);
        remember(rows);
        return rows;
    }

    /**
     * {@code ORDER BY ... LIMIT} has to rank every row on the far side of the key - 54,000 of them
     * half way down 100,000, 6 ms, measured - so when the last page says how far a page reaches, the
     * search is bounded to a few pages' reach of the first order column first. If that bound leaves
     * fewer rows than the page needs, the search runs again without it, so the answer is the same.
     */
    private Fetched fetch(PageKey key, Side side, int limit, Object bound) {
        List<Object> all = new ArrayList<>(parameters);
        StringBuilder query = new StringBuilder("SELECT * FROM (\n").append(sql).append("\n) AS ").append(WRAPPER);
        if (key != null) {
            query.append(" WHERE ").append(condition(key, side, all));
            if (bound != null) {
                query.append(" AND ").append(Sql.quote(order.get(0))).append(side == Side.BEFORE ? " >= ?" : " <= ?");
                all.add(bound);
            }
        }
        query.append(" ORDER BY ").append(orderList(side == Side.BEFORE)).append(" LIMIT ").append(limit);
        return run(query.toString(), all);
    }

    private void remember(Fetched fetched) {
        if (fetched.rows.size() < 2) {
            return;
        }
        Span measured = Span.between(fetched.keyOf(0).values().get(0),
                fetched.keyOf(fetched.rows.size() - 1).values().get(0), fetched.rows.size());
        if (measured != null) {
            span = measured;
        }
    }

    /** Where to stop looking: four times as far from the key as the last page reached for this many rows. */
    private Object bound(PageKey key, Side side, int limit) {
        Span known = span;
        return known == null ? null : known.from(key.values().get(0), side == Side.BEFORE ? -1 : 1, limit * 4.0);
    }

    /** How far a first order column moves per row, for the kinds of value it can be measured for. */
    private record Span(double perRow, Class<?> kind) {

        static Span between(Object a, Object b, int rows) {
            Double distance = distance(a, b);
            return distance == null ? null : new Span(Math.abs(distance) / (rows - 1), a.getClass());
        }

        /** The value {@code rows} rows' reach from the start, in the direction given; null if it cannot say. */
        Object from(Object start, int direction, double rows) {
            if (start == null || start.getClass() != kind || perRow <= 0) {
                return null;   // ties all through the last page, or another kind: no bound
            }
            double reach = perRow * rows * direction;
            if (start instanceof Long n) {
                double to = n + reach;
                return to <= Long.MIN_VALUE || to >= Long.MAX_VALUE ? null : (Object) (long) Math.floor(to);
            }
            if (start instanceof Double d) {
                return d + reach;
            }
            long nanos = (long) Math.max(Math.min(reach, Long.MAX_VALUE / 2.0), Long.MIN_VALUE / 2.0);
            try {
                if (start instanceof java.time.LocalDateTime t) {
                    return t.plusNanos(nanos);
                }
                if (start instanceof java.time.OffsetDateTime t) {
                    return t.plusNanos(nanos);
                }
                if (start instanceof java.time.LocalDate d) {
                    return d.plusDays((long) Math.floor(reach / 86_400e9));
                }
            }
            catch (java.time.DateTimeException outOfRange) {
                return null;
            }
            return null;
        }

        private static Double distance(Object a, Object b) {
            if (a == null || b == null || a.getClass() != b.getClass()) {
                return null;
            }
            if (a instanceof Long x) {
                return (double) ((Long) b - x);
            }
            if (a instanceof Double x) {
                return (Double) b - x;
            }
            if (a instanceof java.time.LocalDateTime x) {
                return (double) java.time.Duration.between(x, (java.time.LocalDateTime) b).toNanos();
            }
            if (a instanceof java.time.OffsetDateTime x) {
                return (double) java.time.Duration.between(x, (java.time.OffsetDateTime) b).toNanos();
            }
            if (a instanceof java.time.LocalDate x) {
                return (double) java.time.temporal.ChronoUnit.DAYS.between(x, (java.time.LocalDate) b) * 86_400e9;
            }
            return null;
        }
    }

    private Fetched run(String query, List<Object> all) {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            Sql.bindAll(statement, all, 1);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                int width = meta.getColumnCount();
                List<String> columns = new ArrayList<>(width);
                for (int i = 1; i <= width; i++) {
                    columns.add(meta.getColumnLabel(i));
                }
                int[] keyColumns = keyColumns(columns);
                List<Object[]> rows = new ArrayList<>();
                while (result.next()) {
                    Object[] row = new Object[width];
                    for (int i = 0; i < width; i++) {
                        row[i] = result.getObject(i + 1);
                    }
                    rows.add(row);
                }
                return new Fetched(columns, rows, keyColumns);
            }
        }
        catch (SQLException e) {
            String message = String.valueOf(e.getMessage());
            for (String column : order) {
                if (message.contains("Referenced column \"" + column + "\" not found")) {
                    throw new IllegalArgumentException("The query does not return the order column " + column
                            + ", so a page cannot say where it ends. DuckDB said: " + message.split("\n")[0], e);
                }
            }
            throw new IllegalStateException("Failed to read a page: " + message, e);
        }
    }

    private int[] keyColumns(List<String> columns) {
        int[] places = new int[order.size()];
        for (int k = 0; k < places.length; k++) {
            places[k] = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).toLowerCase(Locale.ROOT).equals(order.get(k).toLowerCase(Locale.ROOT))) {
                    places[k] = i;
                    break;
                }
            }
            if (places[k] < 0) {
                throw new IllegalArgumentException("The query does not return the order column " + order.get(k)
                        + ", so a page cannot say where it ends. It returns " + columns + ".");
            }
        }
        return places;
    }

    private String orderList(boolean descending) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(Sql.quote(order.get(i))).append(descending ? " DESC" : " ASC");
        }
        return list.toString();
    }

    /**
     * Rows before or after a key, in the order of the columns: {@code a < ? OR (a = ? AND b < ?)}.
     * Written out rather than as a row comparison, which DuckDB ran about 20% slower, measured.
     */
    private String condition(PageKey key, Side side, List<Object> bound) {
        String strict = side == Side.BEFORE ? " < ?" : " > ?";
        String last = side == Side.AFTER_OR_AT ? " >= ?" : strict;
        StringBuilder condition = new StringBuilder("(");
        for (int k = 0; k < order.size(); k++) {
            condition.append(k == 0 ? "(" : " OR (");
            for (int e = 0; e < k; e++) {
                condition.append(Sql.quote(order.get(e))).append(" = ? AND ");
                bound.add(value(key, e));
            }
            condition.append(Sql.quote(order.get(k))).append(k == order.size() - 1 ? last : strict).append(')');
            bound.add(value(key, k));
        }
        return condition.append(')').toString();
    }

    private static Object value(PageKey key, int index) {
        return DuckDBTypes.toSqlValue(key.values().get(index));
    }

    @Override
    public String toString() {
        return "Pages[" + order + ", " + size + " a page]";
    }
}
