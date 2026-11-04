package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.session.QuerySession;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A CSV of 300 columns with columns the application computes from the whole raw row: the total and
 * the count of a group of 150 {@code cpu_*} columns, added to the open table.
 */
public class FileTableComputedTest {

    private static final int ROWS = 4000;
    private static final int CPU = 150;
    private static final int MEM = 148;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path csv;
    private Path cache;
    private List<ColumnDef> columns;
    private final List<FileTable> opened = new ArrayList<>();
    private final List<QuerySession> sessions = new ArrayList<>();

    /** {@code line,host,cpu_001..cpu_150,mem_001..mem_148}: the parser fills line from the row number. */
    private final RowParser parser = new RowParser() {
        @Override
        public void parse(String raw, long rowNo, Object[] cells) {
            BitSet all = new BitSet();
            all.set(0, cells.length);
            parse(raw, rowNo, cells, all);
        }

        @Override
        public void parse(String raw, long rowNo, Object[] cells, BitSet wanted) {
            cells[0] = rowNo + 1;
            String[] parts = raw.split(",", -1);
            for (int c = 1; c < cells.length; c++) {
                if (wanted.get(c)) {
                    String cell = parts[c - 1];
                    cells[c] = c == 1 ? cell : cell.isEmpty() ? null : Double.valueOf(cell);
                }
            }
        }
    };

    /** The total of the row's cpu cells, worked out the plain way. */
    private static Double cpuSum(String raw) {
        String[] parts = raw.split(",", -1);
        double sum = 0;
        int numbers = 0;
        for (int c = 1; c <= CPU; c++) {
            if (!parts[c].isEmpty()) {
                sum += Double.parseDouble(parts[c]);
                numbers++;
            }
        }
        return numbers == 0 ? null : sum;
    }

