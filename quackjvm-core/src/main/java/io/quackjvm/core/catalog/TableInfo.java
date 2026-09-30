package io.quackjvm.core.catalog;

import java.util.List;

/**
 * A table, as something that has to decide what to query would want it described.
 *
 * @param name        the table's name
 * @param description what it holds, from the comment on the table, or null
 * @param rows        DuckDB's row-count estimate, or -1 when it has none
 * @param columns     its columns, in table order
 */
public record TableInfo(String name, String description, long rows, List<ColumnInfo> columns) {

    public TableInfo {
        columns = List.copyOf(columns);
    }

    public ColumnInfo column(String columnName) {
        for (ColumnInfo column : columns) {
            if (column.name().equals(columnName)) {
                return column;
            }
        }
        return null;
    }

    /** The table as a few lines of text: its name, what it holds, and a line per column. */
    public String toText() {
        StringBuilder out = new StringBuilder(name);
        if (rows >= 0) {
            out.append(" (~").append(String.format("%,d", rows)).append(" rows)");
        }
        if (description != null) {
            out.append(" - ").append(description);
        }
        for (ColumnInfo column : columns) {
            out.append("\n  ").append(column.toText());
        }
        return out.toString();
    }
}
