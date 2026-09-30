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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeasureExportTest {

    private static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-09-29T09:00:00Z");

    private Path directory;
    private Path files;
    private DuckDBConnection live;
    private MeasureTable measures;
    private MeasureHistory history;

    @Before
    public void open() throws Exception {
        directory = Files.createTempDirectory("export");
        files = directory.resolve("lake");
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

    private void publish(Instant at, String id, boolean full, double value) {
        MeasureRefresh refresh = MeasureRefresh.at(at, id);
        var batch = measures.batch().record(3).put(value, "x", "5y").build();
        if (full) {
            refresh.full(measures, batch);
        }
        else {
            refresh.increment(measures, batch);
        }
        refresh.commit(live);
        history.ship(live, measures);
    }

    private List<String> read(MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        Rows result = query.run(live.duplicate());
        result.forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=').append(row.get(column));
            }
            rows.add(text.toString());
        });
        return rows;
    }

    private long countInFiles(String... locations) {
        return Sql.queryLong(live, "SELECT count(*) FROM " + MeasureTable.readParquet(List.of(locations)), List.of());
    }

    @Test
    public void exportingWritesAFilePerDayOfRefresh() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(TUESDAY, "tue", false, 12);

        MeasureHistory.Exported exported = history.export(live, measures, files.toString());

        assertEquals(2, exported.files().size());
        assertEquals(2, exported.refreshes());
        assertEquals(2, exported.contributions());
        assertTrue(exported.files().get(0), exported.files().get(0).contains("measure=m/part=2026-09-28"));
        assertTrue(exported.files().get(1), exported.files().get(1).contains("measure=m/part=2026-09-29"));
    }

    @Test
    public void aFileHoldsTheContributionsWithTheirRefreshBesideThem() throws Exception {
        publish(MONDAY, "mon", true, 10);
        String file = history.export(live, measures, files.toString()).files().get(0);

        List<String> rows = new ArrayList<>();
        try (PreparedStatement statement = live.prepareStatement("SELECT record_id, a, point, value, refresh_id,"
                + " refreshed_at, writer, kind FROM " + MeasureTable.readParquet(List.of(file)));
             ResultSet found = statement.executeQuery()) {
            while (found.next()) {
                rows.add(found.getLong(1) + " " + found.getString(2) + "/" + found.getString(3) + "="
                        + found.getDouble(4) + " " + found.getString(5) + " " + found.getString(6) + " "
                        + found.getString(7) + " " + found.getString(8));
            }
        }
        assertEquals(List.of("3 x/5y=10.0 mon 2026-09-28 09:00:00.0 svc_a full"), rows);
    }

    @Test
    public void aFileCarriesTheMeasuresDefinition() throws Exception {
        publish(MONDAY, "mon", true, 10);
        String file = history.export(live, measures, files.toString()).files().get(0);

        MeasureTable described = MeasureTable.describedBy(live, file);

        assertEquals(List.of("a", "point"), described.getFields());
        assertEquals("m", described.getName());
    }

    @Test
    public void exportingTwiceWritesNothingTheSecondTime() throws Exception {
        publish(MONDAY, "mon", true, 10);
        history.export(live, measures, files.toString());

        MeasureHistory.Exported again = history.export(live, measures, files.toString());

        assertEquals(List.of(), again.files());
        assertEquals(0, again.contributions());
    }

    @Test
    public void refreshesAfterAnExportAreExportedNext() throws Exception {
        publish(MONDAY, "mon", true, 10);
        history.export(live, measures, files.toString());
        publish(MONDAY.plusSeconds(3600), "mon-2", false, 12);

        MeasureHistory.Exported second = history.export(live, measures, files.toString());

        assertEquals(1, second.files().size());
        assertEquals(1, second.contributions());
        assertEquals(2, history.exportedFiles(live, measures).size());
    }

    @Test
    public void aDaysFilesFoldIntoOne() throws Exception {
        publish(MONDAY, "mon-1", true, 10);
        history.export(live, measures, files.toString());
        publish(MONDAY.plusSeconds(3600), "mon-2", false, 12);
        history.export(live, measures, files.toString());
        long before = countInFiles(files + "/measure=m/part=2026-09-28/*.parquet");

        String compacted = history.compact(live, measures, files.toString(), "2026-09-28");

        assertEquals(1, filesIn(files.resolve("measure=m").resolve("part=2026-09-28")).size());
        assertEquals(before, countInFiles(compacted));
        assertEquals(List.of("a", "point"), MeasureTable.describedBy(live, compacted).getFields());
    }

    @Test
    public void thereIsNothingToCompactInOneFile() throws Exception {
        publish(MONDAY, "mon", true, 10);
        history.export(live, measures, files.toString());

        assertNull(history.compact(live, measures, files.toString(), "2026-09-28"));
        assertNull(history.compact(live, measures, files.toString(), "1999-01-01"));
    }

    @Test
    public void compactionIsRefusedWhereItCannotDeleteWhatItReplaces() {
        try {
            history.compact(live, measures, "s3://bucket/lake", "2026-09-28");
            fail("expected a remote location to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("local path"));
        }
    }

    @Test
    public void pruningDropsWhatWasExportedBeforeTheLastFullSet() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(MONDAY.plusSeconds(60), "mon-2", false, 12);
        publish(TUESDAY, "tue", true, 20);          // a new baseline
        publish(TUESDAY.plusSeconds(60), "tue-2", false, 25);
        history.export(live, measures, files.toString());

        long dropped = history.prune(live, measures);

        assertEquals(2, dropped);
        assertEquals(2, history.contributionCount(live, measures));
        // What is left still answers as of now, because a full set is a baseline.
        assertEquals(List.of("a=x | point=5y | total=25.0"),
                read(measures.query().asOf(TUESDAY.plusSeconds(120), history).rows("a", "point")));
    }

    @Test
    public void pruningNeverDropsWhatHasNotBeenExported() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(TUESDAY, "tue", true, 20);

        assertEquals(0, history.prune(live, measures));
        assertEquals(2, history.contributionCount(live, measures));
    }

    @Test
    public void whatWasPrunedIsStillInTheFiles() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(TUESDAY, "tue", true, 20);
        List<String> written = history.export(live, measures, files.toString()).files();
        history.prune(live, measures);

        assertEquals(2, countInFiles(written.toArray(new String[0])));
    }

    @Test
    public void aPointThePruneDroppedIsRefusedRatherThanAnsweredWrong() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(TUESDAY, "tue", true, 20);
        history.export(live, measures, files.toString());
        history.prune(live, measures);

        try {
            read(measures.query().asOf(MONDAY, history).rows("a", "point"));
            fail("expected a pruned point to be refused");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("pruned from the history"));
        }
        // Tuesday's baseline is still there, so that still answers.
        assertEquals(List.of("a=x | point=5y | total=20.0"),
                read(measures.query().asOf(TUESDAY, history).rows("a", "point")));
    }

    @Test
    public void aPrunedPointStillReadsFromTheExportedFiles() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(MONDAY.plusSeconds(60), "mon-2", false, 12);
        publish(TUESDAY, "tue", true, 20);
        List<String> written = history.export(live, measures, files.toString()).files();
        history.prune(live, measures);

        assertEquals(List.of("a=x | point=5y | total=10.0"),
                read(measures.query().asOf(MONDAY, history).from(written.toArray(new String[0]))
                        .rows("a", "point")));
        assertEquals(List.of("a=x | point=5y | total=12.0"),
                read(measures.query().asOf(MONDAY.plusSeconds(60), history).from(written.toArray(new String[0]))
                        .rows("a", "point")));
    }

    @Test
    public void whatIsInBothTheHistoryAndTheFilesIsCountedOnce() throws Exception {
        publish(MONDAY, "mon", true, 10);
        List<String> written = history.export(live, measures, files.toString()).files();

        // Nothing pruned: the refresh is in the history and in the files at the same time.
        assertEquals(List.of("a=x | point=5y | total=10.0"),
                read(measures.query().asOf(MONDAY, history).from(written.toArray(new String[0]))
                        .rows("a", "point")));
    }

    @Test
    public void theLiveStateCanBeRebuiltFromTheHistory() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(MONDAY.plusSeconds(60), "mon-2", false, 12);
        MeasureRefresh.at(MONDAY.plusSeconds(120), "mon-3").increment(measures, measures.batch()
                .record(4).put(7, "y", "10y").build()).commit(live);
        history.ship(live, measures);
        List<String> before = read(measures.query().rows("a", "point"));

        // Something has gone wrong with the live database, so it is thrown away.
        Sql.execute(live, "DELETE FROM " + Sql.quote(measures.getValueTable()));
        long values = history.rebuild(live, measures);

        assertEquals(2, values);
        assertEquals(before, read(measures.query().rows("a", "point")));
    }

    @Test
    public void rebuildingWorksWithAnEmptyDictionaryToo() throws Exception {
        publish(MONDAY, "mon", true, 10);
        Sql.execute(live, "DELETE FROM " + Sql.quote(measures.getValueTable()));
        Sql.execute(live, "DELETE FROM " + Sql.quote(measures.getKeyTable()));
        MeasureTable fresh = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();

        assertEquals(1, history.rebuild(live, fresh));
        assertEquals(List.of("a=x | point=5y | total=10.0"), read(fresh.query().rows("a", "point")));
    }

    @Test
    public void rebuildingToAnEarlierPointGivesWhatStoodThen() throws Exception {
        publish(MONDAY, "mon", true, 10);
        publish(TUESDAY, "tue", false, 12);

        history.rebuild(live, measures, MONDAY.plusSeconds(1));

        assertEquals(List.of("a=x | point=5y | total=10.0"), read(measures.query().rows("a", "point")));
    }

    @Test
    public void rebuildingIsRefusedWhileAnythingIsWaitingToBeShipped() throws Exception {
        publish(MONDAY, "mon", true, 10);
        MeasureRefresh.at(TUESDAY, "tue").increment(measures, measures.batch()
                .record(3).put(12, "x", "5y").build()).commit(live);   // not shipped

        try {
            history.rebuild(live, measures);
            fail("expected the unshipped outbox to stop it");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ship them before rebuilding"));
        }
    }

    private List<Path> filesIn(Path directory) throws Exception {
        try (var list = Files.list(directory)) {
            return list.filter(path -> path.toString().endsWith(".parquet")).sorted().toList();
        }
    }
}
