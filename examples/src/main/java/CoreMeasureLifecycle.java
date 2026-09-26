/*
 * The whole life of a measure: keep adding, archive a day to one file, put that file in a store of
 * your own, then - on request - ask the files directly or read days back, even after the measure has
 * grown a field.
 *
 *   1. add, under a part label, saying who wrote it
 *   2. archive a part: one self-contained Parquet file, those rows gone from the live tables
 *   3. move the file into a store of your own, and drop it from disk
 *   4. ask the files directly, one day or several, without reading anything back
 *   5. restore days into a database that knows nothing about the measure
 *   6. grow a field, and read the older files anyway
 *   7. write only what changed
 *   8. let the measure archive itself when it grows past a size
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasureLifecycle.java
 */

import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class CoreMeasureLifecycle {

    static final String[] POINTS = {"1d", "1m", "1y", "5y", "10y", "30y"};
    static final String[] DAYS = {"2026-09-21", "2026-09-22", "2026-09-23"};
    /** Standing in for a store of your own: a blob per archived part. */
    static final Map<String, byte[]> STORE = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("measure-lifecycle");
        Properties properties = new Properties();
        properties.setProperty("memory_limit", "2GB");
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection(
                "jdbc:duckdb:" + work.resolve("live.duckdb"), properties)) {

            MeasureTable measures = MeasureTable.named("measure")
                    .fields("group", "sub", "point", "unit")
                    .unit("unit")
                    .order("point", POINTS)
                    .writtenBy("svc-a")
                    .build();
            measures.create(connection);

            // ---- 1. add, a day at a time ----
            for (String day : DAYS) {
                measures.append(connection, dayOf(measures, day), day);
            }
            System.out.printf("1. Added %,d values over %,d records; parts now:%n", measures.valueCount(connection),
                    measures.recordCount(connection));
            for (MeasureTable.Part part : measures.parts(connection)) {
                System.out.printf("      %s  %,7d values%n", part.name(), part.values());
            }
            show(connection, "   totals by group, the axis as columns",
                    measures.query().rows("group").columns("point").where("unit", "U1"), 3);

            // ---- 2. archive the oldest day ----
            String first = measures.archive(connection, work.resolve("archive").toString(), DAYS[0]);
            System.out.printf("2. Archived %s to %s (%,d KB), leaving %,d values live%n",
                    DAYS[0], work.relativize(Path.of(first)), Files.size(Path.of(first)) / 1024,
                    measures.valueCount(connection));
            System.out.println("      the file says what it is: "
                    + MeasureTable.describedBy(connection, first).getFields() + ", written by "
                    + MeasureTable.describedBy(connection, first).getWrittenBy());

            // ---- 3. into a store of your own ----
            STORE.put(DAYS[0], Files.readAllBytes(Path.of(first)));
            Files.delete(Path.of(first));
            System.out.printf("3. Moved it into a store of my own: %d blob, %,d KB, and off the disk%n%n",
                    STORE.size(), STORE.get(DAYS[0]).length / 1024);

            // ---- 4. ask the files, without reading them back ----
            String fetched = fetch(work, DAYS[0]);
            String second = measures.archive(connection, work.resolve("archive").toString(), DAYS[1]);
            show(connection, "4. Two archived days, asked where they lie",
                    measures.query().from(fetched, second).rows("group").columns("point").where("unit", "U1"), 3);

            // ---- 5. read days back, into a database that knows nothing ----
            try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                long started = System.nanoTime();
                MeasureTable restored = MeasureTable.restore(elsewhere, fetched, second);
                System.out.printf("5. Restored two days into an empty database in %.0f ms: %,d values, %,d keys,"
                                + " fields %s%n", (System.nanoTime() - started) / 1e6, restored.valueCount(elsewhere),
                        restored.keyCount(elsewhere), restored.getFields());
                show(elsewhere, "   the same question, answered there",
                        restored.query().rows("group").columns("point").where("unit", "U1"), 3);
            }

            // ---- 6. the measure grows a field ----
            MeasureTable grown = MeasureTable.named("measure")
                    .fields("group", "sub", "point", "unit")
                    .field("kind", "n/a")
                    .unit("unit")
                    .order("point", POINTS)
                    .writtenBy("svc-a")
                    .build();
            grown.migrate(connection);
            MeasureBatch.Builder batch = grown.batch();
            batch.record(900);
            for (String point : POINTS) {
                batch.put(7.0, "g0", "s0", point, "U1", "k1");
            }
            grown.append(connection, batch.build(), "2026-09-24");
            System.out.println("6. Added a 'kind' field; values written before it read as \"n/a\"");
            show(connection, "   by kind, including the day archived before the field existed",
                    grown.query().from(fetched, grown.archive(connection, work.resolve("archive").toString(),
                            "2026-09-24")).rows("kind").columns("point").where("unit", "U1"), 3);

            // ---- 7. only what changed ----
            long before = grown.valueCount(connection);
            grown.appendChanges(connection, dayOf(grown, DAYS[2]), "2026-09-25");
            long unchanged = grown.valueCount(connection) - before;
            MeasureBatch.Builder moved = grown.batch();
            moved.record(0).put(99.0, "g0", "s0", "1d", "U1", "n/a");
            grown.appendChanges(connection, moved.build(), "2026-09-25");
            System.out.printf("7. appendChanges: re-publishing a day unchanged wrote %,d values;"
                            + " one changed value wrote %,d%n%n",
                    unchanged, grown.valueCount(connection) - before - unchanged);

            // ---- 8. archiving itself by size ----
            archiveBySize(work);
        }
        finally {
            try (var walk = Files.walk(work)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** A day's values: 200 records, each with a few series across the axis. */
    static MeasureBatch dayOf(MeasureTable measures, String day) {
        MeasureBatch.Builder batch = measures.batch();
        int extra = measures.getFields().size() - 4;
        for (int record = 0; record < 200; record++) {
            batch.record(record);
            for (int series = 0; series < 5; series++) {
                int which = (record * 7 + series * 13) % 40;
                for (String point : POINTS) {
                    String[] key = extra == 0
                            ? new String[]{"g" + which % 8, "s" + which % 5, point, "U" + (1 + which % 2)}
                            // The same kind that values written before the field existed read as,
                            // so a re-publish is genuinely unchanged.
                            : new String[]{"g" + which % 8, "s" + which % 5, point, "U" + (1 + which % 2), "n/a"};
                    batch.put((record + series + day.hashCode() % 7 + point.length()) % 100 / 10.0, key);
                }
            }
        }
        return batch.build();
    }

    /** Pulling a blob back out of the store and putting it where DuckDB can read it. */
    static String fetch(Path work, String part) throws Exception {
        Path file = work.resolve("fetched").resolve(part + ".parquet");
        Files.createDirectories(file.getParent());
        Files.write(file, STORE.get(part));
        return file.toString();
    }

    /** A measure told to keep itself under a size, handing each archived part to the store. */
    static void archiveBySize(Path work) throws Exception {
        Path file = work.resolve("sized.duckdb");
        List<String> archived = new ArrayList<>();
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + file)) {
            MeasureTable measures = MeasureTable.named("sized")
                    .fields("group", "point")
                    .writtenBy("svc-a")
                    .archiveWhenLargerThan(2 * 1024 * 1024)
                    .archiveTo(work.resolve("archive").toString())   // or "s3://bucket/measures"
                    .onArchive((part, where) -> {
                        STORE.put("sized-" + part, Files.readAllBytes(Path.of(where)));
                        Files.delete(Path.of(where));
                        archived.add(part);
                    })
                    .build();
            measures.create(connection);
            for (int day = 20; day <= 26; day++) {
                MeasureBatch.Builder batch = measures.batch();
                for (int record = 0; record < 60; record++) {
                    batch.record(day * 1000L + record);
                    for (int key = 0; key < 1_000; key++) {
                        batch.put(record + key / 10.0, "g" + key % 50, "p" + key);
                    }
                }
                measures.append(connection, batch.build(), "2026-09-" + day);
            }
            System.out.printf("8. Told to stay under %,d KB, it archived %s on its own, keeping %s live (%,d KB)%n",
                    2048, archived, measures.parts(connection).stream().map(MeasureTable.Part::name).toList(),
                    measures.sizeOnDisk(connection) / 1024);
            System.out.printf("      the store now holds %d blobs: %s%n", STORE.size(), STORE.keySet());
        }
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
                    out.append(String.format("%9s", column));
                }
                out.append('\n');
            }
            if (seen[0] < maxRows) {
                for (String column : columns) {
                    Object value = row.get(column);
                    out.append(String.format("%9s", value instanceof Double d ? String.format("%.1f", d)
                            : String.valueOf(value)));
                }
                out.append('\n');
            }
            seen[0]++;
        });
        System.out.printf("%s   (%d rows, %.0f ms)%n", title, seen[0], (System.nanoTime() - started) / 1e6);
        out.toString().lines().forEach(line -> System.out.println("      " + line));
        if (seen[0] > maxRows) {
            System.out.println("      ... " + (seen[0] - maxRows) + " more rows");
        }
        System.out.println();
    }

    @SuppressWarnings("unused")
    static List<String> rowsOf(DuckDBConnection connection, String sql) throws Exception {
        List<String> found = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }
}

