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

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Java functions in a file table's queries - issue #18. */
public class FileTableFunctionsTest {

    private static final int ROWS = 5000;
    private static final int VALUES = 5998;
    private static final long START_MS = Instant.parse("2026-03-28T20:00:00Z").toEpochMilli();
    private static final long STEP_MS = 37_000;
    private static final String[] GROUPS = {"minute", "hour", "day"};

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path csv;
    private Path cache;
    private List<ColumnDef> columns;
    private final List<FileTable> opened = new ArrayList<>();

    /** time in ms, a host, then 5,998 numbers: 6,000 columns. Only the wanted cells are parsed. */
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
                        cells[column] = column == 0 ? (Object) Long.valueOf(cell)
                                : column == 1 ? cell : Double.valueOf(cell);
                    }
                    column++;
                    start = i + 1;
                }
            }
        }
    };

    /** A time put in its group - minute, hour, day - and labelled in a zone: what the application's own code does. */
    static String label(long ms, int group, ZoneId zone) {
        ZonedDateTime at = Instant.ofEpochMilli(ms).atZone(zone);
        ChronoUnit unit = group == 0 ? ChronoUnit.MINUTES : group == 1 ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        return at.truncatedTo(unit).toOffsetDateTime().toString();
    }

    private static long time(int r) {
        return START_MS + r * STEP_MS;
    }

    private static String host(int r) {
        return "host-" + (r % 7) + (r % 7 == 3 ? "-db" : "");
    }

    @Before
    public void files() throws Exception {
        csv = folder.getRoot().toPath().resolve("wide.csv");
        cache = folder.newFolder("cache").toPath();
        columns = new ArrayList<>();
        columns.add(new ColumnDef("time", Long.class));
        columns.add(new ColumnDef("host", String.class));
        StringBuilder header = new StringBuilder("time,host");
        for (int c = 1; c <= VALUES; c++) {
            String name = String.format("v_%04d", c);
            columns.add(new ColumnDef(name, Double.class));
            header.append(',').append(name);
        }
        try (BufferedWriter out = Files.newBufferedWriter(csv)) {
            out.write(header.append('\n').toString());
            for (int r = 0; r < ROWS; r++) {
                StringBuilder line = new StringBuilder().append(time(r)).append(',').append(host(r));
                for (int c = 1; c <= VALUES; c++) {
                    line.append(',').append((r + c) % 10);
                }
                out.write(line.append('\n').toString());
            }
        }
    }

    @After
    public void close() {
        for (FileTable table : opened) {
            table.close();
        }
    }

    private FileTable.Builder wide() {
        return FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).cacheDir(cache)
                .lazyColumns(true).version("functions-1");
    }

    private FileTable keep(FileTable table) {
        opened.add(table);
        return table;
    }

    private static GuardedResult run(FileTable table, String sql) {
        return table.query(sql, QueryPolicy.readOnly(), null);
    }

    @Test
    public void aFunctionOverALazySixThousandColumnTableStoresOnlyTheColumnItReads() {
        ZoneId zone = ZoneId.of("Europe/Berlin");   // the clocks go forward on 2026-03-29
        FileTable table = keep(wide().function("label_time", String.class, Long.class, Integer.class,
                (ms, group) -> label(ms, group, zone)).open());
        table.update();
        assertEquals(List.of(), table.storedColumns());
        assertNull(table.whyNot("SELECT label_time(time, 1) AS hour, count(*) FROM t GROUP BY 1"));

        for (int group = 0; group < GROUPS.length; group++) {
            GuardedResult result = run(table, "SELECT label_time(time, " + group + ") AS " + GROUPS[group]
                    + ", count(*) AS n FROM t GROUP BY 1 ORDER BY 1");
            Map<String, Long> expected = new TreeMap<>();
            for (int r = 0; r < ROWS; r++) {
                expected.merge(label(time(r), group, zone), 1L, Long::sum);
            }
            Map<String, Long> got = new TreeMap<>();
            for (Object[] row : result.getRows()) {
                got.put((String) row[0], ((Number) row[1]).longValue());
            }
            assertEquals(GROUPS[group], expected, got);
        }
        assertEquals("only time was stored", List.of("time"), table.storedColumns());
        Object[] offsets = run(table, "SELECT count(*) FILTER (WHERE label_time(time, 1) LIKE '%+01:00'),"
                + " count(*) FILTER (WHERE label_time(time, 1) LIKE '%+02:00') FROM t").getRows().get(0);
        assertTrue("it spans the change to summer time", ((Number) offsets[0]).longValue() > 0
                && ((Number) offsets[1]).longValue() > 0);

        run(table, "SELECT label_time(time, 2), avg(v_0042) FROM t GROUP BY 1");
        assertEquals("one more column for a query reading one more", List.of("time", "v_0042"), table.storedColumns());
        assertNotNull("an unknown function cannot be planned", table.whyNot("SELECT no_such_function(time) FROM t"));
    }

    @Test
    public void registeringOnASixThousandColumnTableTakesAMillisecondOrSo() {
        FileTable table = keep(wide().open());
        table.update();
        List<Double> took = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int add = i;
            long started = System.nanoTime();
            table.function("plus", Long.class, Long.class, x -> x + add);
            took.add((System.nanoTime() - started) / 1e6);
        }
        assertEquals(START_MS + 49, ((Number) run(table, "SELECT plus(min(time)) FROM t").getRows().get(0)[0]).longValue());
        Collections.sort(took);
        double median = took.get(took.size() / 2);
        System.out.printf("registering a function on a 6,000-column table: median %.2f ms, slowest %.2f ms%n",
                median, took.get(took.size() - 1));
        assertTrue("median " + median, median < 10);
    }

    @Test
    public void aPredicateRegisteredByIdGivesEachQueryItsOwnValuesFromTwoThreads() throws Exception {
        Map<Long, Predicate<String>> tests = new ConcurrentHashMap<>();
        AtomicLong calls = new AtomicLong();
        FileTable table = keep(wide().function("keep_value", Boolean.class, Long.class, String.class, (id, value) -> {
            calls.incrementAndGet();
            return tests.get(id).test(value);
        }).open());
        table.update();
        List<Predicate<String>> filters = List.of(Pattern.compile("^host-[0-2]$").asPredicate(),
                value -> value.toLowerCase().contains("-DB".toLowerCase()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong ids = new AtomicLong();
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            Predicate<String> filter = filters.get(t);
            long expected = 0;
            for (int r = 0; r < ROWS; r++) {
                expected += filter.test(host(r)) ? 1 : 0;
            }
            long rows = expected;
            done.add(pool.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    long id = ids.incrementAndGet();
                    tests.put(id, filter);
                    try {
                        // Asked once per distinct value: OFFSET 0 keeps DuckDB 2.0 from pushing the
                        // test down below the DISTINCT, where it is asked once per row.
                        long got = ((Number) run(table, "SELECT count(*) FROM t WHERE host IN (SELECT v FROM"
                                + " (SELECT DISTINCT host AS v FROM t OFFSET 0) WHERE keep_value(" + id + ", v))")
                                .getRows().get(0)[0]).longValue();
                        assertEquals(rows, got);
                    }
                    finally {
                        tests.remove(id);
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        assertTrue("removed after", tests.isEmpty());
        System.out.println("50 filtered queries over 5,000 rows called the predicate " + calls.get() + " times");
        assertEquals("seven hosts, asked once each per query", 50 * 7, calls.get());

        calls.set(0);
        tests.put(0L, filters.get(0));
        run(table, "SELECT count(*) FROM t WHERE host IN (SELECT v FROM (SELECT DISTINCT host AS v FROM t)"
                + " WHERE keep_value(0, v))");
        System.out.println("without OFFSET 0, one query called it " + calls.get() + " times for 5,000 rows");
    }

    @Test
    public void replacingAFunctionWhileAQueryRunsKeepsTheOldOneForThatQuery() throws Exception {
        java.util.concurrent.CountDownLatch calling = new java.util.concurrent.CountDownLatch(1);
        FileTable table = keep(wide().function("weight", Long.class, Long.class, ms -> {
            calling.countDown();
            pause(200_000);
            return 1L;
        }).open());
        table.update();
        run(table, "SELECT count(time) FROM t");   // time stored first, so the query below starts at once
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<GuardedResult> running = pool.submit(() -> run(table, "SELECT sum(weight(time)) FROM t"));
        // Replaced once the query is calling it - not after a guessed wait, which on a slow machine
        // can land before the query has begun.
        assertTrue(calling.await(30, TimeUnit.SECONDS));
        assertFalse("still running when replaced", running.isDone());
        table.function("weight", Long.class, Long.class, ms -> 2L);
        assertEquals("the next query gets the new one", 2L * ROWS,
                ((Number) run(table, "SELECT sum(weight(time)) FROM t").getRows().get(0)[0]).longValue());
        assertEquals("the running one kept the old", (long) ROWS,
                ((Number) running.get(30, TimeUnit.SECONDS).getRows().get(0)[0]).longValue());
        pool.shutdown();
    }

    @Test
    public void aCancelledQueryStopsInsideASlowFunctionAndAnotherRunningQueryStillFinishes() throws Exception {
        AtomicLong calls = new AtomicLong();
        FileTable table = keep(wide().function("slow", Long.class, Long.class, ms -> {
            calls.incrementAndGet();
            pause(1_000_000);   // 1 ms a row
            return 1L;
        }).open());
        table.update();
        run(table, "SELECT count(time) FROM t");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Another query calling the same function, not cancelled: 400 rows, about 0.4 s.
        Future<GuardedResult> other = pool.submit(() ->
                run(table, "SELECT sum(slow(time)) FROM (SELECT time FROM t ORDER BY time LIMIT 400)"));
        CancelSignal stop = new CancelSignal();
        Future<Double> stopped = pool.submit(() -> {
            long started = System.nanoTime();
            try {
                table.query("SELECT sum(slow(time)) FROM t", QueryPolicy.readOnly(), stop);
                return -1.0;
            }
            catch (QueryCancelled expected) {
                return (System.nanoTime() - started) / 1e6;
            }
        });
        Thread.sleep(200);
        long cancelled = System.nanoTime();
        stop.cancel();
        double tookMs = stopped.get(30, TimeUnit.SECONDS);
        double afterCancelMs = (System.nanoTime() - cancelled) / 1e6;
        assertTrue("it was cancelled, not finished", tookMs > 0);
        assertEquals("the other query finished with its own answer", 400L,
                ((Number) other.get(30, TimeUnit.SECONDS).getRows().get(0)[0]).longValue());
        pool.shutdown();
        System.out.printf("a query calling a 1 ms function stopped %.0f ms after its cancel; DuckDB alone took 2,666 ms%n",
                afterCancelMs);
        assertTrue("stopped " + afterCancelMs + " ms after the cancel", afterCancelMs < 200);

        // A signal polled from a flag stops it within the poll interval too.
        AtomicBoolean button = new AtomicBoolean();
        CancelSignal polled = CancelSignal.polling(button::get, java.time.Duration.ofMillis(20));
        Future<Double> polledRun = Executors.newSingleThreadExecutor().submit(() -> {
            try {
                table.query("SELECT sum(slow(time)) FROM t", QueryPolicy.readOnly(), polled);
                return -1.0;
            }
            catch (QueryCancelled expected) {
                return 0.0;
            }
        });
        Thread.sleep(200);
        long pressed = System.nanoTime();
        button.set(true);
        assertEquals(0.0, polledRun.get(30, TimeUnit.SECONDS), 0);
        double polledMs = (System.nanoTime() - pressed) / 1e6;
        System.out.printf("with a signal polled every 20 ms: stopped %.0f ms after the flag was set%n", polledMs);
        assertTrue("stopped " + polledMs + " ms after the flag", polledMs < 250);

        assertEquals("a query after them runs as usual", 10L, ((Number) run(table,
                "SELECT sum(slow(time)) FROM (SELECT time FROM t LIMIT 10)").getRows().get(0)[0]).longValue());
    }

    @Test
    public void hardeningHoldsInQueriesThatCallFunctionsBeforeAndAfterARebuild() throws Exception {
        Path log = folder.getRoot().toPath().resolve("app.log");
        FileTableTest.write(log, 0, 1000, false);
        FileTable table = keep(FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(cache).version("functions-log").function("shout", String.class, String.class,
                        text -> text.toUpperCase()).open());
        table.update();
        Path other = cache.resolve("someone-else.csv");
        Files.writeString(other, "a\n1\n");
        for (int round = 0; round < 2; round++) {
            assertEquals("MESSAGE 7", run(table, "SELECT shout(message) FROM t WHERE line = 8").getRows().get(0)[0]);
            for (String sql : new String[] {
                    "SELECT shout(a) FROM read_csv('" + other + "')",
                    "SELECT shout(a) FROM read_csv('https://example.com/x.csv')",
                    "SELECT shout(message) FROM t, read_text('" + cache + "/*')"}) {
                try {
                    run(table, sql);
                    fail(sql);
                }
                catch (QueryFailed expected) {
                    assertEquals(sql, "Permission", expected.getErrorType());
                }
            }
            for (String sql : new String[] {"CREATE MACRO shout(x) AS x", "CREATE FUNCTION f(x) AS x",
                    "CREATE OR REPLACE MACRO shout(x) AS 'replaced'"}) {
                try {
                    run(table, sql);
                    fail(sql);
                }
                catch (QueryRejected expected) {
                    assertFalse(sql, expected instanceof QueryFailed);
                }
            }
            // The file is rotated: the table is emptied and loaded again in the same database.
            Path next = folder.getRoot().toPath().resolve("next.log");
            FileTableTest.write(next, 0, 300, false);
            Files.move(next, log, StandardCopyOption.REPLACE_EXISTING);
            assertTrue(table.update().rebuilt());
        }
        assertEquals("MESSAGE 7", run(table, "SELECT shout(message) FROM t WHERE line = 8").getRows().get(0)[0]);
    }

    @Test
    public void aCopyATableInMemoryAndOneOpenedAgainGetTheFunctionsToo() throws Exception {
        FileTable.Builder builder = wide().function("double_it", Long.class, Long.class, x -> 2 * x);
        FileTable first = keep(builder.open());
        FileTable copy = keep(builder.open());
        assertNotEquals("the second is a copy", first.cacheFile(), copy.cacheFile());
        assertTrue(copy.cacheFile().getFileName().toString().endsWith(".2.duckdb"));
        FileTable inMemory = keep(FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser).lazyColumns(true)
                .function("double_it", Long.class, Long.class, x -> 2 * x).open());
        assertNull(inMemory.cacheFile());
        for (FileTable table : List.of(first, copy, inMemory)) {
            table.update();
            assertEquals(2 * START_MS, ((Number) run(table, "SELECT double_it(min(time)) FROM t").getRows().get(0)[0])
                    .longValue());
        }
        first.close();
        opened.remove(first);
        FileTable again = keep(builder.open());
        assertEquals(first.cacheFile(), again.cacheFile());
        assertEquals(2 * START_MS, ((Number) run(again, "SELECT double_it(min(time)) FROM t").getRows().get(0)[0])
                .longValue());
        try {
            first.function("late", Long.class, Long.class, x -> x);
            fail("a closed table takes no functions");
        }
        catch (IllegalStateException expected) {
            // closed
        }
    }

    private static void pause(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
