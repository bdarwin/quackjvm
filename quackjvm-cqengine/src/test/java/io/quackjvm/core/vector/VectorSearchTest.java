package io.quackjvm.core.vector;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VectorSearchTest {

    public record Doc(Integer id, String category, float[] embedding) {
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

    private ColumnarLayout<Doc> layoutOf(int dimensions) {
        return ColumnarLayout.builder(Doc.class)
                .column("id", Integer.class, Doc::id)
                .column("category", String.class, Doc::category)
                .vectorColumn("embedding", dimensions, Doc::embedding)
                .rowFactory(values -> new Doc((Integer) values[0], (String) values[1], (float[]) values[2]))
                .build();
    }

    private void createDocs(List<Doc> docs, int dimensions) {
        ColumnarLayout<Doc> layout = layoutOf(dimensions);
        TableWriter writer = new TableWriter("doc", layout.toColumnDefs(), false, 16);
        writer.createTable(connection, true);
        List<Object[]> rows = new ArrayList<>();
        for (Doc doc : docs) {
            rows.add(layout.toRow(doc));
        }
        writer.write(connection, rows.iterator(), false);
    }

    @Test
    public void theNearestVectorComesFirst() {
        createDocs(List.of(
                new Doc(1, "a", new float[] {1f, 0f, 0f, 0f}),
                new Doc(2, "a", new float[] {0.9f, 0.1f, 0f, 0f}),
                new Doc(3, "b", new float[] {0f, 0f, 0f, 1f})), 4);

        List<VectorSearch.Match> top = VectorSearch.on(connection, "doc", "embedding")
                .identifiedBy("id")
                .topK(new float[] {1f, 0f, 0f, 0f}, 2);

        assertEquals(2, top.size());
        assertEquals(1, top.get(0).id());
        assertEquals(1.0, top.get(0).score(), 1e-6);
        assertEquals(2, top.get(1).id());
        assertTrue(top.get(1).score() > 0.9);
    }

    @Test
    public void aFilterNarrowsWhatIsSearched() {
        createDocs(List.of(
                new Doc(1, "news", new float[] {1f, 0f}),
                new Doc(2, "blog", new float[] {1f, 0f}),
                new Doc(3, "news", new float[] {0f, 1f})), 2);

        List<VectorSearch.Match> top = VectorSearch.on(connection, "doc", "embedding")
                .identifiedBy("id")
                .topK(new float[] {1f, 0f}, 5, "category = ?", "news");

        assertEquals(2, top.size());
        assertEquals(1, top.get(0).id());
        assertEquals(3, top.get(1).id());
    }

    @Test
    public void distanceSortsTheOtherWay() {
        createDocs(List.of(
                new Doc(1, "a", new float[] {0f, 0f}),
                new Doc(2, "a", new float[] {3f, 4f})), 2);

        List<VectorSearch.Match> nearest = VectorSearch.on(connection, "doc", "embedding")
                .identifiedBy("id")
                .metric(VectorSearch.Metric.EUCLIDEAN)
                .topK(new float[] {0f, 0f}, 2);

        assertEquals(1, nearest.get(0).id());
        assertEquals(0.0, nearest.get(0).score(), 1e-6);
        assertEquals(5.0, nearest.get(1).score(), 1e-6);
    }

    @Test
    public void innerProductIsNotNormalised() {
        createDocs(List.of(
                new Doc(1, "a", new float[] {1f, 1f}),
                new Doc(2, "a", new float[] {10f, 10f})), 2);

        List<VectorSearch.Match> top = VectorSearch.on(connection, "doc", "embedding")
                .identifiedBy("id")
                .metric(VectorSearch.Metric.INNER_PRODUCT)
                .topK(new float[] {1f, 1f}, 2);

        // The longer vector wins, which is the difference from cosine.
        assertEquals(2, top.get(0).id());
        assertEquals(20.0, top.get(0).score(), 1e-6);
    }

    @Test
    public void withoutAnIdColumnTheAnswerIsStillOrdered() {
        createDocs(List.of(
                new Doc(1, "a", new float[] {0f, 1f}),
                new Doc(2, "a", new float[] {1f, 0f})), 2);

        List<VectorSearch.Match> top = VectorSearch.on(connection, "doc", "embedding")
                .topK(new float[] {1f, 0f}, 1);

        assertEquals(1, top.size());
        assertEquals(1.0, top.get(0).score(), 1e-6);
    }

    @Test
    public void aListColumnSaysWhatToDoInstead() {
        Sql.execute(connection, "CREATE TABLE doc (id INTEGER, embedding FLOAT[])");
        Sql.execute(connection, "INSERT INTO doc VALUES (1, [1.0, 0.0])");

        try {
            VectorSearch.on(connection, "doc", "embedding").identifiedBy("id")
                    .topK(new float[] {1f, 0f}, 1);
            fail("expected a list column to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("vectorColumn"));
        }
    }

    @Test
    public void aQueryOfTheWrongLengthIsRefused() {
        createDocs(List.of(new Doc(1, "a", new float[] {1f, 0f, 0f, 0f})), 4);

        try {
            VectorSearch.on(connection, "doc", "embedding").identifiedBy("id").topK(new float[] {1f, 0f}, 1);
            fail("expected the wrong length to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("holds 4 values"));
        }
    }

    @Test
    public void theSqlIsThereToJoinYourOwnTablesTo() {
        createDocs(List.of(new Doc(1, "a", new float[] {1f, 0f})), 2);

        String sql = VectorSearch.on(connection, "doc", "embedding").identifiedBy("id")
                .sql(new float[] {1f, 0f}, 5, "category = ?");

        assertTrue(sql, sql.contains("array_cosine_similarity"));
        assertTrue(sql, sql.contains("::FLOAT[2]"));
        assertTrue(sql, sql.contains("ORDER BY score DESC"));
        assertTrue(sql, sql.contains("LIMIT 5"));
    }

    @Test
    public void whetherAnIndexCanBeUsedIsSaidPlainly() {
        String reason = VectorSearch.unavailableReason(connection);

        if (reason == null) {
            assertTrue(VectorSearch.isIndexAvailable(connection));
            assertEquals(null, VectorSearch.on(connection, "doc", "embedding").createIndex());
        }
        else {
            // Which is the case on a machine where nobody has installed vss, including this one.
            assertTrue(reason, reason.contains("vss"));
            assertTrue(reason, reason.contains("Exact search works without it"));
            createDocs(List.of(new Doc(1, "a", new float[] {1f, 0f})), 2);
            assertEquals(reason, VectorSearch.on(connection, "doc", "embedding").createIndex());
        }
    }

    @Test
    public void whatAnExactSearchCosts() {
        // 768 dimensions, the usual size of a sentence embedding. Generated row by row: 100,000 of
        // them is 293 MB of floats.
        int dimensions = 768;
        Random random = new Random(42);
        for (int rows : new int[] {10_000, 100_000}) {
            ColumnarLayout<Doc> layout = layoutOf(dimensions);
            Sql.execute(connection, "DROP TABLE IF EXISTS doc");
            TableWriter writer = new TableWriter("doc", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);
            final int count = rows;
            writer.write(connection, new Iterator<Object[]>() {
                private int written;

                @Override
                public boolean hasNext() {
                    return written < count;
                }

                @Override
                public Object[] next() {
                    float[] embedding = new float[dimensions];
                    for (int i = 0; i < dimensions; i++) {
                        embedding[i] = random.nextFloat();
                    }
                    return layout.toRow(new Doc(written++, "c" + (written % 5), embedding));
                }
            }, false);

            float[] query = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                query[i] = random.nextFloat();
            }
            VectorSearch search = VectorSearch.on(connection, "doc", "embedding").identifiedBy("id");
            search.topK(query, 10);
            long[] timings = new long[7];
            for (int i = 0; i < timings.length; i++) {
                long started = System.nanoTime();
                List<VectorSearch.Match> top = search.topK(query, 10);
                timings[i] = System.nanoTime() - started;
                assertEquals(10, top.size());
            }
            java.util.Arrays.sort(timings);
            System.out.printf("exact top-10 over %,d vectors of %d dimensions: %.1f ms%n", rows, dimensions,
                    timings[timings.length / 2] / 1e6);
        }
    }

    @Test
    public void howItScalesWithRowsAtSmallerDimensions() {
        // A million vectors of 768 floats is 3 GB before DuckDB sees any of them, so the row-count
        // scaling is measured at 128 dimensions instead - 512 MB at a million.
        int dimensions = 128;
        Random random = new Random(7);
        for (int rows : new int[] {100_000, 1_000_000}) {
            ColumnarLayout<Doc> layout = layoutOf(dimensions);
            Sql.execute(connection, "DROP TABLE IF EXISTS doc");
            TableWriter writer = new TableWriter("doc", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);
            final int count = rows;
            writer.write(connection, new Iterator<Object[]>() {
                private int written;

                @Override
                public boolean hasNext() {
                    return written < count;
                }

                @Override
                public Object[] next() {
                    float[] embedding = new float[dimensions];
                    for (int i = 0; i < dimensions; i++) {
                        embedding[i] = random.nextFloat();
                    }
                    return layout.toRow(new Doc(written++, "c" + (written % 5), embedding));
                }
            }, false);

            float[] query = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                query[i] = random.nextFloat();
            }
            VectorSearch search = VectorSearch.on(connection, "doc", "embedding").identifiedBy("id");
            search.topK(query, 10);
            long[] timings = new long[5];
            for (int i = 0; i < timings.length; i++) {
                long started = System.nanoTime();
                search.topK(query, 10);
                timings[i] = System.nanoTime() - started;
            }
            java.util.Arrays.sort(timings);
            System.out.printf("exact top-10 over %,d vectors of %d dimensions: %.1f ms%n", rows, dimensions,
                    timings[timings.length / 2] / 1e6);
        }
    }
}
