package io.quackjvm.agent.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a table actually holds: an exact row count, a summary per column, and a few rows.
 *
 * @param table       the table's name
 * @param description what it holds, or null
 * @param rows        the exact row count
 * @param columns     one summary per column
 * @param sample      a few rows, in table column order
 */
public record Profile(String table, String description, long rows, List<ColumnProfile> columns,
                      List<Object[]> sample) {

    public Profile {
        columns = List.copyOf(columns);
        sample = List.copyOf(sample);
    }

    /**
     * Roughly how many distinct values each column holds, by name - for {@link QueryExamples} to choose
     * a group column with few. Columns SUMMARIZE gave no count for are left out.
     */
    public Map<String, Long> distinctValues() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ColumnProfile column : columns) {
            if (column.approxUnique() != null) {
                counts.put(column.column(), column.approxUnique());
            }
        }
        return counts;
    }

    /** The profile as text, for whatever has to decide what to ask next. */
    public String toText() {
        StringBuilder out = new StringBuilder(table).append(": ").append(String.format("%,d", rows)).append(" rows");
        if (description != null) {
            out.append(" - ").append(description);
        }
        for (ColumnProfile column : columns) {
            out.append("\n  ").append(column.toText());
        }
        if (!sample.isEmpty()) {
            out.append("\n  example rows:");
            for (Object[] row : sample) {
                out.append("\n    ");
                for (int i = 0; i < row.length; i++) {
                    out.append(i == 0 ? "" : ", ").append(row[i]);
                }
            }
        }
        return out.toString();
    }
}
