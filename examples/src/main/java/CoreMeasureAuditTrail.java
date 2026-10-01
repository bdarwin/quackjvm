/*
 * Reading the history as history: what a record held at every point of the day, who changed it and
 * by how much, and what moved between two points.
 *
 * Nothing in a measure is overwritten. Every refresh writes contributions - a full set the values
 * themselves, an increment what moved - and the history keeps all of them with the refresh, its
 * timestamp, its writer and its kind beside each row. So the questions an audit asks are plain
 * queries over two tables, and this example asks them.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasureAuditTrail.java
 */

import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CoreMeasureAuditTrail {

    static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("audit");
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

            // A day: a full set, then whatever moved, hour by hour. Record 3 is the one we follow.
            MeasureRefresh.at(NINE, "09:00").full(exposure, exposure.batch()
                    .record(3).put(10, "g1", "5y").put(20, "g1", "10y")
                    .record(4).put(7, "g2", "5y")
                    .build()).commit(live);
            MeasureRefresh.at(NINE.plusSeconds(3600), "10:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(20, "g1", "10y").put(5, "g1", "30y")
                    .build()).commit(live);
            MeasureRefresh.at(NINE.plusSeconds(7200), "11:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(18, "g1", "10y").put(5, "g1", "30y")
                    .record(4).put(8, "g2", "5y")
                    .build()).commit(live);
            MeasureRefresh.at(NINE.plusSeconds(10800), "12:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(18, "g1", "10y")        // 30y has gone
                    .build()).commit(live);
            MeasureRefresh.at(NINE.plusSeconds(14400), "13:00").remove(exposure, 4).commit(live);
            history.ship(live, exposure);

            System.out.println("Record 3, hour by hour - the state at each point");
            System.out.println("-----------------------------------------------");
            System.out.printf("   %-7s %6s %6s %6s%n", "", "5y", "10y", "30y");
            for (int hour = 9; hour <= 13; hour++) {
                Instant at = NINE.plusSeconds((hour - 9) * 3600L);
                Map<String, Object> values = valuesOf(live, exposure.query().asOf(at, history).records(3));
                System.out.printf("   %02d:00   %6s %6s %6s%n", hour, cell(values.get("5y")),
                        cell(values.get("10y")), cell(values.get("30y")));
            }

            System.out.println("\nWho changed what, and by how much - the contributions, with their refresh");
            System.out.println("-----------------------------------------------------------------------");
            System.out.printf("   %-6s %-10s %-6s %-3s %-7s %7s%n", "when", "kind", "by", "rec", "key", "moved");
            Rows.of(live.duplicate(), "SELECT strftime(r.refreshed_at, '%H:%M') AS at, r.kind, r.writer,"
                    + " c.record_id, c.\"group\" || ' ' || c.point AS key, c.value"
                    + " FROM " + history.contributionTable(exposure) + " c"
                    + " JOIN " + history.refreshTable() + " r ON r.refresh_id = c.refresh_id AND r.measure = 'exposure'"
                    + " ORDER BY r.seq, c.record_id, array_position(['5y','10y','30y'], c.point)")
                    .forEachRow((SqlRow row) -> System.out.printf("   %-6s %-10s %-6s %-3s %-7s %+7.1f%n",
                            row.get("at"), row.get("kind"), row.get("writer"), row.get("record_id"),
                            row.get("key"), (Double) row.get("value")));

            System.out.println("\nWhat moved between 10:00 and 13:00, for everything");
            System.out.println("-------------------------------------------------");
            Map<String, Double> before = totals(live, exposure.query().asOf(NINE.plusSeconds(3600), history));
            Map<String, Double> after = totals(live, exposure.query().asOf(NINE.plusSeconds(14400), history));
            List<String> keys = new ArrayList<>(before.keySet());
            for (String key : after.keySet()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
            System.out.printf("   %-8s %7s %7s %8s%n", "key", "10:00", "13:00", "moved");
            for (String key : keys) {
                double was = before.getOrDefault(key, 0.0);
                double is = after.getOrDefault(key, 0.0);
                if (was != is) {
                    System.out.printf("   %-8s %7s %7s %+8.1f%n", key, cell(before.get(key)), cell(after.get(key)),
                            is - was);
                }
            }

            System.out.println("\nWhich refreshes touched record 3 at all, and how many values each moved");
            System.out.println("-----------------------------------------------------------------------");
            Rows.of(live.duplicate(), "SELECT r.refresh_id, r.kind, count(*) AS moved, round(sum(abs(c.value)), 1)"
                    + " AS by_how_much FROM " + history.contributionTable(exposure) + " c JOIN "
                    + history.refreshTable() + " r ON r.refresh_id = c.refresh_id AND r.measure = 'exposure'"
                    + " WHERE c.record_id = 3 GROUP BY r.seq, r.refresh_id, r.kind ORDER BY r.seq")
                    .forEachRow((SqlRow row) -> System.out.printf("   %-6s %-10s %2s values, %5s in all%n",
                            row.get("refresh_id"), row.get("kind"), row.get("moved"), row.get("by_how_much")));

            System.out.println("\nAnd the ledger itself: every refresh, in the order it was committed");
            System.out.println("------------------------------------------------------------------");
            Rows.of(live.duplicate(), "SELECT seq, refresh_id, kind, writer, refreshed_at FROM "
                    + history.refreshTable() + " WHERE measure = 'exposure' ORDER BY seq")
                    .forEachRow((SqlRow row) -> System.out.printf("   %s  %-6s %-10s %-6s %s%n", row.get("seq"),
                            row.get("refresh_id"), row.get("kind"), row.get("writer"), row.get("refreshed_at")));
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Point to value, for one record at one point in time. */
    static Map<String, Object> valuesOf(DuckDBConnection connection, MeasureQuery query) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        query.rows("point").run(connection.duplicate())
                .forEachRow((SqlRow row) -> values.put(String.valueOf(row.get("point")), row.get("total")));
        return values;
    }

    /** "group point" to total, for everything at one point in time. */
    static Map<String, Double> totals(DuckDBConnection connection, MeasureQuery query) throws Exception {
        Map<String, Double> totals = new LinkedHashMap<>();
        query.rows("group", "point").run(connection.duplicate())
                .forEachRow((SqlRow row) -> totals.put(row.get("group") + " " + row.get("point"),
                        (Double) row.get("total")));
        return totals;
    }

    static String cell(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * Record 3, hour by hour - the state at each point
 * -----------------------------------------------
 *                5y    10y    30y
 *    09:00     10.0   20.0      -
 *    10:00     12.0   20.0    5.0
 *    11:00     12.0   18.0    5.0
 *    12:00     12.0   18.0      -
 *    13:00     12.0   18.0      -
 *
 * Who changed what, and by how much - the contributions, with their refresh
 * -----------------------------------------------------------------------
 *    when   kind       by     rec key       moved
 *    09:00  full       svc_a  3   g1 5y     +10.0
 *    09:00  full       svc_a  3   g1 10y    +20.0
 *    09:00  full       svc_a  4   g2 5y      +7.0
 *    10:00  increment  svc_a  3   g1 5y      +2.0
 *    10:00  increment  svc_a  3   g1 30y     +5.0
 *    11:00  increment  svc_a  3   g1 10y     -2.0
 *    11:00  increment  svc_a  4   g2 5y      +1.0
 *    12:00  increment  svc_a  3   g1 30y     -5.0
 *    13:00  increment  svc_a  4   g2 5y      -8.0
 *
 * What moved between 10:00 and 13:00, for everything
 * -------------------------------------------------
 *    key        10:00   13:00    moved
 *    g1 10y      20.0    18.0     -2.0
 *    g1 30y       5.0       -     -5.0
 *    g2 5y        7.0       -     -7.0
 *
 * Which refreshes touched record 3 at all, and how many values each moved
 * -----------------------------------------------------------------------
 *    09:00  full        2 values,  30.0 in all
 *    10:00  increment   2 values,   7.0 in all
 *    11:00  increment   1 values,   2.0 in all
 *    12:00  increment   1 values,   5.0 in all
 *
 * And the ledger itself: every refresh, in the order it was committed
 * ------------------------------------------------------------------
 *    1  09:00  full       svc_a  2026-09-30 09:00:00.0
 *    2  10:00  increment  svc_a  2026-09-30 10:00:00.0
 *    3  11:00  increment  svc_a  2026-09-30 11:00:00.0
 *    4  12:00  increment  svc_a  2026-09-30 12:00:00.0
 *    5  13:00  increment  svc_a  2026-09-30 13:00:00.0
 *
 * Every row in the second table is a fact that was never overwritten: 11:00 moved record 3's 10y by
 * -2, 12:00 took its 30y away, 13:00 removed record 4 entirely, and each says who and when. The
 * hour-by-hour view is the same facts added up to each point, which is all a state ever is here.
 *
 * None of these questions needed anything but the two history tables and ordinary SQL - which is
 * the point of keeping contributions rather than overwriting values.
 */