    /** How many of the row's cpu cells are not blank. */
    private static Long cpuCount(String raw) {
        String[] parts = raw.split(",", -1);
        long n = 0;
        for (int c = 1; c <= CPU; c++) {
            if (!parts[c].isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static final RowFunction CPU_SUM = (raw, rowNo) -> cpuSum(raw);

    private static String row(int r) {
        StringBuilder line = new StringBuilder("h").append(r % 7);
        for (int c = 1; c <= CPU + MEM; c++) {
            // Some cells blank, so the count is not always 150; every 50th row's cpu cells all blank.
            boolean blank = (r + c) % 11 == 0 || (c <= CPU && r % 50 == 3);
            line.append(',').append(blank ? "" : String.valueOf(((r * 31 + c * 7) % 1000) / 10.0));
        }
        return line.append('\n').toString();
    }

    private List<String> written;

    private void append(int from, int to) throws Exception {
        StringBuilder text = new StringBuilder();
        for (int r = from; r < to; r++) {
            String line = row(r);
            written.add(line.substring(0, line.length() - 1));
            text.append(line);
        }
        Files.writeString(csv, text, StandardOpenOption.APPEND);
    }

    @Before
    public void files() throws Exception {
        csv = folder.getRoot().toPath().resolve("wide.csv");
        cache = folder.newFolder("cache").toPath();
        columns = new ArrayList<>();
        columns.add(new ColumnDef("line", Long.class));
        columns.add(new ColumnDef("host", String.class));
        StringBuilder header = new StringBuilder("host");
        for (int c = 1; c <= CPU; c++) {
            String name = String.format("cpu_%03d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        for (int c = 1; c <= MEM; c++) {
            String name = String.format("mem_%03d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        assertEquals(300, columns.size());
        Files.writeString(csv, header.append('\n'));
        written = new ArrayList<>();
        append(0, ROWS);
    }

    @After
    public void close() {
        for (QuerySession session : sessions) {
            session.close();
        }
        for (FileTable table : opened) {
            table.close();
        }
    }

    private FileTable open(int threads, int blockSize, Path cacheDir) {
        FileTable.Builder builder = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).threads(threads)
                .blockSize(blockSize).lazyColumns(true).version("computed-1");
        if (cacheDir != null) {
            builder.cacheDir(cacheDir);
        }
        FileTable table = builder.open();
        opened.add(table);
        return table;
    }

    private FileTable open() {
        return open(4, 500, cache);
    }

    private static GuardedResult run(FileTable table, String sql) {
        return table.query(sql, QueryPolicy.readOnly().maxRows(100_000), null);
    }

    /** Each row's line and cpu_sum, against Java's. */
    private void checkSums(List<Object[]> rows) {
        assertEquals(written.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1L, ((Number) rows.get(i)[0]).longValue());   // the source skips the header
            Double expected = cpuSum(written.get(i));
            Object got = rows.get(i)[1];
            if (expected == null) {
                assertNull("row " + i, got);
            }
            else {
                assertEquals("row " + i, expected, ((Number) got).doubleValue(), 1e-9);
            }
        }
    }

    @Test
    public void aComputedColumnAddedToABuiltTableIsStoredOnFirstReadAndFilledAsTheFileGrows() throws Exception {
        FileTable table = open();
        table.update();
        run(table, "SELECT host, count(*) FROM t GROUP BY 1");
        Path cacheFile = table.cacheFile();
        assertTrue("not known yet", table.whyNot("SELECT sum(cpu_sum) FROM t") != null);

        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        table.column(new ColumnDef("cpu_n", Long.class), (raw, rowNo) -> cpuCount(raw));
        assertEquals("the cache is the same file", cacheFile, table.cacheFile());
        assertEquals(List.of("cpu_sum", "cpu_n"), table.computedColumns().stream().map(ColumnDef::getName).toList());
        assertNull(table.whyNot("SELECT sum(cpu_sum) FROM t"));
        assertEquals("asking stores nothing", List.of("host"), table.storedColumns());

        long read = table.rowsRead();
        checkSums(run(table, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertEquals("one pass over the file", read + ROWS, table.rowsRead());
        assertTrue(table.storedColumns().contains("cpu_sum"));
        assertFalse(table.storedColumns().contains("cpu_n"));

        read = table.rowsRead();
        Object[] totals = run(table, "SELECT sum(cpu_sum), sum(cpu_n), count(cpu_sum) FROM t").getRows().get(0);
        assertEquals("cpu_n stored, cpu_sum not again", read + ROWS, table.rowsRead());
        double sum = 0;
        long n = 0;
        long rowsWithSum = 0;
        for (String raw : written) {
            Double s = cpuSum(raw);
            if (s != null) {
                sum += s;
                rowsWithSum++;
            }
            n += cpuCount(raw);
        }
        assertEquals(sum, ((Number) totals[0]).doubleValue(), 1e-6);
        assertEquals(n, ((Number) totals[1]).longValue());
        assertEquals(rowsWithSum, ((Number) totals[2]).longValue());
        assertTrue("some rows have no cpu values", rowsWithSum < ROWS);

        append(ROWS, ROWS + 300);
        read = table.rowsRead();
        assertEquals(300, table.update().rowsAdded());
        assertEquals("only the new rows read", read + 300, table.rowsRead());
        checkSums(run(table, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertEquals(0L, ((Number) run(table, "SELECT count(*) FROM t WHERE cpu_n IS NULL").getRows().get(0)[0]).longValue());
        assertEquals(cacheFile, table.cacheFile());
    }

    @Test
    public void reopenedTheComputedValuesAreDroppedUntilTheColumnIsAddedAgain() throws Exception {
        FileTable table = open();
        table.update();
        Path cacheFile = table.cacheFile();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        run(table, "SELECT sum(cpu_sum), sum(cpu_001) FROM t");
        assertEquals(List.of("cpu_sum", "cpu_001"), table.storedColumns());
        table.close();
        opened.remove(table);

        FileTable again = open();
        assertEquals("the same cache, with the computed column added or not", cacheFile, again.cacheFile());
        assertEquals("cpu_001 kept, cpu_sum dropped", List.of("cpu_001"), again.storedColumns());
        assertTrue(again.computedColumns().isEmpty());
        try {
            run(again, "SELECT sum(cpu_sum) FROM t");
            fail("read a column no one added");
        }
        catch (QueryRejected expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cpu_sum"));
        }

        // Added again, with a function that differs: computed again, not read from before.
        again.column(new ColumnDef("cpu_sum", Double.class), (raw, rowNo) -> {
            Double s = cpuSum(raw);
            return s == null ? null : s * 2;
        });
        long read = again.rowsRead();
        Object[] totals = run(again, "SELECT sum(cpu_sum) FROM t").getRows().get(0);
        assertEquals(read + ROWS, again.rowsRead());
        double sum = 0;
        for (String raw : written) {
            Double s = cpuSum(raw);
            sum += s == null ? 0 : s * 2;
        }
        assertEquals(sum, ((Number) totals[0]).doubleValue(), 1e-6);
    }

    @Test
    public void addingOneOfTheSameNameReplacesItAndItsStoredValues() {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("score", Long.class), (raw, rowNo) -> 1L);
        assertEquals(ROWS, ((Number) run(table, "SELECT sum(score) FROM t").getRows().get(0)[0]).longValue());
        table.column(new ColumnDef("score", Long.class), (raw, rowNo) -> 2L);
        assertFalse("dropped", table.storedColumns().contains("score"));
        assertEquals(2L * ROWS, ((Number) run(table, "SELECT sum(score) FROM t").getRows().get(0)[0]).longValue());
        // Of another type, too.
        table.column(new ColumnDef("score", String.class), (raw, rowNo) -> raw.substring(0, 2));
        assertEquals("h0", run(table, "SELECT min(score) FROM t").getRows().get(0)[0]);
        assertEquals(1, table.computedColumns().size());
    }

    @Test
    public void refusedOnATableWithoutLazyColumnsOrUnderAParserColumnsName() {
        FileTable plain = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).version("plain-1").open();
        opened.add(plain);
        try {
            plain.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("lazyColumns"));
        }
        FileTable table = open();
        for (String name : new String[] {"CPU_001", "quackjvm_row"}) {
            try {
                table.column(new ColumnDef(name, Double.class), CPU_SUM);
                fail(name);
            }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(name));
            }
        }
    }

    @Test
    public void inAQuerySessionAddedAfterRegisteringAndJoined() throws Exception {
        for (Path cacheDir : new Path[] {cache, null}) {
            FileTable table = open(4, 500, cacheDir);
            table.update();
            QuerySession session = QuerySession.open(Hardening.options(), QueryPolicy.readOnly().maxRows(100_000));
            sessions.add(session);
            session.register("wide", table);
            session.register("hosts", "SELECT 'h' || range AS host, range * 10 AS rack FROM range(0, 7)");
            assertNotNull(session.whyRejected("SELECT sum(cpu_sum) FROM wide"));

            table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
            assertNull(session.whyRejected("SELECT sum(cpu_sum) FROM wide"));
            assertTrue(session.describe("wide").columns().stream().anyMatch(c -> c.name().equals("cpu_sum")));
            List<Object[]> byRack = session.run("SELECT h.rack, sum(w.cpu_sum) FROM wide w JOIN hosts h USING (host)"
                    + " GROUP BY 1 ORDER BY 1").getRows();
            assertEquals(7, byRack.size());
            for (Object[] rack : byRack) {
                String host = "h" + ((Number) rack[0]).longValue() / 10;
                double expected = 0;
                for (String raw : written) {
                    Double s = cpuSum(raw);
                    if (raw.startsWith(host + ",") && s != null) {
                        expected += s;
                    }
                }
                assertEquals(host, expected, ((Number) rack[1]).doubleValue(), 1e-6);
            }
            assertTrue(table.storedColumns().contains("cpu_sum"));

            append(written.size(), written.size() + 100);
            table.update();
            checkSums(session.run("SELECT line, cpu_sum FROM wide ORDER BY line").getRows());

            // Replaced while registered: stored again on the next query.
            table.column(new ColumnDef("cpu_sum", Double.class), (raw, rowNo) -> -1.0);
            assertEquals(-1.0 * written.size(),
                    ((Number) session.run("SELECT sum(cpu_sum) FROM wide").getRows().get(0)[0]).doubleValue(), 1e-9);
            session.close();
        }
    }

    @Test
    public void computedOnManyThreadsTheSameAsOnOne() {
        // Small blocks, so that eight threads each take many and run at once.
        FileTable many = open(8, 37, null);
        FileTable one = open(1, 100_000, folder.getRoot().toPath().resolve("cache-one"));
        for (FileTable table : new FileTable[] {many, one}) {
            table.update();
            table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
            table.column(new ColumnDef("cpu_n", Long.class), (raw, rowNo) -> cpuCount(raw));
        }
        String sql = "SELECT line, cpu_sum, cpu_n FROM t ORDER BY line";
        List<Object[]> fromMany = run(many, sql).getRows();
        List<Object[]> fromOne = run(one, sql).getRows();
        checkSums(fromMany);
        assertEquals(fromOne.size(), fromMany.size());
        for (int i = 0; i < fromOne.size(); i++) {
            assertEquals(java.util.Arrays.asList(fromOne.get(i)), java.util.Arrays.asList(fromMany.get(i)));
            assertEquals(cpuCount(written.get(i)), fromMany.get(i)[2]);
        }
    }

    @Test
    public void aSlowComputedColumnStopsSoonAfterACancelAndIsNotStored() throws Exception {
        FileTable table = open(2, 8192, cache);
        table.update();
        AtomicLong calls = new AtomicLong();
        table.column(new ColumnDef("slow", Double.class), (raw, rowNo) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(1);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return cpuSum(raw);
        });
        CancelSignal stop = new CancelSignal();
        long[] stoppedAt = new long[1];
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(150);
            }
            catch (InterruptedException e) {
                return;
            }
            stoppedAt[0] = System.nanoTime();
            stop.cancel();
        });
        canceller.start();
        try {
            // 4,000 rows at 1 ms on 2 threads: 2 s or more if it ran to the end - one block per thread.
            table.query("SELECT sum(slow) FROM t", QueryPolicy.readOnly(), stop);
            fail("finished");
        }
        catch (QueryCancelled expected) {
            double ms = (System.nanoTime() - stoppedAt[0]) / 1e6;
            System.out.printf("storing a 1 ms computed column stopped %.0f ms after its cancel, after %d calls%n", ms,
                    calls.get());
            assertTrue("stopped " + ms + " ms after the cancel", ms < 100);
        }
        canceller.join();
        assertFalse(table.storedColumns().contains("slow"));
        assertTrue(calls.get() < ROWS / 2);

