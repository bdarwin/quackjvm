package io.quackjvm.core.page;

import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.layout.ColumnarLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Some consecutive rows of a paged query, in order, and the keys to move from them.
 *
 * <pre>
 * Page page = pages.last();
 * Page older = pages.before(page.firstKey());   // scrolling up
 * Page newer = pages.after(page.lastKey());     // scrolling down
 * </pre>
 */
public final class Page {

    private final List<String> columns;
    private final List<Object[]> rows;
    private final int[] keyColumns;
    private final boolean hasBefore;
    private final boolean hasAfter;

    Page(List<String> columns, List<Object[]> rows, int[] keyColumns, boolean hasBefore, boolean hasAfter) {
        this.columns = List.copyOf(columns);
        this.rows = List.copyOf(rows);
        this.keyColumns = keyColumns;
        this.hasBefore = hasBefore;
        this.hasAfter = hasAfter;
    }

    public List<String> columns() {
        return columns;
    }

    /** The rows, first to last in the pages' order. */
    public List<Object[]> rows() {
        return rows;
    }

    public int size() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Whether there are rows before this page's first - read when the page was. */
    public boolean hasBefore() {
        return hasBefore;
    }

    /** Whether there are rows after this page's last - read when the page was. */
    public boolean hasAfter() {
        return hasAfter;
    }

    /** The key of the first row, to ask for the page before; null for an empty page. */
    public PageKey firstKey() {
        return rows.isEmpty() ? null : keyOf(0);
    }

    /** The key of the last row, to ask for the page after; null for an empty page. */
    public PageKey lastKey() {
        return rows.isEmpty() ? null : keyOf(rows.size() - 1);
    }

    /** The key of one of this page's rows. */
    public PageKey keyOf(int row) {
        Object[] values = new Object[keyColumns.length];
        for (int i = 0; i < keyColumns.length; i++) {
            values[i] = rows.get(row)[keyColumns[i]];
        }
        return PageKey.of(values);
    }

    /** One value, by column name, compared without case. */
    public Object get(int row, String column) {
        return rows.get(row)[indexOf(column)];
    }

    /**
     * The rows as objects of a layout, matching its columns to the page's by name. A layout column
     * the query did not return is left null.
     */
    public <O> List<O> as(ColumnarLayout<O> layout) {
        List<ColumnarLayout.Column<O, ?>> wanted = layout.getColumns();
        int[] from = new int[wanted.size()];
        for (int i = 0; i < from.length; i++) {
            from[i] = indexOrMinusOne(wanted.get(i).getName());
        }
        List<O> objects = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Object[] values = new Object[from.length];
            for (int i = 0; i < from.length; i++) {
                values[i] = from[i] < 0 ? null : DuckDBTypes.fromSqlValue(row[from[i]], wanted.get(i).getType());
            }
            objects.add(layout.createObject(values));
        }
        return objects;
    }

    private int indexOf(String column) {
        int index = indexOrMinusOne(column);
        if (index < 0) {
            throw new IllegalArgumentException("No column " + column + " in " + columns);
        }
        return index;
    }

    private int indexOrMinusOne(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).toLowerCase(Locale.ROOT).equals(column.toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "Page[" + rows.size() + " rows" + (hasBefore ? ", more before" : "") + (hasAfter ? ", more after" : "")
                + "]";
    }
}
