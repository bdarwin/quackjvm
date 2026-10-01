/*
 * Values identified by several fields, stored sparsely, and read back grouped or pivoted by any of
 * those fields.
 *
 * Each record here carries about a thousand values. A value is identified by four fields - three
 * that say which series it belongs to, and one point along an axis - plus the unit it is expressed
 * in. Across two thousand records that is two million values and nine thousand distinct keys, and
 * no record has more than a fraction of them.
 *
 * The point of the shape: any field can be the rows, any field can be the columns. A table with a
 * column per key would have nine thousand columns; this has as many as the field you choose.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasures.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;

public class CoreMeasures {

    static final int RECORDS = 2_000;
    static final String[] POINTS = {"1d", "1w", "1m", "3m", "6m", "1y", "2y", "5y", "10y", "30y"};

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("memory_limit", "2GB");
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", properties)) {

            MeasureTable measures = MeasureTable.named("measure")
                    .fields("group", "sub", "kind", "point", "unit")
                    .unit("unit")
                    // Without this the columns come out as 10y, 1d, 1m, 1w, 1y, 2y, 30y, 3m...
                    .order("point", POINTS)
                    .build();
            measures.create(connection);

            long started = System.nanoTime();
            long values = load(connection, measures);
            System.out.printf("%,d values over %,d records and %,d distinct keys, written in %.1f s%n%n",
                    values, measures.recordCount(connection), measures.keyCount(connection),
                    (System.nanoTime() - started) / 1e9);

            // A table of your own, of the kind the query joins in to convert units.
            Sql.execute(connection, "CREATE TABLE rate (unit VARCHAR, to_unit VARCHAR, factor DOUBLE)");
            Sql.execute(connection, "INSERT INTO rate VALUES ('U1','U1',1.0), ('U2','U1',1.25), ('U3','U1',0.8)");

            show(connection, "One record, its series as rows and the axis as columns",
                    measures.query().records(7).rows("group", "sub", "kind").columns("point").where("unit", "U1"), 6);

            show(connection, "Every record: totals by group, the axis as columns",
                    measures.query().rows("group").columns("point").where("unit", "U1"), 6);

            show(connection, "The same data, turned round: the axis as rows, sub-groups as columns",
                    measures.query().rows("point").columns("sub").where("unit", "U1"), 6);

            show(connection, "One group only, by kind",
                    measures.query().rows("kind").columns("point").where("unit", "U1").where("group", "g3"), 6);

            show(connection, "All three units, converted into one and added up",
                    measures.query().rows("group").columns("point").convertTo("U1", "rate"), 6);

            show(connection, "Or kept apart, by putting the unit in the rows",
                    measures.query().rows("group", "unit").columns("point"), 6);

            System.out.println("Adding up different units without converting them:");
            try {
                measures.query().rows("group").columns("point").run(connection.duplicate());
            }
            catch (IllegalStateException refused) {
                System.out.println("   refused: " + refused.getMessage());
            }

            exportAndReadBack(connection, measures);

            System.out.println("The SQL behind the second one, to join your own tables to:");
            System.out.println("   " + measures.query().rows("group").columns("point").where("unit", "U1")
                    .sql(connection).replace(", sum", ",\n          sum").replace(" FROM totals", "\n   FROM totals"));
        }
    }

    /** Archiving the measure to a file, asking the file a question, and reading it back elsewhere. */
    static void exportAndReadBack(DuckDBConnection connection, MeasureTable measures) throws Exception {
        java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("measure-export");
        try {
            long started = System.nanoTime();
            // One self-contained Parquet file: a row per value with its fields beside it, and the
            // measure's definition in the file's metadata. The rows leave the live table with it.
            String file = measures.archive(connection, directory.toString(), MeasureTable.DEFAULT_PART);
            long bytes = java.nio.file.Files.size(java.nio.file.Path.of(file));
            System.out.printf("Archived to Parquet in %.0f ms, %,d KB - the values with their fields,"
                    + " and what the measure is%n", (System.nanoTime() - started) / 1e6, bytes / 1024);

            started = System.nanoTime();
            long rows = measures.query().from(file).rows("group").columns("point").where("unit", "U1")
                    .run(connection.duplicate()).count();
            System.out.printf("The same question asked of the file, without reading it back: %.0f ms, %d rows%n",
                    (System.nanoTime() - started) / 1e6, rows);

            try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                // Nothing here knows the measure: its definition is read out of the file.
                MeasureTable there = MeasureTable.describedBy(elsewhere, file);
                started = System.nanoTime();
                long values = there.restore(elsewhere, java.util.List.of(file));
                System.out.printf("Read back into an empty database: %,d values in %.0f ms, %,d keys%n%n",
                        values, (System.nanoTime() - started) / 1e6, there.keyCount(elsewhere));
            }
        }
        finally {
            try (var walk = java.nio.file.Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Two thousand records, each with about a thousand values, written two hundred at a time. */
    static long load(DuckDBConnection connection, MeasureTable measures) {
        long written = 0;
        for (int from = 0; from < RECORDS; from += 200) {
            MeasureBatch.Builder batch = measures.batch();
            for (int record = from; record < from + 200; record++) {
                batch.record(record);
                for (int series = 0; series < 100; series++) {
                    int which = (record * 7 + series * 13) % 300;
                    String group = "g" + which % 20;
                    String sub = "s" + which / 20 % 5;
                    String kind = "k" + series % 3;
                    String unit = "U" + (1 + (series + which) % 3);
                    for (String point : POINTS) {
                        batch.put((record * 31 + series * 17 + point.length()) % 1000 / 10.0,
                                group, sub, kind, point, unit);
                        written++;
                    }
                }
            }
            measures.append(connection, batch.build());
        }
        return written;
    }

    static void show(DuckDBConnection connection, String title, MeasureQuery query, int maxRows) throws Exception {
        long started = System.nanoTime();
        Rows rows = query.run(connection.duplicate());
        StringBuilder out = new StringBuilder();
        int[] seen = {0};
        rows.forEachRow((SqlRow row) -> {
            List<String> columns = row.getColumnNames();
            if (seen[0] == 0) {
                for (String column : columns) {
                    out.append(String.format("%10s", shorten(column)));
                }
                out.append('\n');
            }
            if (seen[0] < maxRows) {
                for (String column : columns) {
                    Object value = row.get(column);
                    out.append(String.format("%10s", value instanceof Double d ? String.format("%.1f", d)
                            : String.valueOf(value)));
                }
                out.append('\n');
            }
            seen[0]++;
        });
        System.out.printf("%s   (%d rows, %.0f ms)%n", title, seen[0], (System.nanoTime() - started) / 1e6);
        out.toString().lines().forEach(line -> System.out.println("   " + line));
        if (seen[0] > maxRows) {
            System.out.println("   ... " + (seen[0] - maxRows) + " more rows");
        }
        System.out.println();
    }

    static String shorten(String column) {
        return column.length() <= 9 ? column : column.substring(0, 8) + "…";
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * 2,000,000 values over 2,000 records and 9,000 distinct keys, written in 0.9 s
 *
 * One record, its series as rows and the axis as columns   (33 rows, 12 ms)
 *         group       sub      kind        1d        1w        1m        3m        6m        1y        2y        5y       10y       30y
 *            g0        s1        k1      35.8      35.8      35.8      35.8      35.8      35.8      35.8      35.8      35.9      35.9
 *            g0        s2        k1      33.8      33.8      33.8      33.8      33.8      33.8      33.8      33.8      33.9      33.9
 *            g1        s0        k1      28.7      28.7      28.7      28.7      28.7      28.7      28.7      28.7      28.8      28.8
 *            g1        s4        k1      30.7      30.7      30.7      30.7      30.7      30.7      30.7      30.7      30.8      30.8
 *           g10        s0        k1      86.8      86.8      86.8      86.8      86.8      86.8      86.8      86.8      86.9      86.9
 *           g10        s1        k1      84.8      84.8      84.8      84.8      84.8      84.8      84.8      84.8      84.9      84.9
 *    ... 27 more rows
 *
 * Every record: totals by group, the axis as columns   (20 rows, 8 ms)
 *         group        1d        1w        1m        3m        6m        1y        2y        5y       10y       30y
 *            g0  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166946.0  166946.0
 *            g1  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  165904.0  165904.0
 *           g10  166124.0  166124.0  166124.0  166124.0  166124.0  166124.0  166124.0  166124.0  166457.0  166457.0
 *           g11  166549.7  166549.7  166549.7  166549.7  166549.7  166549.7  166549.7  166549.7  166883.0  166883.0
 *           g12  166788.0  166788.0  166788.0  166788.0  166788.0  166788.0  166788.0  166788.0  167121.6  167121.6
 *           g13  167338.9  167338.9  167338.9  167338.9  167338.9  167338.9  167338.9  167338.9  166272.8  166272.8
 *    ... 14 more rows
 *
 * The same data, turned round: the axis as rows, sub-groups as columns   (10 rows, 6 ms)
 *         point        s0        s1        s2        s3        s4
 *            1d  666093.0  666051.8  665872.7  665903.3  666144.9
 *            1w  666093.0  666051.8  665872.7  665903.3  666144.9
 *            1m  666093.0  666051.8  665872.7  665903.3  666144.9
 *            3m  666093.0  666051.8  665872.7  665903.3  666144.9
 *            6m  666093.0  666051.8  665872.7  665903.3  666144.9
 *            1y  666093.0  666051.8  665872.7  665903.3  666144.9
 *    ... 4 more rows
 *
 * One group only, by kind   (3 rows, 6 ms)
 *          kind        1d        1w        1m        3m        6m        1y        2y        5y       10y       30y
 *            k0   55268.9   55268.9   55268.9   55268.9   55268.9   55268.9   55268.9   55268.9   55382.2   55382.2
 *            k1   56870.0   56870.0   56870.0   56870.0   56870.0   56870.0   56870.0   56870.0   56980.0   56980.0
 *            k2   54110.2   54110.2   54110.2   54110.2   54110.2   54110.2   54110.2   54110.2   54220.0   54220.0
 *
 * All three units, converted into one and added up   (20 rows, 8 ms)
 *         group        1d        1w        1m        3m        6m        1y        2y        5y       10y       30y
 *            g0  508423.8  508423.8  508423.8  508423.8  508423.8  508423.8  508423.8  508423.8  509440.9  509440.9
 *            g1  509456.6  509456.6  509456.6  509456.6  509456.6  509456.6  509456.6  509456.6  506408.6  506408.6
 *           g10  506290.6  506290.6  506290.6  506290.6  506290.6  506290.6  506290.6  506290.6  507307.1  507307.1
 *           g11  507273.1  507273.1  507273.1  507273.1  507273.1  507273.1  507273.1  507273.1  508289.4  508289.5
 *           g12  508302.4  508302.4  508302.4  508302.4  508302.4  508302.4  508302.4  508302.4  509318.7  509318.7
 *           g13  509298.6  509298.6  509298.6  509298.6  509298.6  509298.6  509298.6  509298.6  506249.8  506249.8
 *    ... 14 more rows
 *
 * Or kept apart, by putting the unit in the rows   (60 rows, 8 ms)
 *         group      unit        1d        1w        1m        3m        6m        1y        2y        5y       10y       30y
 *            g0        U1  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166612.0  166946.0  166946.0
 *            g0        U2  166892.0  166892.0  166892.0  166892.0  166892.0  166892.0  166892.0  166892.0  167226.0  167226.0
 *            g0        U3  166496.0  166496.0  166496.0  166496.0  166496.0  166496.0  166496.0  166496.0  166828.0  166828.0
 *            g1        U1  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  166970.3  165904.0  165904.0
 *            g1        U2  167250.0  167250.0  167250.0  167250.0  167250.0  167250.0  167250.0  167250.0  166284.0  166284.0
 *            g1        U3  166779.7  166779.7  166779.7  166779.7  166779.7  166779.7  166779.7  166779.7  165812.0  165812.0
 *    ... 54 more rows
 *
 * Adding up different units without converting them:
 *    refused: This would add up values in different units, which is never right. Put 'unit' in rows(...) or columns(...), narrow to one with where("unit", ...), or convertTo(unit, rates).
 * Archived to Parquet in 290 ms, 5,857 KB - the values with their fields, and what the measure is
 * The same question asked of the file, without reading it back: 14 ms, 20 rows
 * Read back into an empty database: 2,000,000 values in 394 ms, 9,000 keys
 *
 * The SQL behind the second one, to join your own tables to:
 *    WITH perKey AS (SELECT key_id,
 *           sum(value) AS total FROM "measure_value" WHERE key_id IN (SELECT id FROM "measure_key" WHERE "unit" IN ('U1')) GROUP BY key_id), totals AS (SELECT d."group", d."sub", d."kind", d."point", d."unit", t.total FROM perKey t JOIN "measure_key" d ON d.id = t.key_id) SELECT k."group",
 *           sum(k.total) FILTER (WHERE k."point" = '1d') AS "1d",
 *           sum(k.total) FILTER (WHERE k."point" = '1w') AS "1w",
 *           sum(k.total) FILTER (WHERE k."point" = '1m') AS "1m",
 *           sum(k.total) FILTER (WHERE k."point" = '3m') AS "3m",
 *           sum(k.total) FILTER (WHERE k."point" = '6m') AS "6m",
 *           sum(k.total) FILTER (WHERE k."point" = '1y') AS "1y",
 *           sum(k.total) FILTER (WHERE k."point" = '2y') AS "2y",
 *           sum(k.total) FILTER (WHERE k."point" = '5y') AS "5y",
 *           sum(k.total) FILTER (WHERE k."point" = '10y') AS "10y",
 *           sum(k.total) FILTER (WHERE k."point" = '30y') AS "30y"
 *    FROM totals k GROUP BY k."group" ORDER BY k."group"
 *
 * The archive is one self-contained file: every row carries its key fields, and the measure's
 * definition travels in the file's metadata, which is why a database that has never heard of the
 * measure can read it back. That costs more than the earlier two-file export did - 5,857 KB against
 * 1,589, 290 ms against 33 - and buys files anything can read, where they lie, with nothing else in
 * hand.
 */
