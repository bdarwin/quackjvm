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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeasureExportTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private DuckDBConnection connection;
    private MeasureTable measures;
    private Path exported;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        measures = definition();
        measures.create(connection);
        measures.replace(connection, measures.batch()
                .record(1)
                    .put(1.0, "x", "5y", "U1")
                    .put(2.0, "x", "10y", "U1")
                    .put(4.0, "y", "5y", "U2")
                .record(2)
                    .put(0.5, "x", "5y", "U1")
                .build());
        exported = folder.getRoot().toPath().resolve("export");
        measures.export(connection, exported);
    }

    private static MeasureTable definition() {
        return MeasureTable.named("m").fields("a", "point", "unit").unit("unit")
                .order("point", "5y", "10y", "30y").build();
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private List<String> read(MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        Rows result = query.run(connection.duplicate());
        result.forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=').append(row.get(column));
            }
            rows.add(text.toString());
        });
        return rows;
    }

    @Test
    public void anExportIsAMeasureNotTwoLooseTables() throws Exception {
        assertTrue(Files.exists(exported.resolve("key.parquet")));
        assertTrue(Files.exists(exported.resolve("value.parquet")));
        assertTrue(Files.exists(exported.resolve("measure.parquet")));

        MeasureTable read = MeasureTable.describedBy(connection, exported);
        assertEquals("m", read.getName());
        assertEquals(List.of("a", "point", "unit"), read.getFields());
        assertEquals("unit", read.getUnitField());
        assertEquals(List.of("5y", "10y", "30y"), read.orderOf("point"));
    }

    @Test
    public void theFilesAnswerTheSameQuestionsWithoutBeingReadBackIn() throws Exception {
        assertEquals(read(measures.query().rows("a").columns("point").where("unit", "U1")),
                read(measures.query().from(exported).rows("a").columns("point").where("unit", "U1")));
        assertEquals(List.of("a=x | 5y=1.5 | 10y=2.0"),
                read(measures.query().from(exported).rows("a").columns("point").where("unit", "U1")));
    }

    @Test
    public void readingAnExportBackGivesTheSameAnswers() throws Exception {
        List<String> before = read(measures.query().rows("a", "unit").columns("point"));
        try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable there = definition();
            there.create(elsewhere);
            assertEquals(4, there.importFrom(elsewhere, exported));

            List<String> after = new ArrayList<>();
            there.query().rows("a", "unit").columns("point").run(elsewhere.duplicate())
                    .forEachRow((SqlRow row) -> {
                        StringBuilder text = new StringBuilder();
                        for (String column : row.getColumnNames()) {
                            text.append(text.length() == 0 ? "" : " | ").append(column).append('=')
                                    .append(row.get(column));
                        }
                        after.add(text.toString());
                    });
            assertEquals(before, after);
            assertEquals(4, there.valueCount(elsewhere));
            // Three distinct keys: both records share (x, 5y, U1).
            assertEquals(3, there.keyCount(elsewhere));
        }
    }

    /** Keys are matched by their fields, so an export can arrive where the dictionary differs. */
    @Test
    public void keysAreMatchedByWhatTheyAreNotByTheirOldIds() throws Exception {
        try (DuckDBConnection elsewhere = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            MeasureTable there = definition();
            there.create(elsewhere);
            // A dictionary built in another order, so the shared key has a different id.
            there.replace(elsewhere, there.batch().record(9)
                    .put(7.0, "z", "30y", "U1")
                    .put(8.0, "x", "5y", "U1")
                    .build());
            there.importFrom(elsewhere, exported);

            // Two of its own, and two more from the export that it did not have.
            assertEquals(4, there.keyCount(elsewhere));
            assertEquals(List.of("a=x | 5y=9.5 | 10y=2.0 | 30y=null",
                            "a=z | 5y=null | 10y=null | 30y=7.0"),
                    readFrom(elsewhere, there.query().rows("a").columns("point").where("unit", "U1")));
        }
    }

    @Test
    public void importingReplacingLeavesThoseRecordsAsTheyWereExported() throws Exception {
        measures.replace(connection, measures.batch().record(1).put(99.0, "x", "5y", "U1").build());
        assertEquals(List.of("a=x | total=99.5"), read(measures.query().rows("a").where("unit", "U1")));

        measures.importReplacing(connection, exported);
        assertEquals(List.of("a=x | total=3.5"), read(measures.query().rows("a").where("unit", "U1")));
    }

    @Test
    public void importingWithoutReplacingAddsToWhatIsThere() throws Exception {
        measures.importFrom(connection, exported);
        assertEquals(8, measures.valueCount(connection));
        assertEquals(List.of("a=x | total=7.0"), read(measures.query().rows("a").where("unit", "U1")));
    }

    @Test
    public void anExportOfAnotherMeasureIsRefused() throws Exception {
        MeasureTable other = MeasureTable.named("m").fields("a", "b").build();
        other.create(connection);
        try {
            other.importFrom(connection, exported);
            fail("expected a complaint");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not [a, b]"));
        }
    }

    /** One flat table for anything that does not know this layout. */
    @Test
    public void theFlatExportIsAValuePerRowWithItsFields() throws Exception {
        Path flat = folder.getRoot().toPath().resolve("flat");
        measures.exportFlat(connection, flat);
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT record_id, a, point, unit, value FROM read_parquet('"
                     + flat.resolve("flat.parquet") + "') ORDER BY record_id, a, point")) {
            List<String> read = new ArrayList<>();
            while (rows.next()) {
                read.add(rows.getLong(1) + " " + rows.getString(2) + " " + rows.getString(3) + " "
                        + rows.getString(4) + " " + rows.getDouble(5));
            }
            assertEquals(List.of("1 x 10y U1 2.0", "1 x 5y U1 1.0", "1 y 5y U2 4.0", "2 x 5y U1 0.5"), read);
        }
    }

    @Test
    public void theFlatExportCanBeSplitByAField() throws Exception {
        Path flat = folder.getRoot().toPath().resolve("split");
        measures.exportFlat(connection, flat, "unit");
        assertTrue(Files.isDirectory(flat.resolve("unit=U1")));
        assertTrue(Files.isDirectory(flat.resolve("unit=U2")));
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM read_parquet('"
                     + flat.resolve("unit=U1") + "/*.parquet')")) {
            rows.next();
            assertEquals(3, rows.getLong(1));
        }
    }

    private List<String> readFrom(DuckDBConnection other, MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        query.run(other.duplicate()).forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=').append(row.get(column));
            }
            rows.add(text.toString());
        });
        return rows;
    }
}
