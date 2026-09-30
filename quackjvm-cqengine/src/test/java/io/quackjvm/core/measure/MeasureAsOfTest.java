package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeasureAsOfTest {

    private static final Instant EIGHT = Instant.parse("2026-09-30T08:00:00Z");
    private static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-09-30T11:00:00Z");
    private static final Instant NOON = Instant.parse("2026-09-30T12:00:00Z");

    private Path directory;
    private DuckDBConnection live;
    private MeasureTable measures;
    private MeasureHistory history;

    @Before
    public void open() throws Exception {
        directory = Files.createTempDirectory("as-of");
        live = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        measures = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();
        measures.create(live);
        MeasureRefresh.createLedger(live);
        history = MeasureHistory.at(directory.resolve("history.duckdb").toString());
        history.create(live, measures);
    }

    @After
    public void close() throws Exception {
        live.close();
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** Nine o'clock: a full set. Ten: an increment. Eleven: record 4 removed. */
    private void aMorning() {
        MeasureRefresh.at(NINE, "nine").full(measures, measures.batch()
                .record(3).put(10, "x", "5y").put(20, "x", "10y")
                .record(4).put(7, "y", "5y")
                .build()).commit(live);
        // Record 3 as it now stands: 5y has moved, 10y has not, 30y is new. A record in a refresh
        // replaces itself entirely, so leaving 10y out here would cancel it.
        MeasureRefresh.at(TEN, "ten").increment(measures, measures.batch()
                .record(3).put(12, "x", "5y").put(20, "x", "10y").put(5, "x", "30y")
                .build()).commit(live);
        MeasureRefresh.at(ELEVEN, "eleven").remove(measures, 4).commit(live);
    }

    private List<String> read(MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        Rows result = query.run(live.duplicate());
        result.forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                Object value = row.get(column);
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=').append(value);
            }
            rows.add(text.toString());
        });
        return rows;
    }

    @Test
    public void beforeAnythingWasPublishedThereIsNothing() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of(), read(measures.query().asOf(EIGHT, history).rows("a", "point")));
    }

    @Test
    public void asOfAFullSetIsThatFullSet() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of("a=x | point=10y | total=20.0", "a=x | point=5y | total=10.0",
                        "a=y | point=5y | total=7.0"),
                read(measures.query().asOf(NINE, history).rows("a", "point")));
    }

    @Test
    public void asOfAnIncrementIsTheSumUpToIt() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of("a=x | point=10y | total=20.0", "a=x | point=30y | total=5.0",
                        "a=x | point=5y | total=12.0", "a=y | point=5y | total=7.0"),
                read(measures.query().asOf(TEN, history).rows("a", "point")));
    }

    @Test
    public void aPointBetweenTwoRefreshesReadsAsTheEarlierOne() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(read(measures.query().asOf(NINE, history).rows("a", "point")),
                read(measures.query().asOf(NINE.plusSeconds(1800), history).rows("a", "point")));
    }

    @Test
    public void aRemovedRecordIsThereBeforeAndGoneAfter() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of("a=y | point=5y | total=7.0"),
                read(measures.query().asOf(TEN, history).records(4).rows("a", "point")));
        assertEquals(List.of(), read(measures.query().asOf(ELEVEN, history).records(4).rows("a", "point")));
    }

    @Test
    public void keysThatCancelOutAreLeftOutUnlessAskedFor() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of(), read(measures.query().asOf(ELEVEN, history).records(4).rows("a", "point")));
        assertEquals(List.of("a=y | point=5y | total=0.0"),
                read(measures.query().asOf(ELEVEN, history).records(4).showZeros().rows("a", "point")));
    }

    @Test
    public void aLaterFullSetIsWhereTheSumStarts() throws Exception {
        aMorning();
        // A full set naming only record 3: record 4 is gone, and nothing before this counts.
        MeasureRefresh.at(NOON, "noon").full(measures, measures.batch()
                .record(3).put(1, "x", "5y").build()).commit(live);
        history.ship(live, measures);

        assertEquals(List.of("a=x | point=5y | total=1.0"),
                read(measures.query().asOf(NOON, history).rows("a", "point")));
    }

    @Test
    public void whatIsStillInTheOutboxCountsToo() throws Exception {
        aMorning();
        // Nothing shipped at all: the answer comes from the outbox alone.
        assertTrue("there should be something waiting", measures.contributionCount(live) > 0);
        assertEquals(List.of("a=x | point=10y | total=20.0", "a=x | point=30y | total=5.0",
                        "a=x | point=5y | total=12.0"),
                read(measures.query().asOf(ELEVEN, history).rows("a", "point")));
    }

    @Test
    public void halfShippedReadsTheSameAsFullyShipped() throws Exception {
        aMorning();
        List<String> beforeShipping = read(measures.query().asOf(ELEVEN, history).rows("a", "point"));
        history.ship(live, measures);

        assertEquals(beforeShipping, read(measures.query().asOf(ELEVEN, history).rows("a", "point")));
    }

    @Test
    public void aContributionShippedButNotYetClearedIsCountedOnce() throws Exception {
        aMorning();
        // The crash window: in the history, and still in the outbox.
        history.attach(live);
        Sql.execute(live, "INSERT INTO " + history.contributionTable(measures)
                + " SELECT c.refresh_id, c.record_id, k.a, k.point, c.value FROM "
                + Sql.quote(measures.getContributionTable()) + " c JOIN " + Sql.quote(measures.getKeyTable())
                + " k ON k.id = c.key_id");
        Sql.execute(live, "INSERT INTO " + history.refreshTable() + " SELECT * FROM "
                + Sql.quote(MeasureRefresh.LEDGER));

        assertEquals(List.of("a=x | point=10y | total=20.0", "a=x | point=30y | total=5.0",
                        "a=x | point=5y | total=12.0"),
                read(measures.query().asOf(ELEVEN, history).rows("a", "point")));
    }

    @Test
    public void asOfNowAgreesWithTheStateItself() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(read(measures.query().rows("a", "point")),
                read(measures.query().asOf(ELEVEN, history).rows("a", "point")));
    }

    @Test
    public void theColumnsAreWhatTheAnswerHoldsAtThatPoint() throws Exception {
        aMorning();
        history.ship(live, measures);

        // 30y does not exist yet at nine o'clock, so it is not a column.
        assertEquals(List.of("a=x | 10y=20.0 | 5y=10.0", "a=y | 10y=null | 5y=7.0"),
                read(measures.query().asOf(NINE, history).rows("a").columns("point")));
        assertEquals(List.of("a=x | 10y=20.0 | 30y=5.0 | 5y=12.0"),
                read(measures.query().asOf(ELEVEN, history).rows("a").columns("point")));
    }

    @Test
    public void fieldsCanStillBeFilteredAndPivotedAnyWayRound() throws Exception {
        aMorning();
        history.ship(live, measures);

        assertEquals(List.of("point=10y | x=20.0", "point=30y | x=5.0", "point=5y | x=12.0"),
                read(measures.query().asOf(ELEVEN, history).where("a", "x").rows("point").columns("a")));
    }

    @Test
    public void theHistoryOfAMeasureWithAUnitStillRefusesToAddUnitsTogether() throws Exception {
        MeasureTable priced = MeasureTable.named("p").fields("a", "unit").unit("unit").writtenBy("svc_a").build();
        priced.create(live);
        history.create(live, priced);
        MeasureRefresh.at(NINE, "nine-p").full(priced, priced.batch()
                .record(1).put(2, "x", "U1").put(3, "x", "U2").build()).commit(live);
        history.ship(live, priced);

        try {
            read(priced.query().asOf(NINE, history).rows("a"));
            fail("expected units to be refused");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("different units"));
        }
        assertEquals(List.of("a=x | unit=U1 | total=2.0", "a=x | unit=U2 | total=3.0"),
                read(priced.query().asOf(NINE, history).rows("a", "unit")));
    }

    @Test
    public void latestPerKeyMakesNoSenseAsOfAPoint() throws Exception {
        aMorning();
        try {
            measures.query().asOf(NINE, history).latestPerKey().rows("a").statement(live);
            fail("expected latestPerKey to be refused");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("added up"));
        }
    }

}