/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 1. Added 18,000 values over 200 records; parts now:
 *       2026-09-21    6,000 values
 *       2026-09-22    6,000 values
 *       2026-09-23    6,000 values
 *    totals by group, the axis as columns   (4 rows, 8 ms)
 *           group       1d       1m       1y       5y      10y      30y
 *              g0   1852.5   1852.5   1852.5   1852.5   1860.0   1860.0
 *              g2   1847.5   1847.5   1847.5   1847.5   1865.0   1865.0
 *              g4   1852.5   1852.5   1852.5   1852.5   1860.0   1860.0
 *       ... 1 more rows
 *
 * 2. Archived 2026-09-21 to archive/measure=measure/part=2026-09-21/svc-a-1790408720381.parquet (14 KB), leaving 12,000 values live
 *       the file says what it is: [group, sub, point, unit], written by svc-a
 * 3. Moved it into a store of my own: 1 blob, 14 KB, and off the disk
 *
 * 4. Two archived days, asked where they lie   (4 rows, 6 ms)
 *           group       1d       1m       1y       5y      10y      30y
 *              g0   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *              g2   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *              g4   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *       ... 1 more rows
 *
 * 5. Restored two days into an empty database in 17 ms: 12,000 values, 240 keys, fields [group, sub, point, unit]
 *    the same question, answered there   (4 rows, 4 ms)
 *           group       1d       1m       1y       5y      10y      30y
 *              g0   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *              g2   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *              g4   1225.0   1225.0   1225.0   1225.0   1250.0   1250.0
 *       ... 1 more rows
 *
 * 6. Added a 'kind' field; values written before it read as "n/a"
 *    by kind, including the day archived before the field existed   (2 rows, 6 ms)
 *            kind       1d       1m       1y       5y      10y      30y
 *              k1      7.0      7.0      7.0      7.0      7.0      7.0
 *             n/a   2450.0   2450.0   2450.0   2450.0   2500.0   2500.0
 *
 * 7. appendChanges: re-publishing a day unchanged wrote 0 values; one changed value wrote 1
 *
 * 8. Told to stay under 2,048 KB, it archived [2026-09-20, 2026-09-21] on its own, keeping [2026-09-22, 2026-09-23, 2026-09-24, 2026-09-25, 2026-09-26] live (1,792 KB)
 *       the store now holds 3 blobs: [2026-09-21, sized-2026-09-20, sized-2026-09-21]
 */
