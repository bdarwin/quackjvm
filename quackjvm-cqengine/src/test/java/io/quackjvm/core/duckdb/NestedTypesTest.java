package io.quackjvm.core.duckdb;

import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NestedTypesTest {

    public record Owner(String name, Integer age, LocalDate since) {
    }

    /** Every nested shape in one record. */
    public record Doc(Integer id, List<String> tags, List<Integer> scores, Map<String, Integer> counts,
                      Owner owner, float[] embedding) {
    }

    public record Vectors(Integer id, float[] embedding) {
    }

    private DuckDBConnection connection;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private <T> TableWriter writerFor(String table, ColumnarLayout<T> layout) {
        TableWriter writer = new TableWriter(table, layout.toColumnDefs(), false, 16);
        writer.createTable(connection, true);
        return writer;
    }

    private <T> void write(TableWriter writer, ColumnarLayout<T> layout, List<T> objects) {
        List<Object[]> rows = new ArrayList<>();
        for (T object : objects) {
            rows.add(layout.toRow(object));
        }
        writer.write(connection, rows.iterator(), false);
    }

    private String sqlTypeOf(String table, String column) throws java.sql.SQLException {
        return Rows.of(connection.duplicate(), "SELECT data_type FROM duckdb_columns() WHERE table_name = '"
                + table + "' AND column_name = '" + column + "'").scalar(String.class);
    }

    @Test
    public void aRecordSaysWhatItsNestedColumnsHoldWithoutBeingTold() throws Exception {
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        writerFor("doc", layout);

        assertEquals("VARCHAR[]", sqlTypeOf("doc", "tags"));
        assertEquals("INTEGER[]", sqlTypeOf("doc", "scores"));
        assertEquals("MAP(VARCHAR, INTEGER)", sqlTypeOf("doc", "counts"));
        assertEquals("STRUCT(\"name\" VARCHAR, age INTEGER, since DATE)", sqlTypeOf("doc", "owner"));
        // A float[] does not say how long it is, so it is a list.
        assertEquals("FLOAT[]", sqlTypeOf("doc", "embedding"));
    }

    @Test
    public void everyNestedShapeRoundTrips() throws Exception {
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        TableWriter writer = writerFor("doc", layout);
        Doc one = new Doc(1, List.of("a", "b"), List.of(10, 20, 30),
                new LinkedHashMap<>(Map.of("x", 7)), new Owner("ann", 41, LocalDate.of(2020, 1, 2)),
                new float[] {1.5f, 2.5f});

        write(writer, layout, List.of(one));
        List<Doc> back = Rows.of(connection.duplicate(), "SELECT * FROM doc").records(Doc.class);

        assertEquals(1, back.size());
        Doc read = back.get(0);
        assertEquals(List.of("a", "b"), read.tags());
        assertEquals(List.of(10, 20, 30), read.scores());
        assertEquals(Map.of("x", 7), read.counts());
        assertEquals(new Owner("ann", 41, LocalDate.of(2020, 1, 2)), read.owner());
        assertArrayEquals(new float[] {1.5f, 2.5f}, read.embedding(), 1e-6f);
    }

    @Test
    public void nullsAndEmptiesSurviveAtEveryLevel() throws Exception {
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        TableWriter writer = writerFor("doc", layout);
        List<Doc> written = List.of(
                new Doc(1, List.of(), List.of(), Map.of(), new Owner("ann", 41, null), new float[0]),
                new Doc(2, Arrays.asList("a", null, "c"), Arrays.asList(1, null), null,
                        new Owner(null, null, null), new float[] {0f}));

        write(writer, layout, written);
        List<Doc> back = Rows.of(connection.duplicate(), "SELECT * FROM doc ORDER BY id").records(Doc.class);

        assertEquals(List.of(), back.get(0).tags());
        assertEquals(Map.of(), back.get(0).counts());
        assertEquals(0, back.get(0).embedding().length);
        assertNull(back.get(0).owner().since());
        assertEquals(Arrays.asList("a", null, "c"), back.get(1).tags());
        assertEquals(Arrays.asList(1, null), back.get(1).scores());
        assertNull(back.get(1).counts());
        assertEquals(new Owner(null, null, null), back.get(1).owner());
    }

    @Test
    public void aWholeColumnCanBeNull() throws Exception {
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        TableWriter writer = writerFor("doc", layout);

        write(writer, layout, List.of(new Doc(1, null, null, null, null, null)));
        Doc back = Rows.of(connection.duplicate(), "SELECT * FROM doc").records(Doc.class).get(0);

        assertNull(back.tags());
        assertNull(back.counts());
        assertNull(back.owner());
        assertNull(back.embedding());
    }

    @Test
    public void aFixedSizeVectorIsDeclaredAsOne() throws Exception {
        ColumnarLayout<Vectors> layout = ColumnarLayout.builder(Vectors.class)
                .column("id", Integer.class, Vectors::id)
                .vectorColumn("embedding", 4, Vectors::embedding)
                .rowFactory(values -> new Vectors((Integer) values[0], (float[]) values[1]))
                .build();
        TableWriter writer = writerFor("vec", layout);

        write(writer, layout, List.of(new Vectors(1, new float[] {1f, 2f, 3f, 4f})));

        assertEquals("FLOAT[4]", sqlTypeOf("vec", "embedding"));
        assertArrayEquals(new float[] {1f, 2f, 3f, 4f},
                Rows.of(connection.duplicate(), "SELECT * FROM vec").records(Vectors.class).get(0).embedding(),
                1e-6f);
        // And DuckDB's own array functions work on it, which is why the size matters.
        assertEquals(1.0, Rows.of(connection.duplicate(), "SELECT array_cosine_similarity(embedding,"
                + " [1.0, 2.0, 3.0, 4.0]::FLOAT[4]) FROM vec").scalar(Double.class), 1e-6);
    }

    @Test
    public void aVectorOfTheWrongSizeIsRefusedWithWhatIsWrong() {
        ColumnarLayout<Vectors> layout = ColumnarLayout.builder(Vectors.class)
                .column("id", Integer.class, Vectors::id)
                .vectorColumn("embedding", 4, Vectors::embedding)
                .rowFactory(values -> new Vectors((Integer) values[0], (float[]) values[1]))
                .build();
        TableWriter writer = writerFor("vec", layout);

        try {
            write(writer, layout, List.of(new Vectors(1, new float[] {1f, 2f})));
            fail("expected the wrong size to be refused");
        }
        catch (RuntimeException expected) {
            Throwable root = expected;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage(), root.getMessage().contains("vectors of 4 values"));
        }
    }

    @Test
    public void theBuilderCanSayWhatARawCollectionHolds() throws Exception {
        record Bag(Integer id, List<String> things) {
        }
        ColumnarLayout<Bag> layout = ColumnarLayout.builder(Bag.class)
                .column("id", Integer.class, Bag::id)
                .listColumn("things", String.class, Bag::things)
                .rowFactory(values -> new Bag((Integer) values[0], (List<String>) values[1]))
                .build();
        TableWriter writer = writerFor("bag", layout);

        write(writer, layout, List.of(new Bag(1, List.of("x", "y"))));

        assertEquals("VARCHAR[]", sqlTypeOf("bag", "things"));
        assertEquals(List.of("x", "y"),
                Rows.of(connection.duplicate(), "SELECT * FROM bag").records(Bag.class).get(0).things());
    }

    @Test
    public void sqlSeesInsideTheNestedValues() throws Exception {
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        TableWriter writer = writerFor("doc", layout);
        write(writer, layout, List.of(
                new Doc(1, List.of("a", "b"), List.of(1), Map.of("x", 7),
                        new Owner("ann", 41, null), new float[] {1f}),
                new Doc(2, List.of("a"), List.of(2), Map.of("x", 9),
                        new Owner("bob", 50, null), new float[] {2f})));

        assertEquals(2, Sql.queryLong(connection, "SELECT count(*) FROM doc WHERE list_contains(tags, 'a')",
                List.of()));
        assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM doc WHERE owner.age > 45", List.of()));
        assertEquals(16, Sql.queryLong(connection, "SELECT sum(counts['x']) FROM doc", List.of()));
        assertEquals(3, Sql.queryLong(connection, "SELECT count(*) FROM (SELECT unnest(tags) FROM doc)", List.of()));
    }

    @Test
    public void twoLevelsOfNestingAreRefusedRatherThanWrittenWrongly() {
        record Inner(Integer a) {
        }
        record Outer(Integer id, List<Inner> inners) {
        }
        try {
            ColumnarLayout.ofRecord(Outer.class);
            fail("expected a list of records to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("one level"));
        }

        record WithList(Integer a, List<String> things) {
        }
        record Holder(Integer id, WithList inner) {
        }
        try {
            ColumnarLayout.ofRecord(Holder.class);
            fail("expected a struct holding a list to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("one level"));
        }
    }

    @Test
    public void aRawCollectionInARecordSaysWhatToDoInstead() {
        record Raw(Integer id, List things) {
        }
        try {
            ColumnarLayout.ofRecord(Raw.class);
            fail("expected a raw List to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("listColumn"));
        }
    }

    @Test
    public void aSmallBatchGoesThroughTheAppenderBecauseAStatementCannotBindAList() throws Exception {
        // One row, which would normally take the prepared-statement path.
        ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
        TableWriter writer = writerFor("doc", layout);

        write(writer, layout, List.of(new Doc(1, List.of("only"), List.of(1), Map.of(),
                new Owner("ann", 1, null), new float[] {1f})));

        assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM doc", List.of()));
        assertEquals(List.of("only"),
                Rows.of(connection.duplicate(), "SELECT * FROM doc").records(Doc.class).get(0).tags());
    }

    @Test
    public void whatItCostsToWriteVectors() throws Exception {
        // The brief asks for 1,000,000 rows of FLOAT[768]. That is 3 GB of floats before DuckDB sees
        // any of them, so what is measured here is 100,000 - 307 MB - and the rate, which is what
        // scales. Generated row by row rather than held in a list, for the same reason.
        int dimensions = 768;
        int rows = 100_000;
        ColumnarLayout<Vectors> layout = ColumnarLayout.builder(Vectors.class)
                .column("id", Integer.class, Vectors::id)
                .vectorColumn("embedding", dimensions, Vectors::embedding)
                .rowFactory(values -> new Vectors((Integer) values[0], (float[]) values[1]))
                .build();
        TableWriter writer = writerFor("vec", layout);

        long started = System.nanoTime();
        writer.write(connection, new java.util.Iterator<Object[]>() {
            private int written;

            @Override
            public boolean hasNext() {
                return written < rows;
            }

            @Override
            public Object[] next() {
                float[] embedding = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    embedding[i] = (written + i) % 1000 / 1000f;
                }
                return layout.toRow(new Vectors(written++, embedding));
            }
        }, false);
        double took = (System.nanoTime() - started) / 1e9;

        assertEquals(rows, Sql.queryLong(connection, "SELECT count(*) FROM vec", List.of()));
        double megabytes = (double) rows * dimensions * 4 / (1024 * 1024);
        System.out.printf("wrote %,d rows of FLOAT[%d] (%.0f MB of floats) in %.2f s: %,.0f rows/s, %.0f MB/s%n",
                rows, dimensions, megabytes, took, rows / took, megabytes / took);
    }
}
