package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.core.duckdb.ColumnDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A CSV of 6,000 columns and 5,000 rows, storing only the columns queries read - issue #13. */
public class FileTableWideTest {

    private static final int ROWS = 5000;
    private static final int CPU = 1500;
    private static final int MEM = 4499;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path csv;
    private Path cache;
    private List<ColumnDef> columns;
    private final List<FileTable> opened = new ArrayList<>();

    /** Splits on commas, and turns only the wanted cells into values. */
    private final RowParser parser = new RowParser() {
        @Override
        public void parse(String raw, long rowNo, Object[] cells) {
            BitSet all = new BitSet();
            all.set(0, cells.length);
            parse(raw, rowNo, cells, all);
        }

        @Override
        public void parse(String raw, long rowNo, Object[] cells, BitSet wanted) {
            int column = 0;
            int start = 0;
            for (int i = 0; i <= raw.length(); i++) {
                if (i == raw.length() || raw.charAt(i) == ',') {
                    if (wanted.get(column)) {
                        String cell = raw.substring(start, i);
                        cells[column] = column == 0 ? cell : Double.valueOf(cell);
                    }
                    column++;
                    start = i + 1;
                }
            }
        }
    };

    private static String row(int r) {
        StringBuilder line = new StringBuilder("h").append(r % 7);
        for (int c = 1; c <= CPU + MEM; c++) {
            line.append(',').append((r + c) % 10);
        }
        return line.append('\n').toString();
    }

    @Before
    public void files() throws Exception {
        csv = folder.getRoot().toPath().resolve("wide.csv");
        cache = folder.newFolder("cache").toPath();
        columns = new ArrayList<>();
        columns.add(new ColumnDef("host", String.class));
        StringBuilder header = new StringBuilder("host");
        for (int c = 1; c <= CPU; c++) {
            String name = String.format("cpu_%04d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        for (int c = 1; c <= MEM; c++) {
            String name = String.format("mem_%04d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        try (BufferedWriter out = Files.newBufferedWriter(csv)) {
            out.write(header.append('\n').toString());
            for (int r = 0; r < ROWS; r++) {
                out.write(row(r));
            }
        }
    }

    @After
    public void close() {
        for (FileTable table : opened) {
            table.close();
        }
    }

    private FileTable open() {
        FileTable table = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).cacheDir(cache)
                .lazyColumns(true).maxNewColumnsPerQuery(64).version("wide-1").open();
        opened.add(table);
        return table;
    }

    private static GuardedResult run(FileTable table, String sql) {
        return table.query(sql, QueryPolicy.readOnly(), null);
    }

    @Test
    public void aQueryStoresTheTwoColumnsItReadsAndASecondStoresNone() {
        FileTable table = open();
        assertEquals(6000, columns.size());
        table.update();
        assertEquals(ROWS, table.rowsLoaded());
        assertTrue("an update stores no columns until asked", table.storedColumns().isEmpty());
        assertEquals(ROWS, ((Number) run(table, "SELECT count(*) FROM t").getRows().get(0)[0]).longValue());

        long started = System.nanoTime();
        GuardedResult first = run(table, "SELECT host, avg(cpu_0001) AS a FROM t GROUP BY 1 ORDER BY 1");
        long firstMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(List.of("cpu_0001", "host"), table.storedColumns().stream().sorted().toList());
        assertEquals(7, first.getRowCount());
        // h0 is rows 0, 7, 14...; cpu_0001 is (r + 1) % 10.
        double expected = 0;
        int n = 0;
        for (int r = 0; r < ROWS; r += 7) {
            expected += (r + 1) % 10;
            n++;
        }
        assertEquals(expected / n, ((Number) first.getRows().get(0)[1]).doubleValue(), 1e-9);

        long read = table.rowsRead();
        started = System.nanoTime();
        run(table, "SELECT host, max(cpu_0001) FROM t GROUP BY 1");
        long secondMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals("nothing more stored", 2, table.storedColumns().size());
        assertEquals("nothing read again", read, table.rowsRead());
        System.out.printf("first query (stores 2 columns) %d ms, second %d ms%n", firstMs, secondMs);
    }

    @Test
    public void tooManyNewColumnsOrColumnsThatCannotBeToldAreRefusedWithAReason() {
        FileTable table = open();
        table.update();
        String family = "SELECT avg(COLUMNS('^cpu_[0-9]+$')) FROM t";
        String why = table.whyNot(family);
        assertNotNull(why);
        assertTrue(why, why.contains("1500 columns") && why.contains("64"));
        try {
            run(table, family);
            fail();
        }
        catch (QueryRejected expected) {
            assertEquals(why, expected.getMessage());
        }
        assertTrue(table.whyNot("SELECT * FROM t").contains("6000 columns"));
        assertTrue(table.whyNot("PIVOT t ON host USING sum(cpu_0001)") != null);
        assertNull(table.whyNot("SELECT avg(COLUMNS('^cpu_000[0-9]$')) FROM t"));
        assertTrue("nothing was stored by asking", table.storedColumns().isEmpty());
        GuardedResult nine = run(table, "SELECT avg(COLUMNS('^cpu_000[0-9]$')) FROM t");
        assertEquals(9, nine.getColumns().size());
        assertEquals(9, table.storedColumns().size());
    }

    @Test
    public void storedColumnsAreRememberedAcrossARestartAndGrowWithTheFile() throws Exception {
        FileTable table = open();
        table.update();
        run(table, "SELECT host, sum(mem_0007) FROM t GROUP BY 1");
        table.close();
        opened.remove(table);

        FileTable again = open();
        assertEquals(List.of("host", "mem_0007"), again.storedColumns().stream().sorted().toList());
        run(again, "SELECT host, sum(mem_0007) FROM t GROUP BY 1");
        assertEquals("nothing read after the restart", 0, again.rowsRead());

        StringBuilder more = new StringBuilder();
        for (int r = ROWS; r < ROWS + 100; r++) {
            more.append(row(r));
        }
        Files.writeString(csv, more, StandardOpenOption.APPEND);
        assertEquals(100, again.update().rowsAdded());
        assertEquals(100, again.rowsRead());
        double total = ((Number) run(again, "SELECT sum(mem_0007) FROM t").getRows().get(0)[0]).doubleValue();
        double expected = 0;
        for (int r = 0; r < ROWS + 100; r++) {
            expected += (r + CPU + 7) % 10;
        }
        assertEquals(expected, total, 1e-9);
    }

    @Test
    public void columnsAskedForBeforeAnyRowsAreStoredAsRowsArrive() {
        FileTable table = open();
        run(table, "SELECT host FROM t");
        assertEquals(List.of("host"), table.storedColumns());
        table.update();
        assertEquals(7, run(table, "SELECT DISTINCT host FROM t").getRowCount());
    }
}
