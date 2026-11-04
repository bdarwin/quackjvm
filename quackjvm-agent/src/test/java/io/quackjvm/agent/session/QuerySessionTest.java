package io.quackjvm.agent.session;

import io.quackjvm.agent.catalog.internal.SqlNames;
import io.quackjvm.agent.catalog.Profile;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.core.layout.ColumnarLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Named tables in one hardened database, added and taken away while it runs - issue #14. */
public class QuerySessionTest {

    private static final String LONG = "SELECT count(*) FROM range(100000000000) t(x) WHERE x % 7 = 3";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path data;
    private Path elsewhere;
    private Path temp;
    private QuerySession session;

    public record Note(long id, String text) {
    }

    @Before
    public void files() throws Exception {
        data = folder.newFolder("data").toPath();
        elsewhere = folder.newFolder("elsewhere").toPath();
        temp = folder.newFolder("temp").toPath();
        Files.writeString(data.resolve("readings-2026.csv"), "id,value\n1,10\n2,20\n3,30\n");
        Files.writeString(data.resolve("devices.csv"), "id,name\n1,ann\n2,bob\n");
        Files.writeString(elsewhere.resolve("regions.csv"), "id,region\n1,north\n2,south\n3,east\n");
        session = QuerySession.open(Hardening.options().allowedFolders(data.toString()).tempFolder(temp.toString()),
                QueryPolicy.readOnly().timeout(Duration.ofMinutes(1)));
    }

    @After
    public void close() {
        session.close();
    }

    private long count(String sql) {
        return ((Number) session.run(sql).getRows().get(0)[0]).longValue();
    }

    // ---------- the issue's acceptance tests ----------

    @Test
    public void twoFilesRegisteredJoinThroughAnAliasAndOneTakenAwayIsGone() {
        String readings = session.register(data.resolve("readings-2026.csv"));
        String devices = session.register(data.resolve("devices.csv"));
        assertEquals("readings_2026", readings);
        assertEquals("devices", devices);
        session.alias("t", readings);

        GuardedResult joined = session.run("SELECT name, value FROM t JOIN devices USING (id) ORDER BY id");
        assertEquals(2, joined.getRowCount());
        assertEquals("ann", joined.getRows().get(0)[0]);

        session.unregister("devices");
        try {
            session.run("SELECT name, value FROM t JOIN devices USING (id)");
            fail();
        }
        catch (QueryFailed failed) {
            assertTrue(failed.getMessage(), failed.getMessage().contains("Table with name devices does not exist"));
        }
        assertEquals(List.of("readings_2026"), session.names());
    }

    @Test
    public void allowingAFolderKeepsEveryRegistrationAliasAndName() {
        String readings = session.register(data.resolve("readings-2026.csv"));
        session.register("big", "SELECT * FROM range(5) r(x)");
        List<Note> notes = new CopyOnWriteArrayList<>(List.of(new Note(1, "a"), new Note(2, "b")));
        session.register("note", () -> notes, ColumnarLayout.ofRecord(Note.class));
        session.alias("t", readings);

        session.allowFolders(List.of(elsewhere));

        assertEquals(List.of("readings_2026", "big", "note"), session.names());
        assertEquals(Map.of("t", "readings_2026"), session.aliases());
        assertEquals(3, count("SELECT count(*) FROM t"));
        assertEquals(5, count("SELECT count(*) FROM big"));
        assertEquals(2, count("SELECT count(*) FROM note"));
        notes.add(new Note(3, "c"));
        assertEquals("a live table is still live", 3, count("SELECT count(*) FROM note"));
        assertEquals(3, count("SELECT count(*) FROM read_csv('" + elsewhere.resolve("regions.csv") + "')"));
        assertTrue(session.allowedFolders().contains(elsewhere.toString()));
    }

    @Test
    public void registeringAFileFromAFolderNotAllowedYetLetsItsFolderIn() {
        session.register(data.resolve("readings-2026.csv"));
        assertFalse(session.allowedFolders().contains(elsewhere.toString()));
        assertEquals("regions", session.register(elsewhere.resolve("regions.csv")));
        assertTrue(session.allowedFolders().contains(elsewhere.toString()));
        assertEquals(3, count("SELECT count(*) FROM regions JOIN readings_2026 USING (id)"));
    }

