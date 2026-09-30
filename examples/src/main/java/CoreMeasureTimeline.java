/*
 * A measure over time: what it held at any point, files for whoever reads Parquet, a history cut back
 * once it is safe to, and a live database rebuilt from the history when it has to be.
 *
 * Two days of publishing, then the four things that happen to the history afterwards - export,
 * compact, prune, rebuild - and what each one does to the answers.
 *
 * The one rule behind all of it: the state at a point in time is the sum of the contributions from the
 * last full set at or before that point. A full set is a baseline - it contributes the values
 * themselves - which is what makes everything before one safe to throw away.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasureTimeline.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public class CoreMeasureTimeline {

    static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");
    static final Instant TUESDAY = Instant.parse("2026-09-29T09:00:00Z");

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("timeline");
        Path lake = directory.resolve("lake");
        try (DuckDBConnection live = (DuckDBConnection) DriverManager.getConnection(
                "jdbc:duckdb:" + directory.resolve("live.duckdb"))) {
            MeasureTable exposure = MeasureTable.named("exposure")
                    .fields("group", "point")
                    .order("point", "5y", "10y", "30y")
                    .writtenBy("svc_a")
                    .build();
            exposure.create(live);
            MeasureRefresh.createLedger(live);
            MeasureHistory history = MeasureHistory.at(directory.resolve("history.duckdb").toString());
            history.create(live, exposure);

            System.out.println("Monday");
            System.out.println("------");
            // Everything this publisher has. A full set is a baseline.
            MeasureRefresh.at(MONDAY, "mon-09:00").full(exposure, exposure.batch()
                    .record(3).put(10, "g1", "5y").put(20, "g1", "10y")
                    .record(4).put(7, "g2", "5y")
                    .build()).commit(live);
            history.ship(live, exposure);
            // Exported as the day goes on, not at the end of it, so downstream is never waiting.
            history.export(live, exposure, lake.toString());

            // Through the day, only what moved.
            MeasureRefresh.at(MONDAY.plusSeconds(3600), "mon-10:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(20, "g1", "10y").put(5, "g1", "30y")
                    .build()).commit(live);
            MeasureRefresh.at(MONDAY.plusSeconds(7200), "mon-11:00").remove(exposure, 4).commit(live);
            history.ship(live, exposure);
            // Exported twice during the day, so Monday ends up as two files - which is the normal
            // shape of it, since downstream wants files as the day goes on.
            history.export(live, exposure, lake.toString());

            show(live, "at 09:00", exposure.query().asOf(MONDAY, history));
            show(live, "at 10:00", exposure.query().asOf(MONDAY.plusSeconds(3600), history));
            show(live, "at 11:00", exposure.query().asOf(MONDAY.plusSeconds(7200), history));
            show(live, "at 09:30", exposure.query().asOf(MONDAY.plusSeconds(1800), history));

            System.out.println("\nTuesday - a new full set, and one increment on top");
            System.out.println("--------------------------------------------------");
            MeasureRefresh.at(TUESDAY, "tue-09:00").full(exposure, exposure.batch()
                    .record(3).put(1, "g1", "5y")
                    .record(9).put(4, "g3", "10y")
                    .build()).commit(live);
            MeasureRefresh.at(TUESDAY.plusSeconds(3600), "tue-10:00").increment(exposure, exposure.batch()
                    .record(9).put(6, "g3", "10y").build()).commit(live);
            history.ship(live, exposure);

            show(live, "at 09:00", exposure.query().asOf(TUESDAY, history));
            show(live, "at 10:00", exposure.query().asOf(TUESDAY.plusSeconds(3600), history));
            show(live, "Monday still", exposure.query().asOf(MONDAY.plusSeconds(7200), history));

            System.out.println("\nA timeline only moves forward");
            System.out.println("----------------------------");
            try {
                MeasureRefresh.at(MONDAY.plusSeconds(10800), "mon-12:00").increment(exposure, exposure.batch()
                        .record(3).put(99, "g1", "5y").build()).commit(live);
            }
            catch (IllegalArgumentException refused) {
                System.out.println("   " + refused.getMessage());
            }

            System.out.println("\nExport - for whoever reads Parquet, a file per day of refresh");
            System.out.println("------------------------------------------------------------");
            MeasureHistory.Exported exported = history.export(live, exposure, lake.toString());
            System.out.printf("What was left to export: %,d contributions over %,d refreshes, in %d file%n",
                    exported.contributions(), exported.refreshes(), exported.files().size());
            for (String file : history.exportedFiles(live, exposure)) {
                System.out.println("   " + file.substring(lake.toString().length() + 1));
            }
            System.out.printf("Exporting again: %d files, %d contributions%n",
                    history.export(live, exposure, lake.toString()).files().size(),
                    history.export(live, exposure, lake.toString()).contributions());

            System.out.println("\nCompact - Monday is finished, so its files become one");
            System.out.println("----------------------------------------------------");
            Path monday = lake.resolve("measure=exposure").resolve("part=2026-09-28");
            System.out.printf("before: %d files, %,d rows%n", count(monday), rowsIn(live, monday));
            String compacted = history.compact(live, exposure, lake.toString(), "2026-09-28");
            System.out.printf("after:  %d file,  %,d rows, and it still says what the measure is: %s%n",
                    count(monday), rowsIn(live, monday),
                    compacted == null ? "(nothing to compact)" : MeasureTable.describedBy(live, compacted).getFields());

            System.out.println("\nPrune - the history drops what it has exported and no longer needs");
            System.out.println("-----------------------------------------------------------------");
            System.out.printf("before: %,d contributions in the history%n", history.contributionCount(live, exposure));
            long dropped = history.prune(live, exposure);
            System.out.printf("dropped %,d - everything before Tuesday's full set%n", dropped);
            System.out.printf("after:  %,d contributions, and Tuesday still reads the same:%n",
                    history.contributionCount(live, exposure));
            show(live, "at 10:00", exposure.query().asOf(TUESDAY.plusSeconds(3600), history));
            System.out.println("   Monday is gone from the history, but not from the files:");
            System.out.printf("   %,d rows for Monday, where they were exported%n", rowsIn(live, monday));
            try {
                show(live, "Monday now", exposure.query().asOf(MONDAY.plusSeconds(3600), history));
            }
            catch (IllegalStateException refused) {
                System.out.println("   asked of the history alone: " + refused.getMessage());
            }
            // The files carry the refresh each row belongs to, so they answer as of a point as well.
            show(live, "Monday from files", exposure.query().asOf(MONDAY.plusSeconds(3600), history)
                    .from(monday + "/*.parquet"));

            System.out.println("\nRebuild - the live database is a cache, and this is how it comes back");
            System.out.println("--------------------------------------------------------------------");
            show(live, "live now", exposure.query().rows("group", "point"));
            Sql.execute(live, "DELETE FROM " + Sql.quote(exposure.getValueTable()));
            Sql.execute(live, "DELETE FROM " + Sql.quote(exposure.getKeyTable()));
            System.out.printf("   thrown away - the live tables now hold %,d values%n", exposure.valueCount(live));
            long started = System.nanoTime();
            long values = history.rebuild(live, exposure);
            System.out.printf("   rebuilt %,d values from the history in %.1f ms%n", values,
                    (System.nanoTime() - started) / 1e6);
            show(live, "live again", exposure.query().rows("group", "point"));
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    static void show(DuckDBConnection connection, String what, MeasureQuery query) throws Exception {
        StringBuilder line = new StringBuilder("   " + what);
        while (line.length() < 24) {
            line.append(' ');
        }
        StringBuilder values = new StringBuilder();
        Rows rows = query.rows("group", "point").run(connection.duplicate());
        rows.forEachRow((SqlRow row) -> values.append(values.length() == 0 ? "" : ",  ")
                .append(row.get("group")).append(' ').append(row.get("point")).append('=')
                .append(row.get("total")));
        System.out.println(line + (values.length() == 0 ? "(nothing)" : values.toString()));
    }

    static long count(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".parquet")).count();
        }
    }

    static long rowsIn(Connection connection, Path directory) {
        return Sql.queryLong(connection, "SELECT count(*) FROM read_parquet('" + directory
                + "/*.parquet', union_by_name = true, hive_partitioning = false)", List.of());
    }
}

/*
 * What it printed:
 *
 * Monday
 * ------
 *    at 09:00             g1 5y=10.0,  g1 10y=20.0,  g2 5y=7.0
 *    at 10:00             g1 5y=12.0,  g1 10y=20.0,  g1 30y=5.0,  g2 5y=7.0
 *    at 11:00             g1 5y=12.0,  g1 10y=20.0,  g1 30y=5.0
 *    at 09:30             g1 5y=10.0,  g1 10y=20.0,  g2 5y=7.0
 *
 * Tuesday - a new full set, and one increment on top
 * --------------------------------------------------
 *    at 09:00             g1 5y=1.0,  g3 10y=4.0
 *    at 10:00             g1 5y=1.0,  g3 10y=6.0
 *    Monday still         g1 5y=12.0,  g1 10y=20.0,  g1 30y=5.0
 *
 * A timeline only moves forward
 * ----------------------------
 *    'svc_a' has already published exposure at 2026-09-29 10:00:00.0, and a timeline only moves forward - this refresh is dated 2026-09-28T12:00. Publish the correction at a later point: it is a change made now to what was said then, and reading as of a point in between must not see it.
 *
 * Export - for whoever reads Parquet, a file per day of refresh
 * ------------------------------------------------------------
 * What was left to export: 3 contributions over 2 refreshes, in 1 file
 *    measure=exposure/part=2026-09-28/svc_a-1790760968706.parquet
 *    measure=exposure/part=2026-09-28/svc_a-1790760968732.parquet
 *    measure=exposure/part=2026-09-29/svc_a-1790760968780.parquet
 * Exporting again: 0 files, 0 contributions
 *
 * Compact - Monday is finished, so its files become one
 * ----------------------------------------------------
 * before: 2 files, 6 rows
 * after:  1 file,  6 rows, and it still says what the measure is: [group, point]
 *
 * Prune - the history drops what it has exported and no longer needs
 * -----------------------------------------------------------------
 * before: 9 contributions in the history
 * dropped 6 - everything before Tuesday's full set
 * after:  3 contributions, and Tuesday still reads the same:
 *    at 10:00             g1 5y=1.0,  g3 10y=6.0
 *    Monday is gone from the history, but not from the files:
 *    6 rows for Monday, where they were exported
 *    asked of the history alone: exposure as of 2026-09-28T10:00:00Z has been pruned from the history - it is in the exported files: pass them to from(...) alongside asOf(...)
 *    Monday from files    g1 5y=12.0,  g1 10y=20.0,  g1 30y=5.0,  g2 5y=7.0
 *
 * Rebuild - the live database is a cache, and this is how it comes back
 * --------------------------------------------------------------------
 *    live now             g1 5y=1.0,  g3 10y=6.0
 *    thrown away - the live tables now hold 0 values
 *    rebuilt 2 values from the history in 4.6 ms
 *    live again           g1 5y=1.0,  g3 10y=6.0
 *
 * Four things worth noticing.
 *
 * A point between two refreshes reads as the earlier one - 09:30 is 09:00's answer - because a
 * refresh is a point and nothing happens between two of them.
 *
 * Tuesday's full set is a baseline: reading as of Tuesday says nothing of Monday's records, and
 * reading as of Monday still says what Monday said. That is what makes pruning safe.
 *
 * A timeline only moves forward. A contribution says how much a value moved from what the measure
 * held when it was written, so a refresh dated before the last one would be added to a state it was
 * never measured against. A correction is published at a later point - which is the truth of it.
 *
 * Pruning is refused as an answer, not silently wrong: asking the history alone for a pruned point
 * says so and says where to find it. The exported files carry the refresh each row belongs to, so
 * they answer as of a point exactly as the history does, and a refresh in both is counted once.
 */
