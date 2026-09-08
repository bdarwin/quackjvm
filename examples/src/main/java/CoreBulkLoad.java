/*
 * Bulk-load a million rows into a DuckDB file and report the time and the resulting file size.
 * No CQEngine involved.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreBulkLoad.java [rowCount]
 */

import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.SqlQuery;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.io.File;
import java.sql.DriverManager;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.stream.Stream;

public class CoreBulkLoad {

    public record Trade(long tradeId, String symbol, int quantity, double price, boolean buy) {
    }

    private static final String[] SYMBOLS = {"AAPL", "MSFT", "NVDA", "AMZN", "GOOG", "META", "TSLA", "AVGO"};

    public static void main(String[] args) throws Exception {
        int rowCount = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;

        ColumnarLayout<Trade> layout = ColumnarLayout.ofRecord(Trade.class);
        List<ColumnDef> columns = layout.getColumns().stream()
                .map(column -> new ColumnDef(column.getName(), column.getType()))
                .toList();

        File file = File.createTempFile("quackjvm-bulk-", ".duckdb");
        // DuckDB refuses to open a file which already exists but is not a database, and
        // createTempFile has just made an empty one.
        if (!file.delete()) {
            throw new IllegalStateException("Could not clear " + file);
        }
        file.deleteOnExit();

        Properties properties = new Properties();
        // Worth capping: without it DuckDB will happily use most of the machine's memory. The
        // writer stages rows in bounded chunks, so a modest limit is enough for any batch size.
        properties.setProperty("memory_limit", "512MB");

        long written;
        long elapsedNanos;
        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + file.getAbsolutePath(),
                             properties)) {

            // No primary key: a bulk load into a fresh table does not need the uniqueness check,
            // and building the index costs both time and file space. The writer's orReplace flag
            // has to agree - an INSERT OR REPLACE needs a key to conflict on.
            TableWriter writer = new TableWriter("trade", columns, false,
                    TableWriter.DEFAULT_APPENDER_THRESHOLD);
            writer.createTable(connection, false);

            long startedAt = System.nanoTime();
            // A lazy iterator, so the million objects never exist at once. Any batch larger than
            // the appender threshold is streamed through a temporary staging table.
            written = writer.write(connection, rows(layout, rowCount), false).rowsWritten;
            elapsedNanos = System.nanoTime() - startedAt;

            System.out.printf("Wrote %,d rows in %.2f s (%.2f us per row)%n",
                    written, elapsedNanos / 1e9, elapsedNanos / 1000.0 / rowCount);
            System.out.printf("Rows in the table: %,d%n", writer.count(connection));

            try (Stream<SqlRow> summary = SqlQuery.stream(connection.duplicate(),
                    "SELECT symbol, count(*) AS trades, round(sum(quantity * price), 2) AS notional "
                            + "FROM trade GROUP BY symbol ORDER BY notional DESC LIMIT 3")) {
                System.out.println("Top three symbols by notional:");
                summary.forEach(row -> System.out.printf("  %-6s %,10d trades  %,18.2f%n",
                        row.getString("symbol"), row.getLong("trades"), row.getDouble("notional")));
            }
        }

        // The file is only fully written once the database is closed, so its size is measured here.
        System.out.printf("File: %s%n", file);
        System.out.printf("File size: %.1f MB (%.1f bytes per row)%n",
                file.length() / 1024.0 / 1024.0, (double) file.length() / rowCount);
    }

    /** Generates trades on demand rather than building a list of them. */
    private static Iterator<Object[]> rows(ColumnarLayout<Trade> layout, int rowCount) {
        return new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < rowCount;
            }

            @Override
            public Object[] next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int i = index++;
                Trade trade = new Trade(i, SYMBOLS[i % SYMBOLS.length], 10 + (i % 500),
                        50.0 + (i % 10_000) / 100.0, (i & 1) == 0);
                Object[] row = new Object[layout.getColumns().size()];
                for (int column = 0; column < row.length; column++) {
                    row[column] = layout.getColumns().get(column).getValue(trade);
                }
                return row;
            }
        };
    }
}

/*
 * Output (Apple Silicon, JDK 25, default 1,000,000 rows):
 *
 * Wrote 1,000,000 rows in 0.62 s (0.62 us per row)
 * Rows in the table: 1,000,000
 * Top three symbols by notional:
 *   AVGO      125,000 trades    3,289,518,750.00
 *   AMZN      125,000 trades    3,288,213,750.00
 *   TSLA      125,000 trades    3,276,690,000.00
 * File: /var/folders/.../quackjvm-bulk-1993139296406353480.duckdb
 * File size: 9.8 MB (10.2 bytes per row)
 */