    @Test
    public void aFolderAlreadyAllowedOrBelowOneDoesNotReopen() throws Exception {
        Path below = Files.createDirectories(data.resolve("below"));
        List<String> before = session.allowedFolders();
        session.allowFolders(List.of(data, below));
        assertEquals(before, session.allowedFolders());
    }

    @Test
    public void aStopAlreadyTrueStopsTheQueryBeforeItRuns() {
        try {
            session.run("SELECT * FROM not_there", CancelSignal.from(() -> true));
            fail();
        }
        catch (QueryCancelled expected) {
            // not a QueryFailed: nothing ran
        }
    }

    @Test
    public void aStopThatTurnsTrueStopsALongQueryWithin100Ms() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong flipped = new AtomicLong();
        Thread flipper = new Thread(() -> {
            try {
                Thread.sleep(200);
            }
            catch (InterruptedException e) {
                return;
            }
            flipped.set(System.nanoTime());
            stop.set(true);
        });
        flipper.start();
        try {
            session.run(LONG, CancelSignal.from(stop::get));
            fail();
        }
        catch (QueryCancelled expected) {
            long after = (System.nanoTime() - flipped.get()) / 1_000_000;
            assertTrue("stopped " + after + " ms after", after < 100);
        }
        flipper.join();
        assertEquals(1, count("SELECT 1"));
    }

    @Test
    public void tableNamesFollowTheRules() {
        Set<String> none = Set.of();
        assertEquals("readings_2026_q4", QuerySession.tableName(Path.of("Readings 2026-Q4.tsv"), none));
        assertEquals("table_2026", QuerySession.tableName(Path.of("2026.csv"), none));
        assertEquals("readings_2026", QuerySession.tableName(Path.of("/x/readings-2026.csv"), none));
        assertEquals("readings_2", QuerySession.tableName(Path.of("readings.csv"), Set.of("readings")));
        assertEquals("readings_3", QuerySession.tableName(Path.of("readings.csv"), Set.of("readings", "READINGS_2")));
        assertEquals("t_2", QuerySession.tableName(Path.of("t.csv"), none));
        assertEquals("log_2", QuerySession.tableName(Path.of("log.txt"), none));
        assertEquals("log_3", QuerySession.tableName(Path.of("log.txt"), Set.of("log_2")));
        assertEquals("table_order", QuerySession.tableName(Path.of("order.csv"), none));
        assertEquals("table_join", QuerySession.tableName(Path.of("Join.parquet"), none));
        assertEquals("events", QuerySession.tableName(Path.of("events.csv.gz"), none));
        assertEquals("my_data", QuerySession.tableName(Path.of("my.data.csv"), none));
        assertEquals("cafe_menu", QuerySession.tableName(Path.of("Café menu.csv"), none));
        assertEquals("table", QuerySession.tableName(Path.of("日本.csv"), none));
        assertEquals("table_2", QuerySession.tableName(Path.of("---.csv"), Set.of("table")));
        assertEquals("readme", QuerySession.tableName(Path.of("README"), none));
        assertEquals("bashrc", QuerySession.tableName(Path.of(".bashrc"), none));
    }

    @Test
    public void aFolderWithABackslashInItsNameCanBeRegisteredFrom() throws Exception {
        // Issue #12, on any OS: a backslash is legal in a name on macOS and Linux.
        Path odd = Files.createDirectories(folder.getRoot().toPath().resolve("back\\slash"));
        Files.writeString(odd.resolve("x.csv"), "a\n1\n2\n");
        assertEquals("x", session.register(odd.resolve("x.csv")));
        assertEquals(2, count("SELECT count(*) FROM x"));
    }

    // ---------- names and aliases ----------

    @Test
    public void theKeywordListIsDuckDbs() throws Exception {
        Set<String> duckdb = new HashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT keyword_name FROM duckdb_keywords()"
                     + " WHERE keyword_category IN ('reserved', 'type_function')")) {
            while (r.next()) {
                duckdb.add(r.getString(1));
            }
        }
        assertEquals(duckdb, SqlNames.KEYWORDS);
    }

    @Test
    public void everyNameItGivesCanBeWrittenWithoutQuotes() throws Exception {
        for (String keyword : SqlNames.KEYWORDS) {
            Files.writeString(data.resolve(keyword + ".csv"), "a\n1\n");
            String name = session.register(data.resolve(keyword + ".csv"));
            assertEquals(name, 1, count("SELECT count(*) FROM " + name));
        }
    }

    @Test
    public void registeringTheSameFileTwiceGivesTwoNames() {
        assertEquals("devices", session.register(data.resolve("devices.csv")));
        assertEquals("devices_2", session.register(data.resolve("devices.csv")));
        assertEquals(List.of("devices", "devices_2"), session.names());
    }

    @Test
    public void anAliasMovesAndGoesWithItsTable() {
        session.register(data.resolve("readings-2026.csv"));
        session.register(data.resolve("devices.csv"));
        session.alias("t", "readings_2026");
        assertEquals(3, count("SELECT count(*) FROM t"));
        session.alias("t", "devices");
        assertEquals(2, count("SELECT count(*) FROM t"));
        session.unregister("devices");
        assertTrue(session.aliases().isEmpty());
        try {
            session.run("SELECT * FROM t");
            fail();
        }
        catch (QueryFailed expected) {
            assertEquals("Catalog", expected.getErrorType());
        }
    }

    @Test
    public void anAliasNeedsATableAndCannotTakeATablesName() {
        session.register(data.resolve("devices.csv"));
        session.register("other", "SELECT 1 AS x");
        try {
            session.alias("t", "nothing");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nothing"));
        }
        try {
            session.alias("other", "devices");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("registered table"));
        }
        session.alias("t", "devices");
        try {
            session.register("t", "SELECT 1");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("alias"));
        }
        session.unalias("t");
        session.register("t", "SELECT 7 AS seven");
        assertEquals(7, count("SELECT seven FROM t"));
    }

    // ---------- registering ----------

    @Test
    public void aTableUnderATakenNameIsReplacedAndOneThatFailsLeavesTheOldOne() {
        session.register("x", "SELECT 1 AS a");
        session.register("x", "SELECT 2 AS a");
        assertEquals(2, count("SELECT a FROM x"));
        try {
            session.register("x", "SELECT * FROM missing_table");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("missing_table"));
        }
        assertEquals("the old one is still there", 2, count("SELECT a FROM x"));
        assertEquals(List.of("x"), session.names());
    }

    @Test
    public void aLiveTableReplacedByAViewAndBack() {
        List<Note> notes = List.of(new Note(1, "a"));
        session.register("n", () -> notes, ColumnarLayout.ofRecord(Note.class));
        assertEquals(1, count("SELECT count(*) FROM n"));
        session.register("n", "SELECT * FROM range(4)");
        assertEquals(4, count("SELECT count(*) FROM n"));
        session.register("n", () -> notes, ColumnarLayout.ofRecord(Note.class));
        assertEquals(1, count("SELECT count(*) FROM n"));
        session.unregister("n");
        assertTrue(session.names().isEmpty());
        try {
            session.run("SELECT * FROM n");
            fail();
        }
        catch (QueryFailed expected) {
            // gone
        }
    }

    @Test
    public void eachKindOfFileGetsItsReader() throws Exception {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(data.resolve("zipped.csv.gz")))) {
            out.write("a,b\n1,2\n3,4\n5,6\n".getBytes());
        }
        Files.writeString(data.resolve("rows.jsonl"), "{\"a\":1}\n{\"a\":2}\n");
        Files.writeString(data.resolve("tabbed.tsv"), "a\tb\n1\t2\n");
        Files.writeString(data.resolve("plain.log"), "level,message\nINFO,started\nWARN,slow\n");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM range(7) r(x)) TO '" + data.resolve("seven.parquet") + "' (FORMAT parquet)");
        }
        assertEquals(3, count("SELECT count(*) FROM " + session.register(data.resolve("zipped.csv.gz"))));
        assertEquals(2, count("SELECT count(*) FROM " + session.register(data.resolve("rows.jsonl"))));
        assertEquals(2, count("SELECT b FROM " + session.register(data.resolve("tabbed.tsv"))));
        assertEquals(2, count("SELECT count(*) FROM " + session.register(data.resolve("plain.log"))));
        assertEquals(7, count("SELECT count(*) FROM " + session.register(data.resolve("seven.parquet"))));
        assertEquals(List.of("zipped", "rows", "tabbed", "plain", "seven"), session.names());
    }

    @Test
    public void aFileThatCannotBeReadIsRefusedWithDuckDbsReason() {
        try {
            session.register(data.resolve("not-there.csv"));
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not-there.csv"));
        }
        assertTrue(session.names().isEmpty());
    }

    @Test
    public void whenAReopenFailsTheSessionIsAsItWas() throws Exception {
        Path gone = data.resolve("gone.csv");
        Files.writeString(gone, "a\n1\n");
        session.register("kept", "SELECT * FROM read_csv('" + gone + "')");
        session.register(data.resolve("devices.csv"));
        Files.delete(gone);   // the view can no longer be made again
        List<String> before = session.allowedFolders();
        try {
            session.allowFolders(List.of(elsewhere));
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("as it was"));
        }
        assertEquals(before, session.allowedFolders());
        assertEquals(List.of("kept", "devices"), session.names());
        assertEquals(2, count("SELECT count(*) FROM devices"));
    }

    // ---------- still hardened, still guarded ----------

    @Test
    public void sqlStillReachesNothingOutsideTheAllowedFolders() {
        for (String sql : new String[] {
                "SELECT * FROM read_csv('" + elsewhere.resolve("regions.csv") + "')",
                "SELECT * FROM read_csv('https://example.com/a.csv')"}) {
            try {
                session.run(sql);
                fail(sql);
            }
            catch (QueryFailed expected) {
                assertEquals("Permission", expected.getErrorType());
            }
        }
        session.allowFolders(List.of(elsewhere));
        Path third = folder.getRoot().toPath().resolve("third");
        try {
            Files.createDirectories(third);
            Files.writeString(third.resolve("a.csv"), "a\n1\n");
        }
        catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        for (String sql : new String[] {
                "SELECT * FROM read_csv('https://example.com/a.csv')",
                "SELECT * FROM read_csv('" + third.resolve("a.csv") + "')"}) {
            try {
                session.run(sql);
                fail(sql);
            }
            catch (QueryFailed expected) {
                assertEquals("still hardened after reopening", "Permission", expected.getErrorType());
            }
        }
    }

    @Test
    public void statementsThatWriteAreRefused() {
        session.register(data.resolve("devices.csv"));
        for (String sql : new String[] {"DROP VIEW devices", "COPY devices TO '" + data.resolve("out.csv") + "'",
                "SET threads = 1", "CREATE TABLE x AS SELECT 1"}) {
            try {
                session.run(sql);
                fail(sql);
            }
            catch (QueryRejected expected) {
                assertFalse(sql, expected instanceof QueryFailed);
            }
            assertTrue(session.whyRejected(sql) != null);
        }
        assertNull(session.whyRejected("SELECT * FROM devices"));
        assertEquals(2, count("SELECT count(*) FROM devices"));
    }

    @Test
    public void aPolicyForOneStatement() {
        session.register("many", "SELECT * FROM range(1000) r(x)");
        GuardedResult first = session.run("SELECT * FROM many", null,
                QueryPolicy.readOnly().maxRows(10).whenOver(QueryPolicy.Over.TRUNCATE));
        assertEquals(10, first.getRowCount());
        assertTrue(first.isTruncated());
        assertEquals(1000, session.run("SELECT * FROM many").getRowCount());
    }

    @Test
    public void describeGivesTheColumns() {
        String readings = session.register(data.resolve("readings-2026.csv"));
        assertEquals(List.of("id", "value"),
                session.describe(readings).columns().stream().map(c -> c.name()).toList());
    }

    // ---------- threads ----------

    @Test
    public void aChangeWaitsForTheQueriesRunningThen() throws Exception {
        session.register("x", "SELECT 1 AS a");
        CountDownLatch started = new CountDownLatch(1);
        AtomicLong queryEnded = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> query = pool.submit(() -> {
                started.countDown();
                session.run("SELECT count(*) FROM range(400000000) t(x) WHERE x % 7 = 3");
                queryEnded.set(System.nanoTime());
            });
            started.await();
            Thread.sleep(50);
            session.register("y", "SELECT 2 AS a");
            long registered = System.nanoTime();
            query.get(1, TimeUnit.MINUTES);
            assertTrue("the change waited for the query", registered >= queryEnded.get());
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void queriesFromManyThreadsWhileTablesComeAndGo() throws Exception {
        session.register(data.resolve("devices.csv"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                readers.add(pool.submit(() -> {
                    while (running.get()) {
                        try {
                            assertEquals(2L, ((Number) session.run("SELECT count(*) FROM devices").getRows().get(0)[0])
                                    .longValue());
                            session.run("SELECT count(*) FROM coming_and_going");
                        }
                        catch (QueryFailed expected) {
                            // coming_and_going is not there just now
                        }
                        catch (Throwable t) {
                            unexpected.add(t);
                        }
                    }
                }));
            }
            for (int i = 0; i < 40; i++) {
                session.register("coming_and_going", "SELECT * FROM range(" + i + ")");
                if (i % 10 == 9) {
                    session.allowFolders(List.of(Files.createDirectories(elsewhere.resolve("f" + i))));
                }
                session.unregister("coming_and_going");
            }
            running.set(false);
            for (Future<?> reader : readers) {
                reader.get(1, TimeUnit.MINUTES);
            }
        }
        finally {
            running.set(false);
            pool.shutdownNow();
        }
        assertTrue(unexpected.toString(), unexpected.isEmpty());
        assertEquals(List.of("devices"), session.names());
    }

    @Test
    public void examplesForTheTableUnderItsAliasJoinTheOthersAndAllRun() {
        String readings = session.register(data.resolve("readings-2026.csv"));
        session.register(data.resolve("devices.csv"));
        session.alias("t", readings);
        List<String> sql = session.examples("t").stream().map(e -> e.sql()).toList();
        assertTrue(sql.toString(), sql.contains("SELECT * FROM t JOIN devices USING (id) LIMIT 10"));
        assertTrue("not joined to itself: " + sql, sql.stream().noneMatch(s -> s.contains("JOIN readings_2026")));
        for (String example : sql) {
            session.run(example);
        }
    }

    @Test
    public void aProfilesDistinctCountsChooseTheGroupColumnOfTheExamples() throws Exception {
        StringBuilder csv = new StringBuilder("visitor,colour,host,weight\n");
        for (int x = 0; x < 2000; x++) {
            csv.append("v").append(x).append(',').append(x % 2 == 0 ? "red" : "blue").append(",h").append(x)
                    .append(',').append(x).append('\n');
        }
        Files.writeString(data.resolve("seen.csv"), csv);
        String seen = session.register(data.resolve("seen.csv"));
        session.alias("t", seen);
        String byName = "SELECT host, count(*) AS rows, avg(weight) AS mean_weight, max(weight) AS highest_weight"
                + " FROM t GROUP BY 1 ORDER BY 2 DESC LIMIT 20";
        assertTrue(session.examples("t").stream().anyMatch(e -> e.sql().equals(byName)));

        Profile profile = session.profile("t");
        assertEquals(2000, profile.rows());
        List<String> sql = session.examples("t", profile.distinctValues()).stream().map(e -> e.sql()).toList();
        assertTrue(sql.toString(), sql.contains(byName.replace("host", "colour")));
        assertFalse(sql.contains(byName));
        for (String example : sql) {
            session.run(example);
        }
    }

    @Test
    public void namesAreComparedWithoutCaseAsDuckDbComparesThem() {
        session.register("Things", "SELECT 1 AS a");
        session.register("things", "SELECT 2 AS a");
        assertEquals("one table, replaced", List.of("things"), session.names());
        assertEquals(2, count("SELECT a FROM THINGS"));
        session.alias("T", "THINGS");
        session.alias("t", "things");
        assertEquals(Map.of("t", "things"), session.aliases());
        try {
            session.register("T", "SELECT 3");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("alias"));
        }
        session.unregister("THINGS");
        assertTrue(session.names().isEmpty());
        assertTrue(session.aliases().isEmpty());
    }

    @Test
    public void aTableReplacedKeepsItsPlaceSoViewsOverItSurviveAReopen() {
        session.register("base", "SELECT 1 AS a");
        session.register("over", "SELECT a + 1 AS b FROM base");
        session.register("BASE", "SELECT 10 AS a");
        assertEquals(List.of("BASE", "over"), session.names());
        session.allowFolders(List.of(elsewhere));
        assertEquals(11, count("SELECT b FROM over"));
    }

    @Test
    public void aClosedSessionSaysSo() {
        session.close();
        assertFalse(session.isOpen());
        for (Runnable call : Arrays.<Runnable>asList(() -> session.run("SELECT 1"),
                () -> session.register("x", "SELECT 1"), () -> session.allowFolders(List.of(elsewhere)))) {
            try {
                call.run();
                fail();
            }
            catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("closed"));
            }
        }
        session.close();   // twice is fine
    }
}