        table.column(new ColumnDef("slow", Double.class), CPU_SUM);
        checkSums(run(table, "SELECT line, slow FROM t ORDER BY line").getRows());
    }

    @Test
    public void storedOnRequestWithProgressBeforeAnyQueryReadsThem() throws Exception {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        List<Double> progress = new ArrayList<>();
        assertEquals(3, table.store(List.of("line", "cpu_sum", "host"), null, progress::add));
        assertTrue(table.storedColumns().containsAll(List.of("line", "cpu_sum", "host")));
        assertFalse("it reads the file once, a batch at a time", progress.isEmpty());
        for (int i = 1; i < progress.size(); i++) {
            assertTrue("never goes back", progress.get(i) >= progress.get(i - 1));
        }
        assertEquals(1.0, progress.get(progress.size() - 1), 0);
        checkSums(run(table, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertEquals("all there already: nothing to do", 0, table.store(List.of("cpu_sum", "line"), null, null));
        append(ROWS, ROWS + 300);
        table.update();
        checkSums(run(table, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
    }

    @Test
    public void storeStoppedStoresNothingAndRefusesWhatCantBeStored() throws Exception {
        FileTable table = open(2, 200, cache);
        table.update();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            table.store(List.of("cpu_001", "cpu_002"), CancelSignal.from(stop::get), p -> stop.set(p > 0.2));
            fail("stopped");
        }
        catch (QueryCancelled expected) {
            // stopped after a batch
        }
        assertFalse(table.storedColumns().contains("cpu_001"));
        assertEquals(2, table.store(List.of("cpu_001", "cpu_002"), null, null));
        assertEquals(ROWS, ((Number) run(table, "SELECT count(cpu_001) + count(*) - count(cpu_001) FROM t")
                .getRows().get(0)[0]).longValue());
        try {
            table.store(List.of("no_such_column"), null, null);
            fail("a column the table doesn't have");
        }
        catch (IllegalArgumentException expected) {
            // refused
        }
        FileTable plain = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).version("plain-2").open();
        opened.add(plain);
        try {
            plain.store(List.of("host"), null, null);
            fail("a table without lazy columns stores every column already");
        }
        catch (IllegalStateException expected) {
            // refused
        }
    }

    @Test
    public void selectStarListsTheColumnsInTheTablesOrderWhateverOrderTheyWereStoredIn() throws Exception {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        table.store(List.of("cpu_sum"), null, null);                    // the computed one first
        List<String> backwards = new ArrayList<>();
        for (int i = columns.size() - 1; i >= 0; i--) {
            backwards.add(columns.get(i).getName());
        }
        table.store(backwards.subList(0, 150), null, null);              // then the last parser columns
        table.store(backwards.subList(150, backwards.size()), null, null);
        List<String> expected = new ArrayList<>();
        for (ColumnDef c : columns) {
            expected.add(c.getName());
        }
        expected.add("cpu_sum");
        assertEquals(expected, run(table, "SELECT * FROM t LIMIT 1").getColumns());
    }

    @Test
    public void aVersionedComputedColumnIsKeptAcrossReopeningAndUsedWhenAddedAgainTheSame() throws Exception {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM, "sum-1");
        checkSums(run(table, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        table.close();

        FileTable again = open();
        assertNotNull("not added again yet: a query naming it is refused", again.whyNot("SELECT sum(cpu_sum) FROM t"));
        long read = again.rowsRead();
        again.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM, "sum-1");
        checkSums(run(again, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertEquals("the same version: used as kept, the file not read again", read, again.rowsRead());

        // another version: worked out again
        again.column(new ColumnDef("cpu_sum", Double.class), (raw, rowNo) -> {
            Double sum = cpuSum(raw);
            return sum == null ? null : sum * 2;
        }, "sum-2");
        List<Object[]> doubled = run(again, "SELECT line, cpu_sum FROM t ORDER BY line").getRows();
        assertTrue("read again for the new function", again.rowsRead() > read);
        for (int i = 0; i < doubled.size(); i++) {
            Double expected = cpuSum(written.get(i));
            if (expected == null) {
                assertNull(doubled.get(i)[1]);
            }
            else {
                assertEquals(expected * 2, ((Number) doubled.get(i)[1]).doubleValue(), 1e-9);
            }
        }
    }

    @Test
    public void keptValuesAreDroppedWhenRowsAreStoredBeforeTheColumnIsAddedAgain() throws Exception {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM, "sum-1");
        run(table, "SELECT sum(cpu_sum) FROM t");
        table.close();
        append(ROWS, ROWS + 200);

        FileTable again = open();
        again.update(); // new rows, without the kept column's values: it can't be kept
        long read = again.rowsRead();
        again.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM, "sum-1");
        checkSums(run(again, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertTrue("worked out again, for every row", again.rowsRead() > read);
    }

    @Test
    public void unversionedOnesAreStillDroppedAtReopening() throws Exception {
        FileTable table = open();
        table.update();
        table.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        run(table, "SELECT sum(cpu_sum) FROM t");
        table.close();
        FileTable again = open();
        long read = again.rowsRead();
        again.column(new ColumnDef("cpu_sum", Double.class), CPU_SUM);
        checkSums(run(again, "SELECT line, cpu_sum FROM t ORDER BY line").getRows());
        assertTrue("no version: worked out again", again.rowsRead() > read);
    }
}
