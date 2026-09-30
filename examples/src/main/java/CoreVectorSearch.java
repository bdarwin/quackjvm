/*
 * The rows whose vector is nearest a given one - what an agent's memory needs, and what a
 * recommendation is underneath.
 *
 * DuckDB's array functions do the work: array_cosine_similarity, array_distance,
 * array_inner_product. None of them needs an extension. The column has to be a fixed-size array -
 * FLOAT[768] rather than FLOAT[] - which is what ColumnarLayout.vectorColumn declares.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreVectorSearch.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.vector.VectorSearch;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class CoreVectorSearch {

    public record Note(Integer id, String text, String topic, float[] embedding) {
    }

    /** Stands in for an embedding model: three axes, so the numbers can be read. */
    static float[] embed(double weather, double food, double code) {
        float[] vector = {(float) weather, (float) food, (float) code};
        double length = Math.sqrt(weather * weather + food * food + code * code);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            ColumnarLayout<Note> layout = ColumnarLayout.builder(Note.class)
                    .column("id", Integer.class, Note::id)
                    .column("text", String.class, Note::text)
                    .column("topic", String.class, Note::topic)
                    .vectorColumn("embedding", 3, Note::embedding)
                    .rowFactory(values -> new Note((Integer) values[0], (String) values[1], (String) values[2],
                            (float[]) values[3]))
                    .build();
            TableWriter writer = new TableWriter("note", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);
            write(connection, writer, layout, List.of(
                    new Note(1, "it rained all week", "weather", embed(1, 0, 0)),
                    new Note(2, "the forecast says sun", "weather", embed(0.9, 0.1, 0)),
                    new Note(3, "a good recipe for bread", "food", embed(0, 1, 0)),
                    new Note(4, "sourdough takes patience", "food", embed(0.1, 0.9, 0)),
                    new Note(5, "the parser rejects it", "code", embed(0, 0, 1)),
                    new Note(6, "a cold snap and hot soup", "food", embed(0.5, 0.6, 0))));

            System.out.println("Nearest to a question about the weather");
            System.out.println("--------------------------------------");
            VectorSearch search = VectorSearch.on(connection, "note", "embedding").identifiedBy("text");
            for (VectorSearch.Match match : search.topK(embed(1, 0, 0), 3)) {
                System.out.printf("   %.3f  %s%n", match.score(), match.id());
            }

            System.out.println("\nThe same question, narrowed to one topic");
            System.out.println("----------------------------------------");
            for (VectorSearch.Match match : search.topK(embed(1, 0, 0), 3, "topic = ?", "food")) {
                System.out.printf("   %.3f  %s%n", match.score(), match.id());
            }

            System.out.println("\nThe three metrics, on the same question");
            System.out.println("--------------------------------------");
            for (VectorSearch.Metric metric : VectorSearch.Metric.values()) {
                VectorSearch.Match best = VectorSearch.on(connection, "note", "embedding")
                        .identifiedBy("text").metric(metric).topK(embed(0, 1, 0), 1).get(0);
                System.out.printf("   %-14s %.3f  %s%n", metric, best.score(), best.id());
            }

            System.out.println("\nThe SQL, for joining your own tables to");
            System.out.println("---------------------------------------");
            String sql = search.sql(embed(1, 0, 0), 3, "topic = ?");
            System.out.println("   " + sql.replace(", array", ",\n       array"));

            System.out.println("\nIs an index available here?");
            System.out.println("--------------------------");
            String reason = VectorSearch.unavailableReason(connection);
            System.out.println("   " + (reason == null ? "yes - vss is loaded, so createIndex() will build an HNSW index"
                    : reason));

            System.out.println("\nWhat an exact search costs");
            System.out.println("--------------------------");
            atScale();
        }
    }

    static void atScale() throws Exception {
        for (int[] shape : new int[][] {{10_000, 768}, {100_000, 768}, {100_000, 128}, {1_000_000, 128}}) {
            int rows = shape[0];
            int dimensions = shape[1];
            try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                ColumnarLayout<Note> layout = ColumnarLayout.builder(Note.class)
                        .column("id", Integer.class, Note::id)
                        .column("text", String.class, Note::text)
                        .column("topic", String.class, Note::topic)
                        .vectorColumn("embedding", dimensions, Note::embedding)
                        .rowFactory(values -> new Note((Integer) values[0], (String) values[1], (String) values[2],
                                (float[]) values[3]))
                        .build();
                TableWriter writer = new TableWriter("note", layout.toColumnDefs(), false, 16);
                writer.createTable(connection, true);
                Random random = new Random(42);
                long started = System.nanoTime();
                writer.write(connection, new Iterator<Object[]>() {
                    private int written;

                    @Override
                    public boolean hasNext() {
                        return written < rows;
                    }

                    @Override
                    public Object[] next() {
                        float[] embedding = new float[dimensions];
                        for (int i = 0; i < dimensions; i++) {
                            embedding[i] = random.nextFloat();
                        }
                        return layout.toRow(new Note(written++, "n", "t", embedding));
                    }
                }, false);
                double wrote = (System.nanoTime() - started) / 1e3 / rows;

                float[] query = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    query[i] = random.nextFloat();
                }
                VectorSearch search = VectorSearch.on(connection, "note", "embedding").identifiedBy("id");
                search.topK(query, 10);
                long[] timings = new long[7];
                for (int i = 0; i < timings.length; i++) {
                    long at = System.nanoTime();
                    search.topK(query, 10);
                    timings[i] = System.nanoTime() - at;
                }
                java.util.Arrays.sort(timings);
                System.out.printf("   %,9d vectors x %3d dimensions (%4.0f MB): top-10 in %6.1f ms"
                                + "   (written at %.1f us/row)%n",
                        rows, dimensions, (double) rows * dimensions * 4 / (1024 * 1024),
                        timings[timings.length / 2] / 1e6, wrote);
            }
        }
        System.out.println("   Every row is scored, so it grows with rows times dimensions - and 768 dimensions");
        System.out.println("   cost more per element than 128 do. A million of 768 is 3 GB of floats, which is why");
        System.out.println("   the row-count scaling above is measured at 128.");
    }

    static <T> void write(DuckDBConnection connection, TableWriter writer, ColumnarLayout<T> layout,
                          List<T> objects) {
        List<Object[]> rows = new ArrayList<>();
        for (T object : objects) {
            rows.add(layout.toRow(object));
        }
        writer.write(connection, rows.iterator(), false);
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * Nearest to a question about the weather
 * --------------------------------------
 *    1.000  it rained all week
 *    0.994  the forecast says sun
 *    0.640  a cold snap and hot soup
 *
 * The same question, narrowed to one topic
 * ----------------------------------------
 *    0.640  a cold snap and hot soup
 *    0.110  sourdough takes patience
 *    0.000  a good recipe for bread
 *
 * The three metrics, on the same question
 * --------------------------------------
 *    COSINE         1.000  a good recipe for bread
 *    EUCLIDEAN      0.000  a good recipe for bread
 *    INNER_PRODUCT  1.000  a good recipe for bread
 *
 * The SQL, for joining your own tables to
 * ---------------------------------------
 *    SELECT "text" AS id,
 *        array_cosine_similarity("embedding", [1.0, 0.0, 0.0]::FLOAT[3]) AS score FROM "note" WHERE topic = ? ORDER BY score DESC LIMIT 3
 *
 * Is an index available here?
 * --------------------------
 *    the vss extension is not installed. Install it once, outside quackjvm, with INSTALL vss - it needs the network, which a hardened database forbids. Exact search works without it.
 *
 * What an exact search costs
 * --------------------------
 *       10,000 vectors x 768 dimensions (  29 MB): top-10 in   15.8 ms   (written at 40.1 us/row)
 *      100,000 vectors x 768 dimensions ( 293 MB): top-10 in  109.1 ms   (written at 41.3 us/row)
 *      100,000 vectors x 128 dimensions (  49 MB): top-10 in   15.6 ms   (written at 7.1 us/row)
 *    1,000,000 vectors x 128 dimensions ( 488 MB): top-10 in   40.8 ms   (written at 7.0 us/row)
 *    Every row is scored, so it grows with rows times dimensions - and 768 dimensions
 *    cost more per element than 128 do. A million of 768 is 3 GB of floats, which is why
 *    the row-count scaling above is measured at 128.
 *
 * No extension was loaded for any of that: array_cosine_similarity, array_distance and
 * array_inner_product are built in. What needs the vss extension is the HNSW index, and this machine
 * has never installed one - so the honest answer above is the message, not a number.
 *
 * An exact search scores every row, and the cost follows rows times dimensions with a penalty for
 * width: a million 128-dimension vectors (488 MB) take 41 ms, where a hundred thousand 768-dimension
 * ones (293 MB) take 109. For a few hundred thousand vectors that is fast enough to need nothing else;
 * past that, an index is what to reach for, and createIndex() will build one once vss is installed.
 */
