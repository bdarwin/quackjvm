package io.quackjvm.core.measure;

import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The cycle the measures are for: keep adding under a part label, archive a part to one
 * self-contained file, move that file wherever you like, and later restore days - or ask the files
 * directly - even after the measure has grown fields.
 */
public class MeasureArchiveTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private DuckDBConnection connection;
    private MeasureTable measures;
    private String store;

    private static MeasureTable definition(String writer) {
        return MeasureTable.named("m").fields("a", "point", "unit").unit("unit")
                .order("point", "5y", "10y", "30y").writtenBy(writer).build();
    }

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        store = folder.getRoot().toPath().resolve("store").toString();
        measures = definition("svc-a");
        measures.create(connection);
        measures.append(connection, measures.batch()
                .record(1).put(1.0, "x", "5y", "U1").put(2.0, "x", "10y", "U1")
                .record(2).put(0.5, "y", "5y", "U1")
                .build(), "2026-09-20");
        measures.append(connection, measures.batch()
                .record(1).put(3.0, "x", "30y", "U1")
                .build(), "2026-09-21");
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private List<String> read(MeasureQuery query) throws Exception {
        return readFrom(connection, query);
    }

    private static List<String> readFrom(DuckDBConnection where, MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        Rows result = query.run(where.duplicate());
        result.forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=').append(row.get(column));
            }
            rows.add(text.toString());
        });
        return rows;
    }

    // ---------- Parts ----------

    @Test
    public void everyWriteBelongsToAPart() {
        assertEquals(List.of(new MeasureTable.Part("2026-09-20", 3), new MeasureTable.Part("2026-09-21", 1)),
                measures.parts(connection));
    }

    @Test
    public void aQueryCanKeepToOnePart() throws Exception {
        // Part 20 holds x's 1.0 and 2.0 and y's 0.5; part 21 holds x's 3.0.
        assertEquals(List.of("a=x | total=3.0", "a=y | total=0.5"),
                read(measures.query().rows("a").parts("2026-09-20").where("unit", "U1")));
        assertEquals(List.of("a=x | total=6.0", "a=y | total=0.5"),
                read(measures.query().rows("a").where("unit", "U1")));
    }

    @Test
    public void everyValueRemembersWhoWroteItAndWhen() throws Exception {
        MeasureTable other = definition("svc-b");
        other.append(connection, other.batch().record(3).put(9.0, "z", "5y", "U1").build(), "2026-09-20");
        assertEquals(List.of("written_by=svc-a", "written_by=svc-b"), values(connection,
                "SELECT DISTINCT 'written_by=' || written_by FROM m_value ORDER BY 1"));
        assertEquals(List.of("a=z | total=9.0"),
                read(measures.query().rows("a").writtenBy("svc-b").where("unit", "U1")));
    }

    // ---------- Archiving ----------

    @Test
    public void archivingWritesOneFileAndEmptiesThatPart() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        assertTrue(file, file.endsWith(".parquet"));
        assertTrue(file, file.contains("/measure=m/part=2026-09-20/"));
        assertTrue(file, file.contains("/svc-a-"));
        assertTrue(Files.exists(Path.of(file)));
        assertEquals(List.of(new MeasureTable.Part("2026-09-21", 1)), measures.parts(connection));
        assertEquals(1, measures.valueCount(connection));
    }

    @Test
    public void theFileSaysWhatTheMeasureIs() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        MeasureTable read = MeasureTable.describedBy(connection, file);
        assertEquals("m", read.getName());
        assertEquals(List.of("a", "point", "unit"), read.getFields());
        assertEquals("unit", read.getUnitField());
        assertEquals(List.of("5y", "10y", "30y"), read.orderOf("point"));
        assertEquals("svc-a", read.getWrittenBy());
    }

    /** A value per row with its fields beside it: readable by anything, with no dictionary to decode. */
    @Test
    public void theFileIsAnOrdinaryTable() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        assertEquals(List.of("record_id", "a", "point", "unit", "value", "part", "written_by", "written_at"),
                values(connection, "SELECT column_name FROM (DESCRIBE SELECT * FROM read_parquet('" + file
                        + "', hive_partitioning = false))"));
        // The path says measure=m/part=2026-09-20, so a data lake reading it sees those as columns
        // to prune by. quackjvm's own reads turn that off, or the file's own part column would be
        // read back from the path as a date.
        assertEquals(List.of("m|2026-09-20"), values(connection, "SELECT DISTINCT measure || '|' || part FROM"
                + " read_parquet('" + file + "')"));
        assertEquals(List.of("1|x|10y|U1|2.0", "1|x|5y|U1|1.0", "2|y|5y|U1|0.5"), values(connection,
                "SELECT record_id || '|' || a || '|' || point || '|' || unit || '|' || value"
                        + " FROM read_parquet('" + file + "', hive_partitioning = false) ORDER BY 1"));
    }

    // ---------- Reading files back ----------

    @Test
    public void theFilesAnswerQuestionsWithoutBeingRestored() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        assertEquals(List.of("a=x | 5y=1.0 | 10y=2.0", "a=y | 5y=0.5 | 10y=null"),
                read(measures.query().from(file).rows("a").columns("point").where("unit", "U1")));
    }

    @Test
    public void manyDaysAtOnce() throws Exception {
        String first = measures.archive(connection, store, "2026-09-20");
        String second = measures.archive(connection, store, "2026-09-21");
        assertEquals(0, measures.valueCount(connection));
        assertEquals(List.of("a=x | 5y=1.0 | 10y=2.0 | 30y=3.0", "a=y | 5y=0.5 | 10y=null | 30y=null"),
                read(measures.query().from(first, second).rows("a").columns("point").where("unit", "U1")));
    }

    @Test
    public void restoringRebuildsTheTablesFromTheFileAlone() throws Exception {
        String first = measures.archive(connection, store, "2026-09-20");
        String second = measures.archive(connection, store, "2026-09-21");
        try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable restored = MeasureTable.restore(elsewhere, first, second);
            assertEquals(List.of("a", "point", "unit"), restored.getFields());
            assertEquals(4, restored.valueCount(elsewhere));
            assertEquals(List.of(new MeasureTable.Part("2026-09-20", 3), new MeasureTable.Part("2026-09-21", 1)),
                    restored.parts(elsewhere));
            assertEquals(List.of("a=x | 5y=1.0 | 10y=2.0 | 30y=3.0", "a=y | 5y=0.5 | 10y=null | 30y=null"),
                    readFrom(elsewhere, restored.query().rows("a").columns("point").where("unit", "U1")));
        }
    }

    @Test
    public void restoringTwiceLeavesTheSameThing() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        assertEquals(3, measures.restore(connection, List.of(file)));
        assertEquals(3, measures.restore(connection, List.of(file)));
        assertEquals(4, measures.valueCount(connection));
    }

    @Test
    public void keysAreMatchedByWhatTheyAreNotByTheirOldIds() throws Exception {
        String file = measures.archive(connection, store, "2026-09-20");
        try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable there = definition("svc-b");
            there.create(elsewhere);
            // A dictionary built in another order, so the shared key has a different id there.
            there.append(elsewhere, there.batch().record(9)
                    .put(7.0, "z", "30y", "U1").put(8.0, "x", "5y", "U1").build(), "2026-09-19");
            there.restore(elsewhere, List.of(file));
            assertEquals(List.of("a=x | 5y=9.0 | 10y=2.0 | 30y=null",
                            "a=y | 5y=0.5 | 10y=null | 30y=null",
                            "a=z | 5y=null | 10y=null | 30y=7.0"),
                    readFrom(elsewhere, there.query().rows("a").columns("point").where("unit", "U1")));
        }
    }

    // ---------- Fields added later ----------

    @Test
    public void filesWrittenBeforeAFieldExistedTakeItsDefault() throws Exception {
        String old = measures.archive(connection, store, "2026-09-20");

        MeasureTable grown = MeasureTable.named("m").fields("a", "point", "unit").field("kind", "n/a")
                .unit("unit").order("point", "5y", "10y", "30y").writtenBy("svc-a").build();
        grown.migrate(connection);
        grown.append(connection, grown.batch().record(4).put(6.0, "x", "5y", "U1", "k1").build(), "2026-09-22");

        // The old file has no "kind" at all; the new values do.
        // The old part holds x's 1.0 and y's 0.5 at 5y, both now reading as kind "n/a".
        assertEquals(List.of("kind=k1 | 5y=6.0", "kind=n/a | 5y=1.5"),
                read(grown.query().from(old, archiveOf(grown, "2026-09-22")).rows("kind").columns("point")
                        .where("unit", "U1").where("point", "5y")));
    }

    @Test
    public void anOlderFileRestoresIntoAMeasureThatHasGrown() throws Exception {
        String old = measures.archive(connection, store, "2026-09-20");
        MeasureTable grown = MeasureTable.named("m").fields("a", "point", "unit").field("kind", "n/a")
                .unit("unit").writtenBy("svc-a").build();
        grown.migrate(connection);
        assertEquals(3, grown.restore(connection, List.of(old)));
        assertEquals(List.of("kind=n/a | total=3.5"), read(grown.query().rows("kind").parts("2026-09-20")
                .where("unit", "U1")));
    }

    @Test
    public void migratingKeepsWhatWasAlreadyStored() throws Exception {
        MeasureTable grown = MeasureTable.named("m").fields("a", "point", "unit").field("kind", "n/a")
                .unit("unit").writtenBy("svc-a").build();
        grown.migrate(connection);
        assertEquals(List.of("a=x | kind=n/a | total=6.0", "a=y | kind=n/a | total=0.5"),
                read(grown.query().rows("a", "kind").where("unit", "U1")));
        assertEquals(4, grown.valueCount(connection));
    }

    @Test
    public void aFieldCannotJustDisappear() {
        MeasureTable shrunk = MeasureTable.named("m").fields("a", "point").build();
        try {
            shrunk.migrate(connection);
            fail("expected a refusal");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("[unit]"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("add together values"));
        }
    }

    @Test
    public void aNewFieldNeedsToSayWhatOlderValuesRead() {
        MeasureTable grown = MeasureTable.named("m").fields("a", "point", "unit", "kind").unit("unit").build();
        try {
            grown.migrate(connection);
            fail("expected a refusal");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'kind' is new"));
        }
    }

    // ---------- Two writers ----------

    @Test
    public void twoWritersAddUpUnlessTheReaderAsksForTheLatest() throws Exception {
        MeasureTable second = definition("svc-b");
        Thread.sleep(2);   // a later written_at
        second.append(connection, second.batch().record(1).put(10.0, "x", "5y", "U1").build(), "2026-09-20");

        assertEquals("both, added up", List.of("a=x | 5y=11.0 | 10y=2.0 | 30y=3.0"),
                read(measures.query().rows("a").columns("point").where("unit", "U1").where("a", "x")));
        assertEquals("the newer one only", List.of("a=x | 5y=10.0 | 10y=2.0 | 30y=3.0"),
                read(measures.query().latestPerKey().rows("a").columns("point").where("unit", "U1").where("a", "x")));
        assertEquals("one writer only", List.of("a=x | 5y=1.0 | 10y=2.0 | 30y=3.0"),
                read(measures.query().writtenBy("svc-a").rows("a").columns("point").where("unit", "U1").where("a", "x")));
    }

    @Test
    public void theSameChoicesHoldWhenReadingFiles() throws Exception {
        MeasureTable second = definition("svc-b");
        Thread.sleep(2);
        second.append(connection, second.batch().record(1).put(10.0, "x", "5y", "U1").build(), "2026-09-20");
        String file = measures.archive(connection, store, "2026-09-20");

        assertEquals(List.of("a=x | 5y=11.0 | 10y=2.0"),
                read(measures.query().from(file).rows("a").columns("point").where("unit", "U1").where("a", "x")));
        assertEquals(List.of("a=x | 5y=10.0 | 10y=2.0"),
                read(measures.query().from(file).latestPerKey().rows("a").columns("point")
                        .where("unit", "U1").where("a", "x")));
    }

    // ---------- Only what changed ----------

    @Test
    public void appendChangesWritesOnlyWhatDiffers() throws Exception {
        long before = measures.valueCount(connection);
        measures.appendChanges(connection, measures.batch()
                .record(1).put(1.0, "x", "5y", "U1").put(99.0, "x", "10y", "U1")
                .build(), "2026-09-22");

        assertEquals("the unchanged 1.0 was not written again", before + 1, measures.valueCount(connection));
        assertEquals(List.of("point=5y | total=1.5", "point=10y | total=101.0", "point=30y | total=3.0"),
                read(measures.query().rows("point").where("unit", "U1")));
    }

    @Test
    public void appendChangesWritesNothingWhenNothingChanged() throws Exception {
        long before = measures.valueCount(connection);
        measures.appendChanges(connection, measures.batch()
                .record(1).put(1.0, "x", "5y", "U1").put(2.0, "x", "10y", "U1")
                .build(), "2026-09-22");
        assertEquals(before, measures.valueCount(connection));
    }

    // ---------- Archiving by size ----------

    @Test
    public void aMeasureCanArchiveItselfWhenItGrowsTooLarge() throws Exception {
        Path file = folder.getRoot().toPath().resolve("sized.duckdb");
        List<String> handed = new ArrayList<>();
        try (DuckDBConnection sized = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + file)) {
            MeasureTable measure = MeasureTable.named("big").fields("a", "point")
                    .writtenBy("svc-a")
                    .archiveWhenLargerThan(512 * 1024)
                    .archiveTo(store)
                    .onArchive((part, where) -> handed.add(part + " -> " + Path.of(where).getFileName()))
                    .build();
            measure.create(sized);
            for (int day = 20; day <= 26; day++) {
                MeasureBatch.Builder batch = measure.batch();
                for (int record = 0; record < 60; record++) {
                    batch.record(day * 1000L + record);
                    for (int key = 0; key < 1_000; key++) {
                        batch.put(record + key / 10.0, "a" + key % 50, "p" + key);
                    }
                }
                measure.append(sized, batch.build(), "2026-09-" + day);
            }
            assertFalse("expected it to have archived something: " + handed, handed.isEmpty());
            assertTrue(handed.toString(), handed.get(0).startsWith("2026-09-20 -> svc-a-"));
            List<MeasureTable.Part> left = measure.parts(sized);
            assertTrue("the oldest parts should have gone: " + left, left.size() < 7);
            assertNotEquals("2026-09-20", left.get(0).name());
            assertTrue("and the newest must stay", left.get(left.size() - 1).name().equals("2026-09-26"));

            // What was archived is still readable, and restores.
            String archived = firstFileUnder(Path.of(store), "part=2026-09-20");
            assertEquals(60_000, measure.restore(sized, List.of(archived)));
        }
    }

    // ---------- Helpers ----------

    private String archiveOf(MeasureTable measure, String part) {
        return measure.archive(connection, store, part);
    }

    private static String firstFileUnder(Path root, String contains) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().contains(contains) && path.toString().endsWith(".parquet"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("no file under " + root)).toString();
        }
    }

    private static List<String> values(DuckDBConnection where, String sql) throws Exception {
        List<String> found = new ArrayList<>();
        try (Statement statement = where.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                found.add(rows.getString(1));
            }
        }
        return found;
    }
}
