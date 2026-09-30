package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeasureRefreshTest {

    private static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");
    private static final Instant TEN = Instant.parse("2026-09-30T10:00:00Z");
    private static final Instant ELEVEN = Instant.parse("2026-09-30T11:00:00Z");

    private DuckDBConnection connection;
    private MeasureTable measures;
    private MeasureTable other;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        measures = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();
        other = MeasureTable.named("n").fields("a").writtenBy("svc_a").build();
        measures.create(connection);
        other.create(connection);
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    /** The state, as "record key=value", so a whole measure can be compared in one assert. */
    private List<String> state() throws Exception {
        return rows("SELECT v.record_id, k.a || '/' || k.point, v.value FROM " + Sql.quote(measures.getValueTable())
                + " v JOIN " + Sql.quote(measures.getKeyTable()) + " k ON k.id = v.key_id"
                + " ORDER BY 1, 2");
    }

    /** Every contribution ever written, in the order the refreshes committed. */
    private List<String> contributions() throws Exception {
        return rows("SELECT c.record_id, k.a || '/' || k.point, c.value FROM "
                + Sql.quote(measures.getContributionTable()) + " c JOIN " + Sql.quote(measures.getKeyTable())
                + " k ON k.id = c.key_id JOIN " + Sql.quote(MeasureRefresh.LEDGER) + " r"
                + " ON r.refresh_id = c.refresh_id AND r.measure = 'm' ORDER BY r.seq, 1, 2");
    }

    private List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                out.add(result.getLong(1) + " " + result.getString(2) + "=" + result.getDouble(3));
            }
        }
        return out;
    }

    private MeasureBatch batch(long record, double at5y, double at10y) {
        return measures.batch().record(record).put(at5y, "x", "5y").put(at10y, "x", "10y").build();
    }

    @Test
    public void aFullSetIsWrittenAsItsOwnContributions() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);

        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0"), state());
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0"), contributions());
    }

    @Test
    public void anIncrementContributesOnlyTheDifference() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").increment(measures, measures.batch()
                .record(3).put(12, "x", "5y").put(20, "x", "10y").put(5, "x", "30y").build()).commit(connection);

        assertEquals(List.of("3 x/10y=20.0", "3 x/30y=5.0", "3 x/5y=12.0"), state());
        // 10y did not move, so it contributes nothing; 5y moved by 2 and 30y arrived.
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0", "3 x/30y=5.0", "3 x/5y=2.0"), contributions());
    }

    @Test
    public void aRecordInARefreshReplacesItselfEntirely() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").increment(measures, measures.batch()
                .record(3).put(10, "x", "5y").build()).commit(connection);

        // The increment does not mention 10y, so record 3 no longer has it.
        assertEquals(List.of("3 x/5y=10.0"), state());
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0", "3 x/10y=-20.0"), contributions());
    }

    @Test
    public void anIncrementLeavesOtherRecordsAlone() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, measures.batch()
                .record(3).put(10, "x", "5y")
                .record(4).put(7, "x", "5y").build()).commit(connection);
        MeasureRefresh.at(TEN, "r2").increment(measures, measures.batch()
                .record(3).put(11, "x", "5y").build()).commit(connection);

        assertEquals(List.of("3 x/5y=11.0", "4 x/5y=7.0"), state());
    }

    @Test
    public void aFullSetRemovesRecordsItDoesNotName() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, measures.batch()
                .record(3).put(10, "x", "5y")
                .record(4).put(7, "x", "5y").build()).commit(connection);
        MeasureRefresh.at(TEN, "r2").full(measures, measures.batch()
                .record(3).put(10, "x", "5y").build()).commit(connection);

        assertEquals(List.of("3 x/5y=10.0"), state());
        // The second full set is a baseline: it says what record 3 holds, and says nothing of record
        // 4 at all. Reading from it alone gives the state, which is what lets the rest be dropped.
        assertEquals(List.of("3 x/5y=10.0", "4 x/5y=7.0", "3 x/5y=10.0"), contributions());
    }

    @Test
    public void anEmptyFullSetEmptiesTheMeasure() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").full(measures, measures.batch().build()).commit(connection);

        assertEquals(List.of(), state());
        assertEquals(0, measures.valueCount(connection));
    }

    @Test
    public void removingARecordCancelsItAndKeepsTheAudit() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").remove(measures, 3).commit(connection);

        assertEquals(List.of(), state());
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0", "3 x/10y=-20.0", "3 x/5y=-10.0"), contributions());
    }

    @Test
    public void theStateIsAlwaysTheSumOfTheContributions() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").increment(measures, measures.batch()
                .record(3).put(12, "x", "5y").put(5, "x", "30y").build()).commit(connection);
        MeasureRefresh.at(ELEVEN, "r3").increment(measures, measures.batch()
                .record(4).put(1, "x", "5y").build()).commit(connection);

        assertEquals(0, Sql.queryLong(connection, "SELECT count(*) FROM ("
                + " SELECT record_id, key_id, sum(value) AS total FROM " + Sql.quote(measures.getContributionTable())
                + " GROUP BY 1, 2 HAVING sum(value) <> 0) c"
                + " FULL OUTER JOIN " + Sql.quote(measures.getValueTable()) + " v"
                + " ON v.record_id = c.record_id AND v.key_id = c.key_id"
                + " WHERE coalesce(c.total, 0) IS DISTINCT FROM coalesce(v.value, 0)", List.of()));
    }

    @Test
    public void aFullSetIsABaselineRatherThanADifference() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").full(measures, measures.batch()
                .record(3).put(1, "x", "5y").build()).commit(connection);

        // Not 1 - 12: the values themselves, so nothing before this refresh is needed to read it.
        assertEquals(List.of("3 x/5y=1.0"), contributionsOf("r2"));
        assertEquals(List.of("3 x/5y=1.0"), state());
    }

    /** The contributions of one refresh alone. */
    private List<String> contributionsOf(String refreshId) throws Exception {
        return rows("SELECT c.record_id, k.a || '/' || k.point, c.value FROM "
                + Sql.quote(measures.getContributionTable()) + " c JOIN " + Sql.quote(measures.getKeyTable())
                + " k ON k.id = c.key_id WHERE c.refresh_id = '" + refreshId + "' ORDER BY 1, 2");
    }

    @Test
    public void thesameIdCommittedTwiceCountsOnce() throws Exception {
        assertTrue(MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection));
        assertFalse(MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection));

        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0"), state());
        assertEquals(2, measures.contributionCount(connection));
    }

    @Test
    public void twoRefreshesCanShareATimestampAndStillBeOrdered() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, measures.batch()
                .record(3).put(10, "x", "5y").build()).commit(connection);
        MeasureRefresh.at(NINE, "r2").increment(measures, measures.batch()
                .record(3).put(11, "x", "5y").build()).commit(connection);

        assertEquals(List.of("3 x/5y=11.0"), state());
        assertEquals(List.of("3 x/5y=10.0", "3 x/5y=1.0"), contributions());
        assertEquals(2, Sql.queryLong(connection, "SELECT count(DISTINCT seq) FROM "
                + Sql.quote(MeasureRefresh.LEDGER), List.of()));
    }

    @Test
    public void oneRefreshCoversSeveralMeasuresAtOnce() throws Exception {
        MeasureRefresh.at(NINE, "r1")
                .full(measures, batch(3, 10, 20))
                .full(other, other.batch().record(3).put(1, "x").build())
                .commit(connection);

        assertEquals(2, measures.valueCount(connection));
        assertEquals(1, other.valueCount(connection));
        assertEquals(2, Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(MeasureRefresh.LEDGER)
                + " WHERE refresh_id = 'r1'", List.of()));
    }

    @Test
    public void aMeasureLeftOutOfARefreshIsUntouched() throws Exception {
        MeasureRefresh.at(NINE, "r1")
                .full(measures, batch(3, 10, 20))
                .full(other, other.batch().record(3).put(1, "x").build())
                .commit(connection);
        MeasureRefresh.at(TEN, "r2").full(measures, measures.batch().build()).commit(connection);

        assertEquals(0, measures.valueCount(connection));
        assertEquals(1, other.valueCount(connection));
    }

    @Test
    public void theLedgerSaysWhenAndWhatKind() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureRefresh.at(TEN, "r2").increment(measures, batch(3, 11, 20)).commit(connection);

        List<String> ledger = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT refresh_id, writer, refreshed_at, measure, kind"
                + " FROM " + Sql.quote(MeasureRefresh.LEDGER) + " ORDER BY seq");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ledger.add(rows.getString(1) + " " + rows.getString(2) + " " + rows.getString(3) + " "
                        + rows.getString(4) + " " + rows.getString(5));
            }
        }
        assertEquals(List.of(
                        "r1 svc_a 2026-09-30 09:00:00.0 m full",
                        "r2 svc_a 2026-09-30 10:00:00.0 m increment"),
                ledger);
    }

    @Test
    public void oneTimelineBelongsToOnePublisher() {
        MeasureTable theirs = MeasureTable.named("t").fields("a").writtenBy("svc_b").build();
        try {
            MeasureRefresh.at(NINE, "r1")
                    .full(measures, batch(3, 10, 20))
                    .full(theirs, theirs.batch().record(3).put(1, "x").build());
            fail("Expected a refresh over two publishers to be refused");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("svc_b"));
        }
    }

    @Test
    public void aTimelineOnlyMovesForward() throws Exception {
        MeasureRefresh.at(TEN, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        try {
            MeasureRefresh.at(NINE, "r2").increment(measures, batch(3, 11, 20)).commit(connection);
            fail("expected a refresh dated before the last one to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("only moves forward"));
        }
        // Nothing of it was written, and the measure stands where it did.
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0"), state());
        assertEquals(2, measures.contributionCount(connection));
    }

    @Test
    public void twoMeasuresKeepTheirOwnPlaceOnTheTimeline() throws Exception {
        MeasureRefresh.at(TEN, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        // 'other' has not been published at all yet, so nine o'clock is still ahead of nothing.
        assertTrue(MeasureRefresh.at(NINE, "r2").full(other, other.batch()
                .record(3).put(1, "x").build()).commit(connection));
    }

    @Test
    public void aCorrectionAtTheSamePointIsAllowed() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        assertTrue(MeasureRefresh.at(NINE, "r2").increment(measures, measures.batch()
                .record(3).put(11, "x", "5y").put(20, "x", "10y").build()).commit(connection));

        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=11.0"), state());
    }

    @Test
    public void aRefreshNeedsAnId() {
        try {
            MeasureRefresh.at(NINE, " ");
            fail("Expected a blank id to be refused");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("id"));
        }
    }

    @Test
    public void aRefreshCannotPublishTheSameMeasureTwice() {
        MeasureRefresh refresh = MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20));
        try {
            refresh.increment(measures, batch(4, 1, 2));
            fail("Expected the second batch for one measure to be refused");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already in this refresh"));
        }
    }

    @Test
    public void nothingIsWrittenWhenAMeasureInTheRefreshFails() throws Exception {
        MeasureRefresh.at(NINE, "r1").full(measures, batch(3, 10, 20)).commit(connection);
        MeasureTable mismatched = MeasureTable.named("m").fields("a").writtenBy("svc_a").build();
        try {
            MeasureRefresh.at(TEN, "r2")
                    .full(other, other.batch().record(3).put(1, "x").build())
                    .full(mismatched, mismatched.batch().record(3).put(9, "x").build())
                    .commit(connection);
            fail("Expected a definition the tables disagree with to be refused");
        }
        catch (RuntimeException expected) {
            // The refresh is one transaction, so the measure that did write is rolled back with it.
        }
        assertEquals(0, other.valueCount(connection));
        assertEquals(List.of("3 x/10y=20.0", "3 x/5y=10.0"), state());
    }
}
