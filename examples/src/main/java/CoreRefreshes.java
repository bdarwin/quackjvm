/*
 * Publishing a measure over and over: full sets, increments, and removals, each written as what it
 * moved rather than as an overwrite.
 *
 * A refresh is a point on a publisher's timeline - a timestamp it chooses, and an id that makes it
 * itself. What it publishes is stored twice over: the measure holds the state that results, and a
 * contribution says how much each value moved. Nothing is overwritten and nothing is deleted, so
 * what was published can still be seen after it has gone, and the state is always the sum of the
 * contributions.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreRefreshes.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CoreRefreshes {

    static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable exposure = MeasureTable.named("exposure")
                    .fields("group", "point")
                    .order("point", "5y", "10y", "30y")
                    .writtenBy("svc_a")
                    .build();
            MeasureTable coverage = MeasureTable.named("coverage").fields("group").writtenBy("svc_a").build();
            exposure.create(connection);
            coverage.create(connection);

            System.out.println("A day on one publisher's timeline");
            System.out.println("---------------------------------");

            // Everything this publisher has: after this, the measure holds exactly these.
            MeasureRefresh.at(NINE, "run-09:00")
                    .full(exposure, exposure.batch()
                            .record(3).put(10, "g1", "5y").put(20, "g1", "10y")
                            .record(4).put(7, "g2", "5y")
                            .build())
                    .full(coverage, coverage.batch().record(3).put(1, "g1").build())
                    .commit(connection);
            show(connection, exposure, "09:00  full");

            // Only what moved. Record 3 came in, so it replaces itself entirely: 10y is gone
            // because this refresh does not mention it, and 30y has arrived. Record 4 is not in
            // the refresh at all, so it is left alone.
            MeasureRefresh.at(NINE.plusSeconds(900), "run-09:15")
                    .increment(exposure, exposure.batch()
                            .record(3).put(12, "g1", "5y").put(5, "g1", "30y")
                            .build())
                    .commit(connection);
            show(connection, exposure, "09:15  increment");

            // A removal cancels what the record held. It leaves the state, not the history.
            MeasureRefresh.at(NINE.plusSeconds(1800), "run-09:30")
                    .remove(exposure, 4)
                    .commit(connection);
            show(connection, exposure, "09:30  remove 4");

            System.out.println("\nWhat each refresh contributed");
            System.out.println("-----------------------------");
            print(connection, "SELECT r.refresh_id, r.kind, c.record_id, k.\"group\" || ' ' || k.point AS key,"
                    + " c.value FROM " + Sql.quote(exposure.getContributionTable()) + " c"
                    + " JOIN " + Sql.quote(exposure.getKeyTable()) + " k ON k.id = c.key_id"
                    + " JOIN " + Sql.quote(MeasureRefresh.LEDGER) + " r ON r.refresh_id = c.refresh_id"
                    + " AND r.measure = 'exposure' ORDER BY r.seq, c.record_id, key");

            System.out.printf("%nThe state is the sum of the contributions: %s%n", agrees(connection, exposure));

            // The same id again: the publisher missed the acknowledgement and published twice.
            boolean applied = MeasureRefresh.at(NINE.plusSeconds(1800), "run-09:30")
                    .remove(exposure, 4)
                    .commit(connection);
            System.out.printf("Committing run-09:30 a second time: applied=%b, still %d contributions%n",
                    applied, exposure.contributionCount(connection));
            System.out.printf("coverage was in the 09:00 refresh only, and still holds %d value%n",
                    coverage.valueCount(connection));

            atScale();
        }
    }

    static final int RECORDS = 100;
    static final int POINTS = 25;
    static final int REFRESHES = 200;

    /** What a hundred records of twenty-five values each cost, refresh after refresh, on disk. */
    static void atScale() throws Exception {
        System.out.println("\nAt some scale: 100 records of 25 values, published 200 times, on disk");
        System.out.println("--------------------------------------------------------------------");
        load(false);     // warm the JIT and the page cache before anything is reported
        load(true);
        System.out.println("replacing the records outright, keeping no history: " + load(false));
        System.out.println("the same as refreshes, with the contributions:      " + load(true));
    }

    /** Publishes the same 200 batches, either as plain replaces or as refreshes, and times them. */
    static String load(boolean asRefreshes) throws Exception {
        Path directory = Files.createTempDirectory("refreshes");
        Path file = directory.resolve("live.duckdb");
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + file)) {
            MeasureTable measures = MeasureTable.named("m").fields("group", "point").writtenBy("svc_a").build();
            measures.create(connection);

            List<Long> timings = new ArrayList<>();
            for (int refresh = 0; refresh < REFRESHES; refresh++) {
                var batch = measures.batch();
                for (int record = 0; record < RECORDS; record++) {
                    batch.record(record);
                    for (int point = 0; point < POINTS; point++) {
                        // A twentieth of the values move each time.
                        double value = point + record + (point % 20 == refresh % 20 ? refresh : 0);
                        batch.put(value, "g" + (record % 10), "p" + point);
                    }
                }
                var built = batch.build();
                long started = System.nanoTime();
                if (asRefreshes) {
                    MeasureRefresh.at(NINE.plusSeconds(refresh), "run-" + refresh)
                            .increment(measures, built)
                            .commit(connection);
                }
                else {
                    measures.replace(connection, built);
                }
                timings.add(System.nanoTime() - started);
            }
            timings.sort(Long::compare);
            return String.format("median %.2f ms, p99 %.2f ms, %,d values of state, %,d contributions,"
                            + " %,d KB on disk%s",
                    timings.get(timings.size() / 2) / 1e6, timings.get((int) (timings.size() * 0.99)) / 1e6,
                    measures.valueCount(connection), measures.contributionCount(connection),
                    Files.size(file) / 1024, asRefreshes ? ", sum agrees: " + agrees(connection, measures) : "");
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Whether every value equals the sum of its contributions - the invariant the design rests on. */
    static String agrees(java.sql.Connection connection, MeasureTable measure) {
        long disagreements = Sql.queryLong(connection, "SELECT count(*) FROM ("
                + " SELECT record_id, key_id, sum(value) AS total FROM " + Sql.quote(measure.getContributionTable())
                + " GROUP BY 1, 2 HAVING sum(value) <> 0) c FULL OUTER JOIN " + Sql.quote(measure.getValueTable())
                + " v ON v.record_id = c.record_id AND v.key_id = c.key_id"
                + " WHERE coalesce(c.total, 0) IS DISTINCT FROM coalesce(v.value, 0)", List.of());
        return disagreements == 0 ? "yes" : disagreements + " disagree";
    }

    static void show(java.sql.Connection connection, MeasureTable measure, String what) throws Exception {
        StringBuilder line = new StringBuilder(what);
        while (line.length() < 18) {
            line.append(' ');
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT v.record_id,"
                + " k.\"group\" || ' ' || k.point, v.value FROM " + Sql.quote(measure.getValueTable()) + " v JOIN "
                + Sql.quote(measure.getKeyTable()) + " k ON k.id = v.key_id ORDER BY 1, 2");
             ResultSet rows = statement.executeQuery()) {
            boolean any = false;
            while (rows.next()) {
                line.append(any ? ",  " : "").append(rows.getLong(1)).append(": ").append(rows.getString(2))
                        .append('=').append(rows.getDouble(3));
                any = true;
            }
            if (!any) {
                line.append("(empty)");
            }
        }
        System.out.println(line);
    }

    static void print(java.sql.Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            int columns = rows.getMetaData().getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int column = 1; column <= columns; column++) {
                header.append(String.format("%-12s", rows.getMetaData().getColumnLabel(column)));
            }
            System.out.println(header);
            while (rows.next()) {
                StringBuilder line = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    line.append(String.format("%-12s", rows.getString(column)));
                }
                System.out.println(line);
            }
        }
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5), with plenty else running - consecutive
 * runs varied by about a third, so read the shape rather than the digits:
 *
 * A day on one publisher's timeline
 * ---------------------------------
 * 09:00  full       3: g1 10y=20.0,  3: g1 5y=10.0,  4: g2 5y=7.0
 * 09:15  increment  3: g1 30y=5.0,  3: g1 5y=12.0,  4: g2 5y=7.0
 * 09:30  remove 4   3: g1 30y=5.0,  3: g1 5y=12.0
 *
 * What each refresh contributed
 * -----------------------------
 * refresh_id  kind        record_id   key         value       
 * run-09:00   full        3           g1 10y      20.0        
 * run-09:00   full        3           g1 5y       10.0        
 * run-09:00   full        4           g2 5y       7.0         
 * run-09:15   increment   3           g1 10y      -20.0       
 * run-09:15   increment   3           g1 30y      5.0         
 * run-09:15   increment   3           g1 5y       2.0         
 * run-09:30   increment   4           g2 5y       -7.0        
 *
 * The state is the sum of the contributions: yes
 * Committing run-09:30 a second time: applied=false, still 7 contributions
 * coverage was in the 09:00 refresh only, and still holds 1 value
 *
 * At some scale: 100 records of 25 values, published 200 times, on disk
 * --------------------------------------------------------------------
 * replacing the records outright, keeping no history: median 2.04 ms, p99 6.06 ms, 2,500 values of state, 0 contributions, 1,292 KB on disk
 * the same as refreshes, with the contributions:      median 5.09 ms, p99 9.77 ms, 2,500 values of state, 51,999 contributions, 2,828 KB on disk, sum agrees: yes
 *
 * The state does not grow: 2,500 values after 200 refreshes, because only the current one is kept.
 * The contributions do: 2,500 from the first refresh, then only what moved, which is why they are
 * what gets archived and cut back rather than living in the working database for ever.
 *
 * Refreshing costs about three times a plain replace of the same records for the contributions, the
 * ledger row, and knowing what each value moved by. Of that, resolving 250 keys used to take 2.1 ms
 * until they were remembered: ids never change, so the second refresh onwards does no SQL for them.
 *
 * The first refresh is a full set, and a full set contributes the values themselves rather than what
 * they moved by - it is a baseline. Everything after it is a difference, which is why the state here
 * is the sum of all of them.
 */
