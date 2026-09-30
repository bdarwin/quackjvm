package io.quackjvm.core.guard;

import java.time.Duration;
import java.util.List;

/**
 * What a guarded query returned: the columns, the rows, and what it cost.
 *
 * <p>Materialised rather than streamed, because the caps have to be decided before an answer is
 * handed over: a result that stopped half way, read as though it were the whole answer, is worse
 * than being told it was too big. For reading a lot of rows, use the ordinary
 * {@link io.quackjvm.core.sql.Rows} API on a connection of your own.</p>
 */
public final class GuardedResult {

    private final List<String> columns;
    private final List<String> types;
    private final List<Object[]> rows;
    private final Duration took;
    private final long bytes;

    GuardedResult(List<String> columns, List<String> types, List<Object[]> rows, Duration took, long bytes) {
        this.columns = List.copyOf(columns);
        this.types = List.copyOf(types);
        this.rows = List.copyOf(rows);
        this.took = took;
        this.bytes = bytes;
    }

    public List<String> getColumns() {
        return columns;
    }

    /** The SQL type of each column, as DuckDB names it. */
    public List<String> getColumnTypes() {
        return types;
    }

    public List<Object[]> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rows.size();
    }

    public Duration getTook() {
        return took;
    }

    /** Roughly how many bytes the values hold, as the byte cap counted them. */
    public long getBytes() {
        return bytes;
    }

    /**
     * The result as a table, for something that reads text - one header row, then the rows,
     * columns separated by two spaces and nulls written as an empty cell.
     */
    public String toText() {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
        }
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], text(row[i]).length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            out.append(i == 0 ? "" : "  ").append(pad(columns.get(i), widths[i]));
        }
        for (Object[] row : rows) {
            out.append('\n');
            for (int i = 0; i < row.length; i++) {
                out.append(i == 0 ? "" : "  ").append(pad(text(row[i]), widths[i]));
            }
        }
        return out.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String pad(String value, int width) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    @Override
    public String toString() {
        return "GuardedResult[" + rows.size() + " rows, " + columns.size() + " columns, " + took.toMillis()
                + " ms]";
    }
}
