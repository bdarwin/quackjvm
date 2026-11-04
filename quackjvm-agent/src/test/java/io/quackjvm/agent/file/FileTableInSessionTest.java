package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.agent.session.ResultWindow;
import io.quackjvm.core.duckdb.ColumnDef;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A file table registered in a query session, joined with its other tables - issue #16. */
public class FileTableInSessionTest {

    /** {@code -Dsession.lines=} makes the big log smaller. */
    private static final int BIG = Integer.getInteger("session.lines", 3_000_000);

    private static Path shared;
    private static Path bigLog;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path data;
    private Path elsewhere;
    private Path cache;
    private final List<FileTable> opened = new ArrayList<>();
    private final List<QuerySession> sessions = new ArrayList<>();

    @BeforeClass
    public static void bigLog() throws Exception {
        shared = Files.createTempDirectory("filetable-session");
        bigLog = shared.resolve("big.log");
        String padding = " - " + "x".repeat(48);
        try (BufferedWriter out = Files.newBufferedWriter(bigLog)) {
            for (long i = 0; i < BIG; i++) {
                out.write(FileTableTest.line(i, "message " + i + padding));
            }
        }
    }

    @AfterClass
    public static void removeBigLog() throws Exception {
        try (var files = Files.walk(shared)) {
            for (Path file : files.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Before
    public void folders() throws Exception {
        data = folder.newFolder("data").toPath();
        elsewhere = folder.newFolder("elsewhere").toPath();
        cache = folder.newFolder("cache").toPath();
        // 10,000 lines picked out: every 300th, labelled by what they are.
        StringBuilder marks = new StringBuilder("line,label\n");
        for (int i = 1; i <= 10_000; i++) {
            marks.append(i * 300L).append(',').append(i % 3 == 0 ? "slow" : i % 3 == 1 ? "odd" : "late").append('\n');
        }
        Files.writeString(elsewhere.resolve("marks.csv"), marks);
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

    private QuerySession session() {
        QuerySession session = QuerySession.open(Hardening.options(), QueryPolicy.readOnly().maxRows(100_000));
        sessions.add(session);
        return session;
    }

    private FileTable log(Path file, String version) {
        FileTable table = FileTables.builder(file, FileTableTest.COLUMNS, LineSource.of(file), FileTableTest.PARSER)
                .cacheDir(cache).threads(4).blockSize(1000).version(version).open();
        opened.add(table);
        return table;
    }

    private static long count(QuerySession session, String sql) {
        return ((Number) session.run(sql).getRows().get(0)[0]).longValue();
    }

    private static List<String> rows(GuardedResult result) {
        List<String> rows = new ArrayList<>();
        for (Object[] row : result.getRows()) {
            rows.add(java.util.Arrays.toString(row));
        }
        return rows;
    }

    // ---------- 1 and 5: a big log ----------

    @Test
    public void aThreeMillionLineLogJoinsACsvFromAnotherFolderAsAPlainTableDoes() throws Exception {
        FileTable log = log(bigLog, "big-1");
        long started = System.nanoTime();
        log.update();
        long loadMs = (System.nanoTime() - started) / 1_000_000;
        QuerySession session = session();
        session.register("app_log", log);
        session.alias("log", "app_log");
        String marks = session.register(elsewhere.resolve("marks.csv"));
        session.allowFolders(List.of(shared));
        session.register("plain", "SELECT column4 + 1 AS line, column1 AS level FROM read_csv('" + bigLog
                + "', delim = ' ', header = false, auto_detect = false, columns = {'column0': 'VARCHAR',"
                + " 'column1': 'VARCHAR', 'column2': 'VARCHAR', 'column3': 'VARCHAR', 'column4': 'BIGINT',"
                + " 'column5': 'VARCHAR', 'column6': 'VARCHAR'})");

        String join = "SELECT m.label, l.level, count(*) AS n, sum(l.line) AS total FROM %s l JOIN " + marks
                + " m USING (line) GROUP BY ALL ORDER BY ALL";
        long first = System.nanoTime();
        List<String> got = rows(session.run(String.format(join, "log")));
        double firstMs = (System.nanoTime() - first) / 1e6;
        long second = System.nanoTime();
        List<String> again = rows(session.run(String.format(join, "log")));
        double secondMs = (System.nanoTime() - second) / 1e6;
        List<String> plain = rows(session.run(String.format(join, "plain")));
        System.out.printf("%,d-line log joined with a 10,000-row CSV: first %.0f ms, second %.0f ms (loaded in %,d ms)%n",
                BIG, firstMs, secondMs, loadMs);
        assertEquals(plain, got);
        assertEquals(got, again);
        assertEquals("three labels, all on INFO lines: every 300th line is never a WARN", 3, got.size());
        assertEquals((long) Math.min(10_000, BIG / 300), count(session, "SELECT count(*) FROM log JOIN " + marks
                + " USING (line)"));
    }

    @Test
    public void aWindowOfTwoMillionRowsOfALogReadsAPageInAboutAMillisecond() throws Exception {
        FileTable log = log(bigLog, "big-1");
        log.update();
        QuerySession session = session();
        session.register("app_log", log);
        long rows = Math.min(2_000_000, BIG);
        ResultWindow window = session.window("SELECT * FROM app_log WHERE line <= " + rows, null);
        assertEquals(rows, window.rowCount());
        long at = rows * 3 / 4;
        List<Double> took = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long started = System.nanoTime();
            List<Object[]> page = window.rows(at, 100);
            took.add((System.nanoTime() - started) / 1e6);
            assertEquals(100, page.size());
            assertEquals(at + 1, ((Number) page.get(0)[0]).longValue());
        }
        Collections.sort(took);
        double median = took.get(took.size() / 2);
        System.out.printf("a window of %,d log rows: rows(%,d, 100) median %.2f ms%n", rows, at, median);
        assertTrue("median " + median, median < 10);

        // The window keeps what it had when the log grows.
        Files.writeString(bigLog, FileTableTest.line(BIG, "one more"), java.nio.file.StandardOpenOption.APPEND);
        try {
            log.update();
            assertEquals(rows, window.rowCount());
            assertEquals(BIG + 1L, count(session, "SELECT count(*) FROM app_log"));
        }
        finally {
            // Others reuse the file: take the line away again.
            try (var channel = java.nio.channels.FileChannel.open(bigLog, java.nio.file.StandardOpenOption.WRITE)) {
                channel.truncate(Files.size(bigLog) - FileTableTest.line(BIG, "one more").length());
            }
        }
    }

    // ---------- 2: growing and rotating while queried and reopened ----------

    @Test
    public void sixThreadsJoinWhileTheLogGrowsAndRotatesAndTheSessionReopensTwice() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 20_000, false);
        FileTable log = log(file, "grow-1");
        log.update();
        QuerySession session = session();
        session.register("app_log", log);
        String marks = session.register(elsewhere.resolve("marks.csv"));

        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicLong queries = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(7);
        List<Future<?>> readers = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            readers.add(pool.submit(() -> {
                while (running.get()) {
                    try {
                        Object[] seen = session.run("SELECT count(*), coalesce(max(line), 0), count(m.label) FROM app_log l"
                                + " LEFT JOIN " + marks + " m USING (line)").getRows().get(0);
                        long rows = ((Number) seen[0]).longValue();
                        long last = ((Number) seen[1]).longValue();
                        if (rows != last) {
                            failures.add("a batch seen in part: " + rows + " rows, the last line " + last);
                        }
                        if (((Number) seen[2]).longValue() != Math.min(10_000, last / 300)) {
                            failures.add("the join lost marks: " + java.util.Arrays.toString(seen));
                        }
                        queries.incrementAndGet();
                    }
                    catch (RuntimeException e) {
                        failures.add(e.toString());
                    }
                }
                return null;
            }));
        }
        Future<?> writer = pool.submit(() -> {
            for (int chunk = 0; chunk < 10; chunk++) {
                FileTableTest.write(file, 20_000 + chunk * 10_000L, 30_000 + chunk * 10_000L, true);
                log.update();
            }
            Path next = data.resolve("next.log");
            FileTableTest.write(next, 0, 7_000, false);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(log.update().rebuilt());
            return null;
        });
        Thread.sleep(100);
        session.allowFolders(List.of(folder.newFolder("first").toPath()));
        Thread.sleep(100);
        session.allowFolders(List.of(folder.newFolder("second").toPath()));
        writer.get(120, TimeUnit.SECONDS);
        Thread.sleep(100);
        running.set(false);
        for (Future<?> reader : readers) {
            reader.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println(queries.get() + " joins while 100,000 lines were appended, the log rotated and the session"
                + " reopened twice; failures: " + failures.size());
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue(queries.get() > 20);
        assertEquals(7_000, count(session, "SELECT count(*) FROM app_log"));
        assertTrue(session.allowedFolders().toString(), session.allowedFolders().stream()
                .anyMatch(f -> f.endsWith("second")));
    }

    // ---------- 3: hardening ----------

    @Test
    public void sqlReadsTheTableButNothingElseOfTheCacheFolderAfterRegisteringReopeningAndRotating() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 1000, false);
        FileTable log = log(file, "hard-1");
        log.update();
        Path otherLog = data.resolve("other.log");
        FileTableTest.write(otherLog, 0, 10, false);
        FileTable other = log(otherLog, "hard-1");
        other.update();
        Files.writeString(cache.resolve("someone.csv"), "a\n1\n");
        QuerySession session = session();
        session.register("app_log", log);
        for (int round = 0; round < 3; round++) {
            assertEquals(round == 2 ? 300 : 1000, count(session, "SELECT count(*) FROM app_log"));
            for (String sql : new String[] {
                    "SELECT * FROM read_csv('" + cache.resolve("someone.csv") + "')",
                    "SELECT * FROM read_text('" + other.cacheFile() + "')",
                    "SELECT * FROM read_text('" + log.cacheFile() + ".used')",
                    "SELECT * FROM glob('" + cache + "/*')",
                    "SELECT * FROM read_csv('" + elsewhere.resolve("marks.csv") + "')",
                    "SELECT * FROM read_csv('https://example.com/x.csv')"}) {
                try {
                    session.run(sql);
                    fail(sql);
                }
                catch (QueryFailed expected) {
                    assertEquals(sql, "Permission", expected.getErrorType());
                }
            }
            for (String sql : new String[] {"ATTACH '" + other.cacheFile() + "' AS o", "DETACH quackjvm_file_1",
                    "DELETE FROM app_log", "INSERT INTO quackjvm_file_1.main.quackjvm_data VALUES (1)", "USE quackjvm_file_1"}) {
                try {
                    session.run(sql);
                    fail(sql);
                }
                catch (QueryRejected expected) {
                    assertFalse(sql, expected instanceof QueryFailed);
                }
            }
            if (round == 0) {
                session.allowFolders(List.of(folder.newFolder("more").toPath()));   // reopened
            }
            else if (round == 1) {
                Path next = data.resolve("next.log");
                FileTableTest.write(next, 0, 300, false);
                Files.move(next, file, StandardCopyOption.REPLACE_EXISTING);
                assertTrue(log.update().rebuilt());
            }
        }
    }

    // ---------- 4: lazy columns ----------

    @Test
    public void aLazySixThousandColumnTableInAJoinStoresOnlyTheColumnsTheQueryReads() throws Exception {
        Path csv = data.resolve("wide.csv");
        List<ColumnDef> columns = new ArrayList<>();
        columns.add(new ColumnDef("host", String.class));
        StringBuilder header = new StringBuilder("host");
        for (int c = 1; c < 6000; c++) {
            String name = String.format("cpu_%04d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        try (BufferedWriter out = Files.newBufferedWriter(csv)) {
            out.write(header.append('\n').toString());
            for (int r = 0; r < 2000; r++) {
                StringBuilder line = new StringBuilder("h").append(r % 7);
                for (int c = 1; c < 6000; c++) {
                    line.append(',').append((r + c) % 10);
                }
                out.write(line.append('\n').toString());
            }
        }
        RowParser parser = new RowParser() {
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
        FileTable wide = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).cacheDir(cache)
                .lazyColumns(true).maxNewColumnsPerQuery(3).version("wide-session").open();
        opened.add(wide);
        wide.update();
        Files.writeString(elsewhere.resolve("hosts.csv"), "host,room\nh0,a\nh1,b\nh2,c\nh3,a\nh4,b\nh5,c\nh6,a\n");
        QuerySession session = session();
        session.register("metrics", wide);
        session.alias("t", "metrics");
        String hosts = session.register(elsewhere.resolve("hosts.csv"));
        assertEquals(List.of(), wide.storedColumns());

        GuardedResult joined = session.run("SELECT h.room, avg(t.cpu_0001) AS cpu FROM t JOIN " + hosts
                + " h USING (host) GROUP BY 1 ORDER BY 1");
        assertEquals(3, joined.getRowCount());
        assertEquals("only the two columns read were stored", List.of("host", "cpu_0001"), wide.storedColumns());
        session.run("SELECT h.room, max(m.cpu_5999) FROM metrics m JOIN " + hosts + " h USING (host) GROUP BY 1");
        assertEquals("one more for a query reading one more", List.of("host", "cpu_0001", "cpu_5999"),
                wide.storedColumns());
        double sum = 0;
        int n = 0;
        for (int r = 0; r < 2000; r++) {
            if (r % 7 == 0 || r % 7 == 3 || r % 7 == 6) {   // the hosts in room a
                sum += (r + 1) % 10;
                n++;
            }
        }
        assertEquals("a", joined.getRows().get(0)[0]);
        assertEquals(sum / n, ((Number) joined.getRows().get(0)[1]).doubleValue(), 1e-9);

        String tooMany = session.whyRejected("SELECT cpu_0010, cpu_0011, cpu_0012, cpu_0013 FROM t");
        assertNotNull(tooMany);
        assertTrue(tooMany, tooMany.contains("4 columns that are not stored yet"));
        assertNotNull("a PIVOT's columns cannot be told", session.whyRejected("PIVOT t ON host USING sum(cpu_0002)"));
        assertNull(session.whyRejected("SELECT cpu_0002 FROM metrics"));
        assertEquals(3, wide.storedColumns().size());
        assertEquals("examples describe all 6,000 columns", 6000, session.describe("t").columns().size());
        assertTrue(session.examples("metrics").stream().anyMatch(e -> e.sql().contains("JOIN " + hosts)));
    }

    // ---------- 6: a copy ----------

    @Test
    public void aTableOnACopyBecauseAnotherProcessHoldsTheCacheStillJoins() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 2000, false);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FileTableChild.class.getName(), file.toString(),
                cache.toString()).redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = out.readLine();
            } while (line != null && !line.startsWith("ready "));
            assertNotNull("the other process opened it", line);
            FileTable mine = FileTables.builder(file, FileTableTest.COLUMNS, LineSource.of(file), FileTableTest.PARSER)
                    .cacheDir(cache).version("test-1").open();
            opened.add(mine);
            assertTrue(mine.cacheFile().getFileName().toString().endsWith(".2.duckdb"));
            mine.update();
            QuerySession session = session();
            session.register("app_log", mine);
            String marks = session.register(elsewhere.resolve("marks.csv"));
            assertEquals(6, count(session, "SELECT count(*) FROM app_log JOIN " + marks + " USING (line)"));
        }
        finally {
            child.getOutputStream().close();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        }
    }

    // ---------- what else it has to do ----------

    @Test
    public void unregisteredOrWithTheSessionClosedTheTableIsTheCallersAgainAndWorksAlone() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 500, false);
        FileTable log = log(file, "home-1");
        log.update();
        QuerySession session = session();
        String name = session.register(log);
        assertEquals("app", name);
        try {
            log.query("SELECT count(*) FROM t", QueryPolicy.readOnly(), null);
            fail("queried alone while registered");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("registered in a QuerySession"));
        }
        try {
            session().register("again", log);
            fail("registered twice");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("already"));
        }
        assertEquals(500, count(session, "SELECT count(*) FROM app"));
        session.unregister("app");
        assertEquals(500, ((Number) log.query("SELECT count(*) FROM t", QueryPolicy.readOnly(), null).getRows().get(0)[0])
                .longValue());
        FileTableTest.write(file, 500, 600, true);
        assertEquals(100, log.update().rowsAdded());

        session.register("app", log);
        assertEquals(600, count(session, "SELECT count(*) FROM app"));
        session.close();
        assertTrue("closing the session leaves the table open", log.isOpen());
        assertEquals(600, ((Number) log.query("SELECT count(*) FROM t", QueryPolicy.readOnly(), null).getRows().get(0)[0])
                .longValue());
    }

    @Test
    public void aTableClosedWhileRegisteredSaysSoAndCanBeUnregistered() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 100, false);
        FileTable log = log(file, "closed-1");
        log.update();
        QuerySession session = session();
        session.register("app_log", log);
        session.alias("log", "app_log");
        log.close();
        for (String sql : new String[] {"SELECT count(*) FROM app_log", "SELECT * FROM log LIMIT 1"}) {
            try {
                session.run(sql);
                fail(sql);
            }
            catch (QueryFailed expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("app_log is a file table that was closed"));
            }
        }
        session.allowFolders(List.of(folder.newFolder("after").toPath()));   // reopening skips it
        session.unregister("app_log");
        assertEquals(List.of(), session.names());
        assertEquals(1, count(session, "SELECT 1"));
    }

    @Test
    public void aTableKeptInMemoryIsReadAgainInTheSessionAndAgainWhenItLeaves() throws Exception {
        Path file = data.resolve("small.log");
        FileTableTest.write(file, 0, 300, false);
        FileTable small = FileTables.builder(file, FileTableTest.COLUMNS, LineSource.of(file), FileTableTest.PARSER)
                .version("small-1").open();
        opened.add(small);
        small.update();
        assertNull(small.cacheFile());
        QuerySession session = session();
        session.register("small", small);
        String marks = session.register(elsewhere.resolve("marks.csv"));
        assertEquals(300, count(session, "SELECT count(*) FROM small"));
        assertEquals(1, count(session, "SELECT count(*) FROM small JOIN " + marks + " USING (line)"));
        FileTableTest.write(file, 300, 400, true);
        small.update();
        assertEquals(400, count(session, "SELECT count(*) FROM small"));
        session.unregister("small");
        assertEquals(400, ((Number) small.query("SELECT count(*) FROM t", QueryPolicy.readOnly(), null).getRows().get(0)[0])
                .longValue());
    }

    @Test
    public void aFileTablesJavaFunctionsWorkInTheSessionAndACancelStopsThem() throws Exception {
        Path file = data.resolve("app.log");
        FileTableTest.write(file, 0, 3000, false);
        FileTable log = FileTables.builder(file, FileTableTest.COLUMNS, LineSource.of(file), FileTableTest.PARSER)
                .cacheDir(cache).version("fn-1").function("shout", String.class, String.class, String::toUpperCase)
                .function("slow", Long.class, Long.class, x -> {
                    long until = System.nanoTime() + 1_000_000;
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    return 1L;
                }).open();
        opened.add(log);
        log.update();
        QuerySession session = session();
        session.register("app_log", log);
        String marks = session.register(elsewhere.resolve("marks.csv"));
        assertEquals("MESSAGE 299", session.run("SELECT shout(message) FROM app_log JOIN " + marks
                + " USING (line)").getRows().get(0)[0]);
        CancelSignal stop = new CancelSignal();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Long> stopped = pool.submit(() -> {
            try {
                session.run("SELECT sum(slow(line)) FROM app_log", stop);
                return -1L;
            }
            catch (QueryCancelled expected) {
                return System.nanoTime();
            }
        });
        Thread.sleep(200);
        long cancelled = System.nanoTime();
        stop.cancel();
        long at = stopped.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue("it was cancelled", at > 0);
        double ms = (at - cancelled) / 1e6;
        System.out.printf("a session query in a 1 ms function stopped %.0f ms after its cancel%n", ms);
        assertTrue(ms + " ms", ms < 200);
        log.function("shout", String.class, String.class, text -> text.toLowerCase());
        assertEquals("message 299", session.run("SELECT shout(message) FROM app_log WHERE line = 300").getRows().get(0)[0]);
    }
}
