/*
 * Two databases: one being written to, holding only the current state, and one holding every
 * contribution ever made.
 *
 * DuckDB refuses to write to two attached databases in one transaction, so a refresh cannot land in
 * both at once. Instead it writes the new state and its contributions into the live database in one
 * transaction - the contributions being an outbox - and a shipper moves them into the history later,
 * in bulk, on whatever schedule suits. Nothing can be lost, because until it is shipped a
 * contribution sits in a committed table; nothing is counted twice, because shipping skips refreshes
 * the history already holds.
 *
 * What this shows: the live database staying the same size while the history grows, what a refresh
 * and a shipment each cost, and that shipping the same thing again does nothing.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx2g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasureHistory.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.measure.MeasureBatch;
import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CoreMeasureHistory {

    /** An hour ago, so that forgetting old refreshes at the end has something to forget. */
    static final Instant START = Instant.now().minus(Duration.ofHours(1))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    static final int RECORDS = 1_000;
    static final int POINTS = 50;
    static final int REFRESHES = 20;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("measure-history");
        try {
            Path liveFile = directory.resolve("live.duckdb");
            Path historyFile = directory.resolve("history.duckdb");
            try (DuckDBConnection live = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + liveFile)) {
                MeasureTable measures = MeasureTable.named("m").fields("group", "point").writtenBy("svc_a").build();
                measures.create(live);
                MeasureRefresh.createLedger(live);
                MeasureHistory history = MeasureHistory.at(historyFile.toString());
                history.create(live, measures);

                System.out.printf("%,d records of %,d values: a full set, then %d increments moving a tenth%n%n",
                        RECORDS, POINTS, REFRESHES);

                List<Long> refreshes = new ArrayList<>();
                List<Long> shipments = new ArrayList<>();
                long shipped = 0;
                for (int refresh = 0; refresh <= REFRESHES; refresh++) {
                    MeasureBatch batch = batch(measures, refresh);
                    long started = System.nanoTime();
                    MeasureRefresh refreshing = MeasureRefresh.at(START.plusSeconds(refresh), "run-" + refresh);
                    if (refresh == 0) {
                        refreshing.full(measures, batch);
                    }
                    else {
                        refreshing.increment(measures, batch);
                    }
                    refreshing.commit(live);
                    refreshes.add(System.nanoTime() - started);

                    // The shipper, run here after every refresh; in a service it is a scheduled job.
                    started = System.nanoTime();
                    MeasureHistory.Shipped moved = history.ship(live, measures);
                    shipments.add(System.nanoTime() - started);
                    shipped += moved.contributions();
                }

                System.out.println("Refresh into the live database, with its contributions in the outbox");
                System.out.printf("   full set of %,d values: %.0f ms%n", RECORDS * POINTS, refreshes.get(0) / 1e6);
                System.out.printf("   each increment:          %s%n", summary(refreshes.subList(1, refreshes.size())));
                System.out.println("Shipping the outbox into the history");
                System.out.printf("   the first, %,d contributions: %.0f ms%n", RECORDS * POINTS,
                        shipments.get(0) / 1e6);
                System.out.printf("   the rest:                        %s%n",
                        summary(shipments.subList(1, shipments.size())));

                System.out.printf("%nShipping again with nothing to ship: ");
                long started = System.nanoTime();
                MeasureHistory.Shipped nothing = history.ship(live, measures);
                System.out.printf("%.1f ms, %d contributions%n", (System.nanoTime() - started) / 1e6,
                        nothing.contributions());

                // Each database checkpoints on its own; without naming it the history keeps
                // its rows in the write-ahead log and looks far smaller than it is.
                Sql.execute(live, "CHECKPOINT");
                Sql.execute(live, "CHECKPOINT " + Sql.quote(history.getAlias()));
                System.out.printf("%nlive:     %,d values of state, %,d in the outbox, %,d KB on disk%n",
                        measures.valueCount(live), measures.contributionCount(live), Files.size(liveFile) / 1024);
                System.out.printf("history:  %,d contributions over %,d refreshes, %,d KB on disk%n",
                        history.contributionCount(live, measures), history.refreshCount(live),
                        Files.size(historyFile) / 1024);
                System.out.printf("%,d contributions were shipped in all, and the live database holds none of them%n",
                        shipped);

                // A contribution in the history carries its fields, so nothing else is needed to read
                // it - no dictionary, and no knowledge of which service wrote it.
                System.out.println("\nWhat the history holds, for one key of one record:");
                try (var statement = live.prepareStatement("SELECT r.refresh_id, r.refreshed_at, c.value FROM "
                        + history.contributionTable(measures) + " c JOIN " + history.refreshTable() + " r"
                        + " ON r.refresh_id = c.refresh_id AND r.measure = 'm'"
                        + " WHERE c.record_id = 7 AND c.\"group\" = 'g7' AND c.point = 'p3'"
                        + " ORDER BY r.seq LIMIT 4");
                     var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        System.out.printf("   %-8s %s  %+.1f%n", rows.getString(1), rows.getString(2),
                                rows.getDouble(3));
                    }
                }

                // The live ledger is only there to make committing an id twice harmless, which matters
                // for seconds, not days. The history keeps every one of them.
                long forgotten = history.forgetShippedRefreshes(live, Duration.ofMinutes(30));
                System.out.printf("%nForgetting shipped refreshes in the live ledger: %d gone, %,d still in the"
                        + " history%n", forgotten, history.refreshCount(live));
            }
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The whole set on the first pass, then a tenth of the values moved. */
    static MeasureBatch batch(MeasureTable measures, int refresh) {
        var batch = measures.batch();
        for (int record = 0; record < RECORDS; record++) {
            if (refresh > 0 && record % 10 != refresh % 10) {
                continue;
            }
            batch.record(record);
            for (int point = 0; point < POINTS; point++) {
                batch.put(point + record + refresh, "g" + (record % 10), "p" + point);
            }
        }
        return batch.build();
    }

    static String summary(List<Long> timings) {
        List<Long> sorted = new ArrayList<>(timings);
        sorted.sort(Long::compare);
        return String.format("median %.1f ms, p99 %.1f ms", sorted.get(sorted.size() / 2) / 1e6,
                sorted.get((int) (sorted.size() * 0.99)) / 1e6);
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5), with plenty else running - consecutive
 * runs varied by about a third, so read the shape rather than the digits:
 *
 * 1,000 records of 50 values: a full set, then 20 increments moving a tenth
 *
 * Refresh into the live database, with its contributions in the outbox
 *    full set of 50,000 values: 55 ms
 *    each increment:          median 9.3 ms, p99 14.8 ms
 * Shipping the outbox into the history
 *    the first, 50,000 contributions: 19 ms
 *    the rest:                        median 5.5 ms, p99 6.0 ms
 *
 * Shipping again with nothing to ship: 0.4 ms, 0 contributions
 *
 * live:     50,000 values of state, 0 in the outbox, 2,316 KB on disk
 * history:  150,000 contributions over 21 refreshes, 1,292 KB on disk
 * 150,000 contributions were shipped in all, and the live database holds none of them
 *
 * What the history holds, for one key of one record:
 *    run-0    2026-09-30 08:37:39.0  +10.0
 *    run-7    2026-09-30 08:37:46.0  +7.0
 *    run-17   2026-09-30 08:37:56.0  +10.0
 *
 * Forgetting shipped refreshes in the live ledger: 21 gone, 21 still in the history
 *
 * The live database holds 50,000 values and nothing else, however many refreshes have been through
 * it; the history holds all 150,000 contributions, in a file of its own that can be pruned on its
 * own schedule. Shipping is bulk: 18 ms for the first 50,000 contributions, single digits for the
 * 5,000 of an increment, and under a millisecond when there is nothing waiting, because the shipper
 * asks the outbox what it holds before it looks at anything else.
 *
 * Refreshes carry their own timestamps, so the ones printed above are an hour before the run - and
 * forgetting them in the live ledger leaves the history untouched: the ledger is only there so that
 * committing an id twice counts once, which matters for seconds, not days.
 */
