package io.quackjvm.core.duckdb;

import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** What a write leaves behind: the rows, and nothing else. */
public class TableWriterTest {

    public record Car(Integer id, String make, Double price) {
    }

    public record Doc(Long id, String title, List<String> tags, float[] embedding) {
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
        TableWriter writer = new TableWriter(table, layout.toColumnDefs(), false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
        writer.createTable(connection, true);
        return writer;
    }

    private static <T> List<Object[]> rows(ColumnarLayout<T> layout, List<T> objects) {
        List<Object[]> rows = new ArrayList<>(objects.size());
        for (T object : objects) {
            rows.add(layout.toRow(object));
        }
        return rows;
    }

    /** Asked on the writer's own connection: a temporary table is visible only there. */
    private List<String> tables() throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT table_name FROM duckdb_tables() ORDER BY 1")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static ColumnarLayout<Doc> docLayout() {
        return ColumnarLayout.builder(Doc.class)
                .column("id", Long.class, Doc::id)
                .column("title", String.class, Doc::title)
                .listColumn("tags", String.class, Doc::tags)
                .vectorColumn("embedding", 384, Doc::embedding)
                .rowFactory(values -> new Doc((Long) values[0], (String) values[1], null, null))
                .build();
    }

    private long tableBytes() throws Exception {
        return Rows.of(connection.duplicate(), "SELECT memory_usage_bytes FROM duckdb_memory() WHERE tag = 'IN_MEMORY_TABLE'")
                .scalar(Long.class);
    }

    private static List<Doc> docs(int count) {
        List<Doc> docs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] embedding = new float[384];
            for (int d = 0; d < embedding.length; d++) {
                embedding[d] = (i * 31 + d) % 97 / 97f;
            }
            docs.add(new Doc((long) i, "doc " + i, List.of("t" + i % 7, "u" + i % 3), embedding));
        }
        return docs;
    }

    @Test
    public void aBulkWriteLeavesNoStagingTableBehind() throws Exception {
        ColumnarLayout<Car> layout = ColumnarLayout.ofRecord(Car.class);
        TableWriter writer = writerFor("car", layout);
        List<Car> cars = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            cars.add(new Car(i, "make" + i % 5, i * 1.5));
        }

        writer.write(connection, rows(layout, cars).iterator(), true);

        assertEquals(List.of("car"), tables());
        assertEquals(1_000, writer.count(connection));
    }

    @Test
    public void aSmallWriteOfNestedValuesLeavesNoStagingTableBehind() throws Exception {
        // A list cannot be bound to a statement, so even one row goes through the staging table.
        ColumnarLayout<Doc> layout = docLayout();
        TableWriter writer = writerFor("doc", layout);

        writer.write(connection, rows(layout, docs(1)).iterator(), true);

        assertEquals(List.of("doc"), tables());
    }

    /**
     * The staging table used to be emptied and kept, and DuckDB does not give back the memory of
     * deleted rows, so the data was held twice: 158 MB against 79 for 50,000 384-float vectors.
     */
    @Test
    public void writingVectorsTakesNoMoreMemoryThanAppendingThemDirectly() throws Exception {
        ColumnarLayout<Doc> layout = docLayout();
        List<Doc> docs = docs(20_000);

        // Each in a database of its own, so that neither measurement includes the other's tables.
        writerFor("doc", layout);
        try (DuckDBAppender appender = connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "doc")) {
            for (Doc doc : docs) {
                appender.beginRow().append(doc.id()).append(doc.title()).append(doc.tags())
                        .append(doc.embedding()).endRow();
            }
            appender.flush();
        }
        long direct = tableBytes();

        connection.close();
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        TableWriter writer = writerFor("doc", layout);
        writer.write(connection, rows(layout, docs).iterator(), false);
        long throughWriter = tableBytes();

        assertTrue("through the writer " + throughWriter / 1024 + " KB, appended directly " + direct / 1024 + " KB",
                throughWriter < direct * 1.25);
    }

    @Test
    public void aWriteInTheCallersTransactionRollsBackWithItAndLeavesNothingBehind() throws Exception {
        ColumnarLayout<Car> layout = ColumnarLayout.ofRecord(Car.class);
        TableWriter writer = writerFor("car", layout);
        List<Car> cars = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            cars.add(new Car(i, "make", 1.0));
        }

        connection.setAutoCommit(false);
        writer.write(connection, rows(layout, cars).iterator(), true);
        assertEquals(500, writer.count(connection));
        connection.rollback();
        connection.setAutoCommit(true);

        assertEquals(0, writer.count(connection));
        assertEquals(List.of("car"), tables());
    }
}
