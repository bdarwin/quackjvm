/*
 * Columns that hold more than one value: LIST, STRUCT, MAP, and an ARRAY of fixed size.
 *
 * The roadmap said this would need Arrow, because "the appender's native entry points are scalars
 * only". That was true of an older driver. In 1.5.5 the appender takes a Collection for a list, a Map
 * for a map, beginStruct/endStruct for a struct and a float[] for a fixed-size array - so quackjvm
 * writes them through the appender, with no Arrow and no extra dependency. This example is the
 * evidence.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreNestedTypes.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CoreNestedTypes {

    public record Owner(String name, Integer age, LocalDate since) {
    }

    /** Every nested shape in one record. The types say what they are; nothing else has to. */
    public record Doc(Integer id, String title, List<String> tags, Map<String, Integer> counts,
                      Owner owner, float[] embedding) {
    }

    public record Vector(Integer id, float[] embedding) {
    }

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
            TableWriter writer = new TableWriter("doc", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);

            System.out.println("What the record's own types became");
            System.out.println("---------------------------------");
            print(connection, "SELECT column_name, data_type FROM duckdb_columns() WHERE table_name = 'doc'"
                    + " ORDER BY column_index");

            List<Doc> docs = List.of(
                    new Doc(1, "first", List.of("java", "duckdb"), new LinkedHashMap<>(Map.of("views", 12)),
                            new Owner("ann", 41, LocalDate.of(2020, 1, 2)), new float[] {0.1f, 0.2f}),
                    new Doc(2, "second", Arrays.asList("java", null), new LinkedHashMap<>(Map.of("views", 3)),
                            new Owner("bob", null, null), new float[] {0.3f}),
                    new Doc(3, "third", List.of(), null, null, null));
            write(connection, writer, layout, docs);

            System.out.println("\nWritten and read back, including the empties and the nulls");
            System.out.println("---------------------------------------------------------");
            for (Doc doc : Rows.of(connection.duplicate(), "SELECT * FROM doc ORDER BY id").records(Doc.class)) {
                System.out.printf("   %d %-7s tags=%-16s counts=%-16s owner=%-34s embedding=%s%n",
                        doc.id(), doc.title(), doc.tags(), doc.counts(), doc.owner(),
                        doc.embedding() == null ? "null" : Arrays.toString(doc.embedding()));
            }

            System.out.println("\nAnd SQL can see inside them");
            System.out.println("--------------------------");
            print(connection, "SELECT tag, count(*) AS docs FROM (SELECT unnest(tags) AS tag FROM doc)"
                    + " WHERE tag IS NOT NULL GROUP BY 1 ORDER BY 2 DESC, 1");
            print(connection, "SELECT id, owner.name AS owner, counts['views'] AS views, len(tags) AS tags"
                    + " FROM doc ORDER BY id");

            System.out.println("\nA fixed-size vector, which is what the array functions need");
            System.out.println("----------------------------------------------------------");
            ColumnarLayout<Vector> vectors = ColumnarLayout.builder(Vector.class)
                    .column("id", Integer.class, Vector::id)
                    .vectorColumn("embedding", 4, Vector::embedding)
                    .rowFactory(values -> new Vector((Integer) values[0], (float[]) values[1]))
                    .build();
            TableWriter vectorWriter = new TableWriter("vec", vectors.toColumnDefs(), false, 16);
            vectorWriter.createTable(connection, true);
            write(connection, vectorWriter, vectors, List.of(
                    new Vector(1, new float[] {1f, 0f, 0f, 0f}),
                    new Vector(2, new float[] {0.9f, 0.1f, 0f, 0f}),
                    new Vector(3, new float[] {0f, 0f, 0f, 1f})));
            print(connection, "SELECT column_name, data_type FROM duckdb_columns() WHERE table_name = 'vec'"
                    + " ORDER BY column_index");
            print(connection, "SELECT id, round(array_cosine_similarity(embedding, [1.0, 0.0, 0.0, 0.0]::FLOAT[4]), 4)"
                    + " AS similarity FROM vec ORDER BY similarity DESC");

            System.out.println("\nWhat a vector of 768 floats costs to write");
            System.out.println("-----------------------------------------");
            atScale(connection);

            System.out.println("\nWhat is refused, and why");
            System.out.println("-----------------------");
            record Inner(Integer a) {
            }
            record Outer(Integer id, List<Inner> inners) {
            }
            try {
                ColumnarLayout.ofRecord(Outer.class);
            }
            catch (IllegalArgumentException expected) {
                System.out.println("   " + expected.getMessage());
            }
        }
    }

    static void atScale(DuckDBConnection connection) throws Exception {
        int dimensions = 768;
        for (int rows : new int[] {20_000, 100_000}) {
            ColumnarLayout<Vector> layout = ColumnarLayout.builder(Vector.class)
                    .column("id", Integer.class, Vector::id)
                    .vectorColumn("embedding", dimensions, Vector::embedding)
                    .rowFactory(values -> new Vector((Integer) values[0], (float[]) values[1]))
                    .build();
            Sql.execute(connection, "DROP TABLE IF EXISTS big_vec");
            TableWriter writer = new TableWriter("big_vec", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);

            final int count = rows;
            long started = System.nanoTime();
            // Generated row by row rather than held in a list: 100,000 x 768 floats is 293 MB.
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
                        embedding[i] = (written + i) % 1000 / 1000f;
                    }
                    return layout.toRow(new Vector(written++, embedding));
                }
            }, false);
            double took = (System.nanoTime() - started) / 1e9;
            double megabytes = (double) rows * dimensions * 4 / (1024 * 1024);
            System.out.printf("   %,7d rows of FLOAT[768] (%3.0f MB of floats): %.2f s, %,7.0f rows/s, %3.0f MB/s%n",
                    rows, megabytes, took, rows / took, megabytes / took);
        }
        System.out.println("   A million rows of FLOAT[768] is 3 GB of floats before DuckDB sees any of them,");
        System.out.println("   so it is the rate that matters rather than the total - it is linear in the bytes.");
    }

    static <T> void write(DuckDBConnection connection, TableWriter writer, ColumnarLayout<T> layout, List<T> objects) {
        List<Object[]> rows = new ArrayList<>();
        for (T object : objects) {
            rows.add(layout.toRow(object));
        }
        writer.write(connection, rows.iterator(), false);
    }

    static void print(DuckDBConnection connection, String sql) throws Exception {
        List<String> lines = new ArrayList<>();
        Rows.of(connection.duplicate(), sql).forEachRow((SqlRow row) -> {
            StringBuilder line = new StringBuilder("   ");
            for (String column : row.getColumnNames()) {
                line.append(String.format("%-22s", row.get(column)));
            }
            lines.add(line.toString());
        });
        lines.forEach(System.out::println);
        System.out.println();
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * What the record's own types became
 * ---------------------------------
 *    id                    INTEGER               
 *    title                 VARCHAR               
 *    tags                  VARCHAR[]             
 *    counts                MAP(VARCHAR, INTEGER) 
 *    owner                 STRUCT("name" VARCHAR, age INTEGER, since DATE)
 *    embedding             FLOAT[]               
 *
 *
 * Written and read back, including the empties and the nulls
 * ---------------------------------------------------------
 *    1 first   tags=[java, duckdb]   counts={views=12}       owner=Owner[name=ann, age=41, since=2020-01-02] embedding=[0.1, 0.2]
 *    2 second  tags=[java, null]     counts={views=3}        owner=Owner[name=bob, age=null, since=null] embedding=[0.3]
 *    3 third   tags=[]               counts=null             owner=null                               embedding=null
 *
 * And SQL can see inside them
 * --------------------------
 *    java                  2                     
 *    duckdb                1                     
 *
 *    1                     ann                   12                    2                     
 *    2                     bob                   3                     2                     
 *    3                     null                  null                  0                     
 *
 *
 * A fixed-size vector, which is what the array functions need
 * ----------------------------------------------------------
 *    id                    INTEGER               
 *    embedding             FLOAT[4]              
 *
 *    1                     1.0                   
 *    2                     0.9939                
 *    3                     0.0                   
 *
 *
 * What a vector of 768 floats costs to write
 * -----------------------------------------
 *     20,000 rows of FLOAT[768] ( 59 MB of floats): 0.67 s,  29,716 rows/s,  87 MB/s
 *    100,000 rows of FLOAT[768] (293 MB of floats): 3.44 s,  29,035 rows/s,  85 MB/s
 *    A million rows of FLOAT[768] is 3 GB of floats before DuckDB sees any of them,
 *    so it is the rate that matters rather than the total - it is linear in the bytes.
 *
 * What is refused, and why
 * -----------------------
 *    Inner cannot be a list: quackjvm writes one level of nesting. Flatten it, or keep it in a table of its own.
 *
 * The record's own types are enough: a List<String> becomes VARCHAR[], a Map<String,Integer> becomes
 * MAP(VARCHAR, INTEGER), a nested record becomes a STRUCT, and they all come back as what they were.
 * A float[] becomes FLOAT[] rather than FLOAT[n], because an array's length is not part of its type -
 * vectorColumn(name, 768, ...) is how to say a fixed size, and the size is what array_cosine_similarity
 * needs.
 *
 * Writing 768 floats a row runs at about 29,000 rows a second, 85 MB/s, and is linear in the bytes:
 * the same rate at 20,000 rows as at 100,000. A million rows would be 3 GB of floats before DuckDB saw
 * any of them, which is why the rate is the number that matters.
 *
 * No Arrow anywhere in this, and no extra dependency. The roadmap said the write path would need it;
 * the 1.5.5 appender took Collections, Maps and structs, so it did not.
 */
