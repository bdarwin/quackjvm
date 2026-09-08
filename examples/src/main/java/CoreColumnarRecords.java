/*
 * Store Java records as typed DuckDB columns, then query them with SQL. No CQEngine involved.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreColumnarRecords.java
 */

import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.SqlQuery;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class CoreColumnarRecords {

    /**
     * A plain record. Every component type has to be one DuckDB understands - see DuckDBTypes -
     * and the layout checks that when it is built rather than at the first insert.
     */
    public record Reading(int sensorId, String site, double celsius, LocalDate day) {
    }

    public static void main(String[] args) throws Exception {
        List<Reading> readings = List.of(
                new Reading(1, "kitchen", 21.5, LocalDate.of(2026, 1, 3)),
                new Reading(2, "kitchen", 22.1, LocalDate.of(2026, 1, 4)),
                new Reading(3, "cellar", 11.9, LocalDate.of(2026, 1, 3)),
                new Reading(4, "cellar", 12.4, LocalDate.of(2026, 1, 4)),
                new Reading(5, "loft", 28.8, LocalDate.of(2026, 1, 4)));

        // Derives one column per record component, reading them through the record's accessors and
        // rebuilding objects through its canonical constructor.
        ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);

        List<ColumnDef> columns = new ArrayList<>();
        for (ColumnarLayout.Column<Reading, ?> column : layout.getColumns()) {
            columns.add(new ColumnDef(column.getName(), column.getType()));
        }
        System.out.println("Columns derived from the record: " + columns);

        // "jdbc:duckdb:" is an in-memory database; pass a path to persist it to a file.
        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

            // The first column is treated as the key column, which here is the record's first
            // component, sensorId.
            TableWriter writer = new TableWriter("reading", columns, true,
                    TableWriter.DEFAULT_APPENDER_THRESHOLD);
            writer.createTable(connection, true);

            List<Object[]> rows = new ArrayList<>(readings.size());
            for (Reading reading : readings) {
                Object[] row = new Object[columns.size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = layout.getColumns().get(i).getValue(reading);
                }
                rows.add(row);
            }
            TableWriter.WriteResult result = writer.write(connection, rows.iterator(), true);
            System.out.println("Rows written: " + result.rowsWritten + ", replaced: " + result.rowsReplaced);
            System.out.println("Rows in the table: " + writer.count(connection));

            // The data is now real typed columns, so any SQL works over it - here an aggregate
            // which never materialises a Reading at all. SqlQuery takes ownership of the connection
            // it is given and closes it with the stream, so each query gets a duplicate of the root
            // connection: duplicates share the same in-memory database.
            System.out.println();
            System.out.println("Average temperature per site:");
            try (Stream<SqlRow> rowStream = SqlQuery.stream(connection.duplicate(),
                    "SELECT site, count(*) AS readings, round(avg(celsius), 2) AS avgCelsius "
                            + "FROM reading GROUP BY site ORDER BY avgCelsius DESC")) {
                rowStream.forEach(row -> System.out.printf("  %-8s %d readings, avg %s%n",
                        row.getString("site"), row.getLong("readings"), row.get("avgCelsius")));
            }

            // Reading whole objects back is the same query plus the layout's row factory. The SqlRow
            // is one reusable view over the cursor, so a row kept beyond the iteration is copied
            // with toArray().
            System.out.println();
            System.out.println("Readings above 20 degrees, rebuilt as records:");
            try (Stream<SqlRow> rowStream = SqlQuery.stream(connection.duplicate(),
                    "SELECT sensorId, site, celsius, day FROM reading WHERE celsius > ? ORDER BY sensorId",
                    20.0)) {
                rowStream.map(row -> layout.createObject(row.toArray()))
                        .forEach(reading -> System.out.println("  " + reading));
            }
        }
    }
}

/*
 * Output:
 *
 * Columns derived from the record: ["sensorId" INTEGER, "site" VARCHAR, "celsius" DOUBLE, "day" DATE]
 * Rows written: 5, replaced: 0
 * Rows in the table: 5
 *
 * Average temperature per site:
 *   loft     1 readings, avg 28.8
 *   kitchen  2 readings, avg 21.8
 *   cellar   2 readings, avg 12.15
 *
 * Readings above 20 degrees, rebuilt as records:
 *   Reading[sensorId=1, site=kitchen, celsius=21.5, day=2026-01-03]
 *   Reading[sensorId=2, site=kitchen, celsius=22.1, day=2026-01-04]
 *   Reading[sensorId=5, site=loft, celsius=28.8, day=2026-01-04]
 */
