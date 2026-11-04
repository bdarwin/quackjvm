package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.core.duckdb.ColumnDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A log file as a table kept on disk and up to date as it grows - issue #13. */
public class FileTableTest {

    static final List<ColumnDef> COLUMNS = List.of(new ColumnDef("line", Long.class),
            new ColumnDef("time", LocalDateTime.class), new ColumnDef("level", String.class),
            new ColumnDef("thread", String.class), new ColumnDef("message", String.class));

    static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);

    /** {@code 2026-01-01T00:00:01 INFO [main] message 1} */
    static final RowParser PARSER = (raw, rowNo, cells) -> {
        int a = raw.indexOf(' ');
        int b = raw.indexOf(' ', a + 1);
        int c = raw.indexOf(' ', b + 1);
        cells[0] = rowNo + 1;
        cells[1] = LocalDateTime.parse(raw.substring(0, a));
        cells[2] = raw.substring(a + 1, b);
        cells[3] = raw.substring(b + 2, c - 1);
        cells[4] = raw.substring(c + 1);
    };

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path log;
    private Path cache;
    private final List<FileTable> opened = new ArrayList<>();

    static String line(long i, String message) {
        return START.plusSeconds(i) + " " + (i % 10 == 0 ? "WARN" : "INFO") + " [t" + (i % 4) + "] " + message + "\n";
    }

    static void write(Path file, long from, long to, boolean append) throws Exception {
        StringBuilder text = new StringBuilder();
        for (long i = from; i < to; i++) {
            text.append(line(i, "message " + i));
        }
        if (append) {
            Files.writeString(file, text, StandardOpenOption.APPEND);
        }
        else {
            Files.writeString(file, text);
        }
    }

    @Before
    public void files() throws Exception {
        log = folder.getRoot().toPath().resolve("app.log");
        cache = folder.newFolder("cache").toPath();
        write(log, 0, 1000, false);
    }

    @After
    public void close() {
        for (FileTable table : opened) {
            table.close();
        }
    }

    private FileTable open() {
        FileTable table = FileTables.builder(log, COLUMNS, LineSource.of(log), PARSER).cacheDir(cache).threads(4)
                .blockSize(100).version("test-1").open();
        opened.add(table);
        return table;
    }

    private static long count(FileTable table, String sql) {
        return ((Number) table.query(sql, QueryPolicy.readOnly(), null).getRows().get(0)[0]).longValue();
    }

    @Test
    public void aLogBecomesATableInFileOrder() {
        FileTable table = open();
        assertEquals(0, table.rowsLoaded());
        FileTable.Updated first = table.update();
        assertEquals(1000, first.rowsAdded());
        assertFalse(first.rebuilt());
        assertEquals(1000, table.rowsLoaded());
        assertEquals(1000, table.rowsRead());
        assertEquals(1000, count(table, "SELECT count(*) FROM t"));
        assertEquals(100, count(table, "SELECT count(*) FROM t WHERE level = 'WARN'"));
        GuardedResult first10 = table.query("SELECT * FROM t LIMIT 3", QueryPolicy.readOnly(), null);
        assertEquals(List.of("line", "time", "level", "thread", "message"), first10.getColumns());
        assertEquals("message 0", first10.getRows().get(0)[4]);
        assertEquals("message 2", first10.getRows().get(2)[4]);
        assertEquals("t1", first10.getRows().get(1)[3]);
        assertNotNull(table.cacheFile());
        assertTrue(table.cacheFile().getFileName().toString().startsWith("qft-"));
    }

    @Test
    public void afterARestartNothingIsReadUntilTheFileGrows() throws Exception {
        FileTable before = open();
        before.update();
        before.close();
        opened.remove(before);

        long started = System.nanoTime();
        FileTable after = open();
        assertEquals(1000, count(after, "SELECT count(*) FROM t"));
        long took = (System.nanoTime() - started) / 1_000_000;
        assertEquals("nothing read again", 0, after.rowsRead());
        assertEquals(1000, after.rowsLoaded());
        assertTrue("open and first query took " + took + " ms", took < 2000);

        write(log, 1000, 2000, true);
        FileTable.Updated grown = after.update();
        assertEquals(1000, grown.rowsAdded());
        assertFalse(grown.rebuilt());
        assertEquals("only the new rows were read", 1000, after.rowsRead());
        assertEquals(2000, count(after, "SELECT count(*) FROM t"));
        assertEquals(2000, count(after, "SELECT max(line) FROM t"));
    }

    @Test
    public void aThousandLinesAppendedAreExactlyAThousandRowsAcrossARestart() throws Exception {
        FileTable table = open();
        table.update();
        write(log, 1000, 2000, true);
        assertEquals(1000, table.update().rowsAdded());
        table.close();
        opened.remove(table);
        write(log, 2000, 3000, true);
        FileTable again = open();
        assertEquals(1000, again.update().rowsAdded());
        assertEquals(3000, count(again, "SELECT count(DISTINCT line) FROM t"));
        assertEquals(0, count(again, "SELECT count(*) FROM (SELECT line, row_number() OVER () AS n FROM t) WHERE line <> n"));
    }

    @Test
    public void aRotatedFileIsLoadedAgainWithNothingOfTheOldLeft() throws Exception {
        FileTable table = open();
        table.update();
        for (long rows : new long[] {300, 5000}) {   // smaller, then bigger
            Path next = folder.getRoot().toPath().resolve("next.log");
            StringBuilder text = new StringBuilder();
            for (long i = 0; i < rows; i++) {
                text.append(line(i, "rotated " + i));
            }
            Files.writeString(next, text);
            Files.move(next, log, StandardCopyOption.REPLACE_EXISTING);
            FileTable.Updated updated = table.update();
            assertTrue(updated.rebuilt());
            assertEquals(rows, table.rowsLoaded());
            assertEquals(rows, count(table, "SELECT count(*) FROM t"));
            assertEquals(0, count(table, "SELECT count(*) FROM t WHERE message LIKE 'message %'"));
        }
    }

    @Test
    public void aFileReplacedWhileClosedIsToldByItsFirstBytes() throws Exception {
        FileTable table = open();
        table.update();
        table.close();
        opened.remove(table);
        // Bigger than before, so only the fingerprint can tell.
        StringBuilder text = new StringBuilder();
        for (long i = 0; i < 2000; i++) {
            text.append(line(i + 7, "other " + i));
        }
        Files.writeString(log, text);
        FileTable again = open();
        FileTable.Updated updated = again.update();
        assertTrue(updated.rebuilt());
        assertEquals(2000, count(again, "SELECT count(*) FROM t WHERE message LIKE 'other %'"));
        assertEquals(2000, count(again, "SELECT count(*) FROM t"));
    }

    @Test
    public void aStoppedUpdateKeepsWhatItStoredAndTheNextGoesOnFromThere() throws Exception {
        write(log, 1000, 200_000, true);
        FileTable table = FileTables.builder(log, COLUMNS, LineSource.of(log), PARSER).cacheDir(cache).threads(2)
                .blockSize(1000).version("test-1").open();
        opened.add(table);
        CancelSignal stop = new CancelSignal();
        List<Double> progress = new ArrayList<>();
        long[] stoppedAt = new long[1];
        try {
            table.update(stop, done -> {
                progress.add(done);
                if (progress.size() == 2) {
                    stoppedAt[0] = System.nanoTime();
                    stop.cancel();
                }
            });
            fail("finished");
        }
        catch (QueryCancelled expected) {
            long took = (System.nanoTime() - stoppedAt[0]) / 1_000_000;
            assertTrue("returned " + took + " ms after the stop", took < 100);
        }
        long kept = table.rowsLoaded();
        assertEquals("two batches of 2 threads x 8 blocks x 1000 rows", 32_000, kept);
        assertEquals(kept, count(table, "SELECT count(*) FROM t"));
        FileTable.Updated rest = table.update();
        assertEquals(200_000 - kept, rest.rowsAdded());
        assertEquals(200_000, count(table, "SELECT count(DISTINCT line) FROM t"));
    }

    @Test
    public void queriesAreGuardedAndTheCacheFolderIsNotReadable() throws Exception {
        FileTable table = open();
        table.update();
        Path other = cache.resolve("someone-else.csv");
        Files.writeString(other, "a\n1\n");
        for (String sql : new String[] {"DROP VIEW t", "COPY t TO 'x.csv'", "SET threads = 1"}) {
            try {
                table.query(sql, QueryPolicy.readOnly(), null);
                fail(sql);
            }
            catch (QueryRejected expected) {
                assertFalse(expected instanceof QueryFailed);
            }
        }
        try {
            table.query("SELECT * FROM read_csv('" + other + "')", QueryPolicy.readOnly(), null);
            fail();
        }
        catch (QueryFailed expected) {
            assertEquals("Permission", expected.getErrorType());
        }
        assertNull(table.whyNot("SELECT level FROM t"));
        assertNotNull(table.whyNot("DROP VIEW t"));
    }

    @Test
    public void aSmallFileIsKeptInMemory() {
        FileTable table = FileTables.builder(log, COLUMNS, LineSource.of(log), PARSER).cacheDir(cache)
                .inMemoryBelow(1L << 20).open();
        opened.add(table);
        table.update();
        assertNull(table.cacheFile());
        assertEquals(1000, count(table, "SELECT count(*) FROM t"));
        assertEquals(0, FileTables.size(cache));
    }

    @Test
    public void aLogInAFolderWithABackslashInItsNameLoads() throws Exception {
        // Issue #12's case, on any OS: a backslash is legal in a name on macOS and Linux. The source
        // is read by Java, so its path can hold one.
        Path odd = Files.createDirectories(folder.getRoot().toPath().resolve("back\\slash"));
        Path oddLog = odd.resolve("app.log");
        write(oddLog, 0, 500, false);
        FileTable table = FileTables.builder(oddLog, COLUMNS, LineSource.of(oddLog), PARSER).cacheDir(cache).open();
        opened.add(table);
        table.update();
        assertEquals(500, count(table, "SELECT count(*) FROM t"));
    }

    @Test
    public void aCacheFolderDuckDbCannotOpenIsRefusedPlainly() throws Exception {
        org.junit.Assume.assumeFalse("a backslash is the separator there", "\\".equals(java.io.File.separator));
        Path odd = Files.createDirectories(folder.getRoot().toPath().resolve("back\\slash"));
        try {
            FileTables.builder(log, COLUMNS, LineSource.of(log), PARSER).cacheDir(odd);
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("backslash"));
        }
    }

    @Test
    public void aParserThatFailsSaysWhichRow() throws Exception {
        Files.writeString(log, "not a line at all\n", StandardOpenOption.APPEND);
        FileTable table = open();
        try {
            table.update();
            fail();
        }
        catch (RuntimeException expected) {
            String all = expected.toString() + " " + expected.getCause();
            Throwable cause = expected;
            while (cause.getCause() != null) {
                all += " " + cause.getCause().getMessage();
                cause = cause.getCause();
            }
            assertTrue(all, all.contains("row 1000"));
        }
    }
}
