package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MeasureHistoryTest {

    private static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");

    private Path directory;
    private DuckDBConnection live;
    private MeasureTable measures;
    private MeasureTable other;
    private MeasureHistory history;

    @Before
    public void open() throws Exception {
        directory = Files.createTempDirectory("history");
        live = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        measures = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();
        other = MeasureTable.named("n").fields("a").writtenBy("svc_a").build();
        measures.create(live);
        other.create(live);
        MeasureRefresh.createLedger(live);
        history = MeasureHistory.at(directory.resolve("history.duckdb").toString());
        history.create(live, measures, other);
    }

    @After
    public void close() throws Exception {
        live.close();
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private void refresh(String id, Instant at, double at5y) {
        MeasureRefresh.at(at, id).increment(measures, measures.batch()
                .record(3).put(at5y, "x", "5y").build()).commit(live);
    }

    private List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement statement = live.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                StringBuilder line = new StringBuilder();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    line.append(column == 1 ? "" : " ").append(result.getString(column));
                }
                out.add(line.toString());
            }
        }
        return out;
    }

    @Test
    public void shippingMovesTheOutboxIntoTheHistory() throws Exception {
        refresh("r1", NINE, 10);
        refresh("r2", NINE.plusSeconds(60), 12);

        MeasureHistory.Shipped shipped = history.ship(live, measures, other);

        assertEquals(2, shipped.entries());
        // Two refreshes, but the second only moved 5y from 10 to 12: one contribution each.
        assertEquals(2, shipped.contributions());
        assertEquals(0, measures.contributionCount(live));
        assertEquals(2, history.refreshCount(live));
        assertEquals(2, history.contributionCount(live, measures));
    }

    @Test
    public void contributionsArriveWithTheirFieldsRatherThanADictionaryId() throws Exception {
        refresh("r1", NINE, 10);
        history.ship(live, measures);

        assertEquals(List.of("r1 3 x 5y 10.0"),
                rows("SELECT refresh_id, record_id, a, point, value FROM " + history.contributionTable(measures)));
    }

    @Test
    public void theRefreshLedgerGoesWithThem() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, measures.batch()
                .record(3).put(10, "x", "5y").build()).commit(live);
        history.ship(live, measures);

        assertEquals(List.of("r1 svc_a 2026-09-30 09:00:00.0 m full"),
                rows("SELECT refresh_id, writer, refreshed_at, measure, kind FROM " + history.refreshTable()));
    }

    @Test
    public void shippingTwiceMovesNothingTheSecondTime() throws Exception {
        refresh("r1", NINE, 10);
        history.ship(live, measures);

        MeasureHistory.Shipped again = history.ship(live, measures);

        assertEquals(0, again.entries());
        assertEquals(0, again.contributions());
        assertEquals(1, history.contributionCount(live, measures));
    }

    @Test
    public void aShipmentInterruptedBeforeItsOutboxWasClearedIsHarmless() throws Exception {
        refresh("r1", NINE, 10);
        // What a crash between the two transactions leaves behind: shipped, but still in the outbox.
        history.attach(live);
        Sql.execute(live, "INSERT INTO " + history.contributionTable(measures)
                + " SELECT c.refresh_id, c.record_id, k.a, k.point, c.value FROM "
                + Sql.quote(measures.getContributionTable()) + " c JOIN " + Sql.quote(measures.getKeyTable())
                + " k ON k.id = c.key_id");
        Sql.execute(live, "INSERT INTO " + history.refreshTable() + " SELECT * FROM "
                + Sql.quote(MeasureRefresh.LEDGER));

        MeasureHistory.Shipped shipped = history.ship(live, measures);

        assertEquals(0, shipped.contributions());
        assertEquals(1, history.contributionCount(live, measures));
        assertEquals(0, measures.contributionCount(live));
    }

    @Test
    public void refreshesThatArriveDuringAShipmentAreKept() throws Exception {
        refresh("r1", NINE, 10);
        history.ship(live, measures);
        refresh("r2", NINE.plusSeconds(60), 12);

        assertEquals(1, measures.contributionCount(live));
        assertEquals(1, history.ship(live, measures).contributions());
        assertEquals(0, measures.contributionCount(live));
    }

    @Test
    public void theHistoryKeepsGrowingWhileTheLiveStateDoesNot() throws Exception {
        for (int refresh = 1; refresh <= 20; refresh++) {
            refresh("r" + refresh, NINE.plusSeconds(refresh), refresh);
            history.ship(live, measures);
        }
        assertEquals(1, measures.valueCount(live));
        assertEquals(0, measures.contributionCount(live));
        assertEquals(20, history.contributionCount(live, measures));
    }

    @Test
    public void publishingAZeroWhereThereWasNothingContributesNothing() throws Exception {
        refresh("r1", NINE, 0);
        history.ship(live, measures);

        // The state holds the zero; nothing moved, so there is nothing to say about it.
        assertEquals(1, measures.valueCount(live));
        assertEquals(0, history.contributionCount(live, measures));
    }

    @Test
    public void shippedRefreshesCanBeForgottenOnceTheyAreOldEnough() throws Exception {
        refresh("old", Instant.now().minus(Duration.ofHours(2)), 10);
        refresh("new", Instant.now(), 12);
        history.ship(live, measures);

        long forgotten = history.forgetShippedRefreshes(live, Duration.ofHours(1));

        assertEquals(1, forgotten);
        assertEquals(List.of("new"), rows("SELECT refresh_id FROM " + Sql.quote(MeasureRefresh.LEDGER)));
        // The history keeps both, so nothing about what was published is lost.
        assertEquals(2, history.refreshCount(live));
    }

    @Test
    public void anUnshippedRefreshIsNeverForgotten() throws Exception {
        refresh("old", Instant.now().minus(Duration.ofHours(2)), 10);

        assertEquals(0, history.forgetShippedRefreshes(live, Duration.ofHours(1)));
        assertEquals(List.of("old"), rows("SELECT refresh_id FROM " + Sql.quote(MeasureRefresh.LEDGER)));
    }

    @Test
    public void forgettingARefreshDoesNotLetItBeAppliedAgain() throws Exception {
        // Only in the window where the ledger has been trimmed: after that the history is the record,
        // so an id already shipped must still be refused.
        refresh("old", Instant.now().minus(Duration.ofHours(2)), 10);
        history.ship(live, measures);
        history.forgetShippedRefreshes(live, Duration.ofHours(1));

        assertTrue("the history still holds it", history.refreshCount(live) == 1);
    }

    @Test
    public void shippingOneMeasureLeavesAnothersContributionsToBeShippedLater() throws Exception {
        // One refresh over both. The ledger entry is what says a measure's contributions are in the
        // history, so shipping m must not write n's entry - or n's contributions would be skipped.
        MeasureRefresh.at(NINE, "r1")
                .full(measures, measures.batch().record(3).put(10, "x", "5y").build())
                .full(other, other.batch().record(3).put(1, "x").build())
                .commit(live);

        assertEquals(1, history.ship(live, measures).contributions());
        assertEquals(1, measures.valueCount(live));
        assertEquals(1, other.contributionCount(live));

        assertEquals(1, history.ship(live, other).contributions());
        assertEquals(1, history.contributionCount(live, other));
        assertEquals(0, other.contributionCount(live));
    }

    @Test
    public void severalMeasuresShipTogether() throws Exception {
        MeasureRefresh.at(NINE, "r1")
                .full(measures, measures.batch().record(3).put(10, "x", "5y").build())
                .full(other, other.batch().record(3).put(1, "x").build())
                .commit(live);

        MeasureHistory.Shipped shipped = history.ship(live, measures, other);

        // One refresh, but a ledger entry per measure in it.
        assertEquals(2, shipped.entries());
        assertEquals(2, shipped.contributions());
        assertEquals(1, history.contributionCount(live, measures));
        assertEquals(1, history.contributionCount(live, other));
    }

    @Test
    public void twoPublishersCanShipIntoOneHistory() throws Exception {
        refresh("a-1", NINE, 10);
        history.ship(live, measures);
        Sql.execute(live, "DETACH " + Sql.quote(history.getAlias()));

        // Another service, its own live database and its own dictionary ids, the same history file.
        try (DuckDBConnection second = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable theirs = MeasureTable.named("m").fields("a", "point").writtenBy("svc_b").build();
            theirs.create(second);
            MeasureRefresh.createLedger(second);
            history.create(second, theirs);
            MeasureRefresh.at(NINE, "b-1").full(theirs, theirs.batch()
                    .record(9).put(4, "y", "10y").build()).commit(second);
            history.ship(second, theirs);

            assertEquals(2, history.refreshCount(second));
            assertEquals(List.of("a-1 svc_a 3 x 5y", "b-1 svc_b 9 y 10y"),
                    query(second, "SELECT c.refresh_id, r.writer, c.record_id, c.a, c.point FROM "
                            + history.contributionTable(theirs) + " c JOIN " + history.refreshTable() + " r"
                            + " ON r.refresh_id = c.refresh_id ORDER BY c.refresh_id"));
        }
    }

    private List<String> query(java.sql.Connection connection, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                StringBuilder line = new StringBuilder();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    line.append(column == 1 ? "" : " ").append(result.getString(column));
                }
                out.add(line.toString());
            }
        }
        return out;
    }
}
