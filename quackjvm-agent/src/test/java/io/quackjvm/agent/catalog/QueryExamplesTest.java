package io.quackjvm.agent.catalog;

import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.core.Quack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Queries written from a table's columns - issues #14 and #19. Every one has to run as written, in a
 * hardened database, on an empty table and on a full one; the ones whose answer can be worked out in
 * Java are checked against it.
 */
public class QueryExamplesTest {

    private Quack db;
    private Catalog catalog;
    private GuardedQuery guard;

    @Before
    public void open() {
        db = Hardening.openSandbox();
        catalog = Catalog.of(db);
        guard = GuardedQuery.on(db, QueryPolicy.readOnly());
    }

    @After
    public void close() {
        db.close();
    }

    private List<String> sql(String table) {
        return catalog.examples(table).stream().map(QueryExamples.Example::sql).collect(Collectors.toList());
    }

    private String find(String table, String titleStart) {
        for (QueryExamples.Example example : catalog.examples(table)) {
            if (example.title().startsWith(titleStart)) {
                return example.sql();
            }
        }
        throw new AssertionError("No example \"" + titleStart + "...\" in " + catalog.examples(table));
    }

    /** Runs every example through the guard; a broken one fails the test with its SQL. */
    private void allRun(String table) {
        for (QueryExamples.Example example : catalog.examples(table)) {
            GuardedResult result;
            try {
                result = guard.run(example.sql());
            }
            catch (RuntimeException e) {
                throw new AssertionError(example.sql(), e);
            }
            assertTrue(example.sql(), result.getColumns().size() > 0);
            assertFalse(example.title(), example.title().isBlank());
        }
    }

    /** Runs every example on the table empty, then with the rows the insert adds. */
    private void allRunEmptyAndFull(String table, String... inserts) {
        allRun(table);
        for (String insert : inserts) {
            db.execute(insert);
        }
        allRun(table);
    }

    private static final String LOG = "CREATE TABLE log (line BIGINT, time TIMESTAMP, level VARCHAR, thread VARCHAR,"
            + " logger VARCHAR, message VARCHAR)";

    /** 5,000 lines a second apart: every 10th a WARN, every 50th an ERROR, some with a time in ms. */
    private static final String LOG_ROWS = "INSERT INTO log SELECT x, TIMESTAMP '2026-01-01' + INTERVAL (x) SECOND,"
            + " CASE WHEN x % 50 = 0 THEN 'ERROR' WHEN x % 10 = 0 THEN 'WARN' ELSE 'INFO' END,"
            + " 'worker-' || (x % 3), 'app.' || CASE WHEN x % 4 = 0 THEN 'store' ELSE 'web' END,"
            + " CASE WHEN x % 50 = 0 THEN 'timeout after ' || x || ' tries'"
            + " WHEN x % 7 = 0 THEN 'request took ' || (x % 1000) || '.5 ms' ELSE 'step ' || x END"
            + " FROM range(5000) r(x)";

    @Test
    public void aLogGetsTheTableExamplesAndTheLogOnes() {
        db.execute(LOG);
        assertEquals(List.of(
                "SELECT count(*) AS rows FROM log",
                "SELECT * FROM log LIMIT 10",
                "SUMMARIZE log",
                "SELECT date_trunc('hour', time) AS hour, count(*) AS rows FROM log WHERE time IS NOT NULL"
                        + " GROUP BY 1 ORDER BY 1",
                "SELECT * FROM log WHERE message ILIKE '%error%' LIMIT 50",
                "SELECT level, count(*) AS lines FROM log WHERE level IS NOT NULL GROUP BY 1 ORDER BY 2 DESC",
                "SELECT date_trunc('minute', time) AS minute, count(*) AS errors FROM log WHERE upper(level) = 'ERROR'"
                        + " GROUP BY 1 ORDER BY 1",
                "SELECT regexp_replace(message, '[0-9]+', '#', 'g') AS error, count(*) AS times FROM log"
                        + " WHERE upper(level) = 'ERROR' GROUP BY 1 ORDER BY 2 DESC LIMIT 20",
                "SELECT logger, count(*) FILTER (WHERE upper(level) = 'ERROR') AS errors,"
                        + " count(*) FILTER (WHERE upper(level) IN ('WARN', 'WARNING')) AS warnings FROM log"
                        + " WHERE upper(level) = 'ERROR' OR upper(level) IN ('WARN', 'WARNING')"
                        + " GROUP BY 1 ORDER BY 2 DESC, 3 DESC LIMIT 20",
                "SELECT line, time, message, CAST(regexp_extract(message, '([0-9]+(\\.[0-9]+)?) ?ms\\b', 1) AS DOUBLE)"
                        + " AS ms FROM log WHERE regexp_matches(message, '[0-9]+(\\.[0-9]+)? ?ms\\b')"
                        + " ORDER BY ms DESC LIMIT 20"), sql("log"));
        allRunEmptyAndFull("log", LOG_ROWS);
    }

    @Test
    public void theLogExamplesGiveWhatTheLinesSay() {
        db.execute(LOG);
        db.execute(LOG_ROWS);
        Map<String, Long> perLevel = new HashMap<>();
        Map<String, long[]> byLogger = new TreeMap<>();
        Map<Long, Long> errorsPerMinute = new TreeMap<>();
        double slowest = 0;
        for (long x = 0; x < 5000; x++) {
            String level = x % 50 == 0 ? "ERROR" : x % 10 == 0 ? "WARN" : "INFO";
            perLevel.merge(level, 1L, Long::sum);
            String logger = x % 4 == 0 ? "app.store" : "app.web";
            if (!level.equals("INFO")) {
                byLogger.computeIfAbsent(logger, k -> new long[2])[level.equals("ERROR") ? 0 : 1]++;
            }
            if (level.equals("ERROR")) {
                errorsPerMinute.merge(x / 60, 1L, Long::sum);
            }
            else if (x % 7 == 0) {
                slowest = Math.max(slowest, x % 1000 + 0.5);
            }
        }

        GuardedResult levels = guard.run(find("log", "Lines per level"));
        assertEquals(3, levels.getRowCount());
        for (Object[] row : levels.getRows()) {
            assertEquals(String.valueOf(row[0]), perLevel.get((String) row[0]), ((Number) row[1]).longValue(), 0);
        }
        assertEquals("INFO", levels.getRows().get(0)[0]);

        GuardedResult minutes = guard.run(find("log", "Errors per minute"));
        assertEquals(errorsPerMinute.size(), minutes.getRowCount());
        assertEquals(errorsPerMinute.values().stream().mapToLong(Long::longValue).sum(),
                minutes.getRows().stream().mapToLong(r -> ((Number) r[1]).longValue()).sum());

        GuardedResult frequent = guard.run(find("log", "The most frequent errors"));
        assertEquals("one error, its numbers blanked", 1, frequent.getRowCount());
        assertEquals("timeout after # tries", frequent.getRows().get(0)[0]);
        assertEquals(perLevel.get("ERROR"), ((Number) frequent.getRows().get(0)[1]).longValue(), 0);

        GuardedResult loggers = guard.run(find("log", "Errors and warnings by logger"));
        assertEquals(byLogger.size(), loggers.getRowCount());
        for (Object[] row : loggers.getRows()) {
            long[] counts = byLogger.get((String) row[0]);
            assertEquals(counts[0], ((Number) row[1]).longValue());
            assertEquals(counts[1], ((Number) row[2]).longValue());
        }

        GuardedResult durations = guard.run(find("log", "Durations in the messages"));
        assertEquals(20, durations.getRowCount());
        assertEquals(slowest, ((Number) durations.getRows().get(0)[3]).doubleValue(), 0);
        double previous = Double.MAX_VALUE;
        for (Object[] row : durations.getRows()) {
            double ms = ((Number) row[3]).doubleValue();
            assertTrue("slowest first", ms <= previous);
            assertTrue(String.valueOf(row[2]), ((String) row[2]).endsWith(ms + " ms"));
            previous = ms;
        }
    }

    @Test
    public void aLogWithoutTimeOrLoggerGetsTheLogExamplesThatNeedNeither() {
        db.execute("CREATE TABLE \"Plain Log\" (\"Level\" VARCHAR, \"Message\" VARCHAR)");
        List<String> sql = sql("Plain Log");
        assertTrue(sql.toString(), sql.contains("SELECT \"Level\", count(*) AS lines FROM \"Plain Log\""
                + " WHERE \"Level\" IS NOT NULL GROUP BY 1 ORDER BY 2 DESC"));
        assertTrue(sql.stream().noneMatch(s -> s.contains("'minute'") || s.contains("FILTER")));
        assertTrue(sql.toString(), sql.stream().anyMatch(s -> s.startsWith("SELECT \"Message\", CAST(regexp_extract(")));
        allRunEmptyAndFull("Plain Log", "INSERT INTO \"Plain Log\" VALUES ('error', 'disk 3 full'),"
                + " ('info', 'saved in 12 ms'), ('ERROR', 'disk 4 full')");
        GuardedResult frequent = guard.run(find("Plain Log", "The most frequent errors"));
        assertEquals("any case of ERROR", 2L, ((Number) frequent.getRows().get(0)[1]).longValue());
    }

    @Test
    public void aMeasureGetsPercentilesItsHighestRowsAndItsMeanAndHighestPerGroup() {
        db.execute("CREATE TABLE readings (id BIGINT, host VARCHAR, note VARCHAR, latency DOUBLE)");
        List<String> sql = sql("readings");
        assertTrue(sql.toString(), sql.contains("SELECT quantile_cont(latency, 0.5) AS median,"
                + " quantile_cont(latency, 0.95) AS p95, quantile_cont(latency, 0.99) AS p99 FROM readings"));
        assertTrue(sql.contains("SELECT * FROM readings ORDER BY latency DESC NULLS LAST LIMIT 10"));
        assertTrue(sql.toString(), sql.contains("SELECT host, count(*) AS rows, avg(latency) AS mean_latency,"
                + " max(latency) AS highest_latency FROM readings GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
        assertTrue("id counts rows, so it is no measure", sql.stream().noneMatch(s -> s.contains("quantile_cont(id")));
        allRunEmptyAndFull("readings", "INSERT INTO readings SELECT x, 'h' || (x % 4), 'n' || x,"
                + " CASE WHEN x % 100 = 0 THEN NULL ELSE x END FROM range(5000) r(x)");

        List<Double> values = new ArrayList<>();
        for (int x = 0; x < 5000; x++) {
            if (x % 100 != 0) {
                values.add((double) x);
            }
        }
        Object[] q = guard.run(find("readings", "The median and percentiles")).getRows().get(0);
        assertEquals(quantile(values, 0.5), ((Number) q[0]).doubleValue(), 1e-9);
        assertEquals(quantile(values, 0.95), ((Number) q[1]).doubleValue(), 1e-9);
        assertEquals(quantile(values, 0.99), ((Number) q[2]).doubleValue(), 1e-9);
        GuardedResult top = guard.run(find("readings", "The rows with the highest"));
        assertEquals(4999.0, ((Number) top.getRows().get(0)[3]).doubleValue(), 0);
        assertEquals(10, top.getRowCount());
    }

    /** What quantile_cont computes: linear between the two nearest ranks. */
    private static double quantile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        double at = q * (sorted.size() - 1);
        int below = (int) Math.floor(at);
        int above = Math.min(below + 1, sorted.size() - 1);
        return sorted.get(below) + (at - below) * (sorted.get(above) - sorted.get(below));
    }

    @Test
    public void aFamilyOfNumberedColumnsIsQueriedAllAtOnce() {
        StringBuilder ddl = new StringBuilder("CREATE TABLE wide (host VARCHAR, time TIMESTAMP");
        for (int i = 1; i <= 1500; i++) {
            ddl.append(", cpu_").append(String.format("%04d", i)).append(" DOUBLE");
        }
        ddl.append(", mem_1 BIGINT, mem_2 BIGINT, mem_3 BIGINT, disk2 BIGINT)");
        db.execute(ddl.toString());

        List<String> sql = sql("wide");
        String all = "COLUMNS('^cpu_[0-9]+$')";
        for (String expected : List.of(
                "SELECT avg(" + all + ") FROM wide",
                "SELECT avg(COLUMNS('^mem_[0-9]+$')) FROM wide",
                "SELECT host, avg(" + all + ") FROM wide GROUP BY 1 ORDER BY 1 LIMIT 20",
                "SELECT date_trunc('minute', time) AS minute, avg(" + all + ") FROM wide WHERE time IS NOT NULL"
                        + " GROUP BY 1 ORDER BY 1",
                "SELECT host, list_sum([*" + all + "]) AS cpu_total FROM wide LIMIT 10",
                "SELECT host, greatest(*" + all + ") AS highest FROM wide ORDER BY highest DESC NULLS LAST LIMIT 10",
                "SELECT series, avg(value) AS mean, max(value) AS highest FROM (UNPIVOT (SELECT " + all
                        + " FROM wide) ON COLUMNS(*) INTO NAME series VALUE value) GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 20",
                "SELECT column_name, column_type FROM (DESCRIBE wide) WHERE column_name ILIKE '%cpu%'",
                "SELECT min(disk2) AS lowest, max(disk2) AS highest, avg(disk2) AS mean FROM wide")) {
            assertTrue(expected + " in " + sql, sql.contains(expected));
        }
        assertTrue(sql.get(1), sql.get(1).startsWith("SELECT host, time, cpu_0001, cpu_0002,"));
        assertTrue(sql.get(1), sql.get(1).endsWith("cpu_0008 FROM wide LIMIT 10"));
        assertTrue("a wide table summarizes the columns it shows",
                sql.contains("SUMMARIZE " + sql.get(1).replace(" LIMIT 10", "")));

        StringBuilder insert = new StringBuilder("INSERT INTO wide SELECT 'h' || (x % 5),"
                + " TIMESTAMP '2026-01-01' + INTERVAL (x) SECOND");
        for (int i = 1; i <= 1500; i++) {
            insert.append(", (x * ").append(i).append(") % 1009");
        }
        insert.append(", x, x + 1, x + 2, x FROM range(5000) r(x)");
        allRunEmptyAndFull("wide", insert.toString());

        assertEquals(1500, guard.run("SELECT avg(" + all + ") FROM wide").getColumns().size());
        assertEquals(1501, guard.run(find("wide", "The mean of each of 1500 cpu_N columns for each host"))
                .getColumns().size());
        Object[] total = guard.run(find("wide", "The total of the 1500")).getRows().get(0);
        assertEquals("h0", total[0]);
        assertEquals("row x = 0 is all zeros", 0.0, ((Number) total[1]).doubleValue(), 0);
        GuardedResult rowTotals = guard.run("SELECT host, list_sum([*" + all + "]) FROM wide"
                + " WHERE time = TIMESTAMP '2026-01-01 00:00:07'");
        long expected = 0;
        for (int i = 1; i <= 1500; i++) {
            expected += (7L * i) % 1009;
        }
        assertEquals(expected, ((Number) rowTotals.getRows().get(0)[1]).doubleValue(), 0);
        GuardedResult highest = guard.run(find("wide", "The rows with the highest value in any of the 1500"));
        assertEquals(10, highest.getRowCount());
        assertEquals(1008.0, ((Number) highest.getRows().get(0)[1]).doubleValue(), 0);
        // each column's mean, worked out in Java: the highest first
        double best = -1;
        String bestName = null;
        for (int i = 1; i <= 1500; i++) {
            long sum = 0;
            for (long x = 0; x < 5000; x++) {
                sum += (x * i) % 1009;
            }
            double mean = sum / 5000.0;
            String name = String.format("cpu_%04d", i);
            if (mean > best + 1e-9 || (Math.abs(mean - best) <= 1e-9 && name.compareTo(bestName) < 0)) {
                best = mean;
                bestName = name;
            }
        }
        GuardedResult perColumn = guard.run(find("wide", "Each of the 1500 cpu_N columns"));
        assertEquals(20, perColumn.getRowCount());
        assertEquals(List.of("series", "mean", "highest"), perColumn.getColumns());
        assertEquals(bestName, perColumn.getRows().get(0)[0]);
        assertEquals(best, ((Number) perColumn.getRows().get(0)[1]).doubleValue(), 1e-9);
        assertEquals(1500, guard.run(find("wide", "Columns whose name")).getRowCount());
    }

    @Test
    public void aTextFamilyIsShownAndTurnedIntoRows() {
        db.execute("CREATE TABLE tags (id BIGINT, tag_1 VARCHAR, tag_2 VARCHAR, tag_3 VARCHAR)");
        List<String> sql = sql("tags");
        assertTrue(sql.contains("SELECT COLUMNS('^tag_[0-9]+$') FROM tags LIMIT 10"));
        assertTrue(sql.contains("SELECT * FROM (UNPIVOT tags ON COLUMNS('^tag_[0-9]+$') INTO NAME series VALUE value)"
                + " LIMIT 20"));
        assertTrue(sql.stream().noneMatch(s -> s.contains("list_sum") || s.contains("greatest")));
        allRunEmptyAndFull("tags", "INSERT INTO tags VALUES (1, 'a', 'b', 'c'), (2, 'd', NULL, 'f')");
    }

    @Test
    public void aFamilyOfNumbersOfDifferentTypesIsNotTurnedIntoRows() {
        db.execute("CREATE TABLE mixed_ints (a1 INTEGER, a2 BIGINT, a3 SMALLINT)");
        List<String> sql = sql("mixed_ints");
        assertTrue(sql.contains("SELECT avg(COLUMNS('^a[0-9]+$')) FROM mixed_ints"));
        assertTrue(sql.stream().noneMatch(s -> s.contains("UNPIVOT")));
        allRunEmptyAndFull("mixed_ints", "INSERT INTO mixed_ints VALUES (1, 2, 3)");
    }

    @Test
    public void numberedColumnsOfMixedKindsAreNoFamily() {
        db.execute("CREATE TABLE mixed (x1 INTEGER, x2 VARCHAR, x3 INTEGER)");
        assertTrue(sql("mixed").stream().noneMatch(s -> s.contains("COLUMNS(")));
        allRunEmptyAndFull("mixed", "INSERT INTO mixed VALUES (1, 'a', 3)");
    }

    @Test
    public void aFamilyWhosePatternWouldCatchAnotherColumnIsLeftOut() {
        // A prefix "p.q_" must not catch "pxq_1": the dot is matched as a dot.
        db.execute("CREATE TABLE dotted (\"p.q_1\" DOUBLE, \"p.q_2\" DOUBLE, \"p.q_3\" DOUBLE, pxq_1 DOUBLE)");
        List<String> sql = sql("dotted");
        assertTrue(sql.toString(), sql.contains("SELECT avg(COLUMNS('^p\\.q_[0-9]+$')) FROM dotted"));
        allRunEmptyAndFull("dotted", "INSERT INTO dotted VALUES (1, 2, 3, 4)");
        assertEquals(3, guard.run("SELECT avg(COLUMNS('^p\\.q_[0-9]+$')) FROM dotted").getColumns().size());
    }

    @Test
    public void aQuoteInAFamilysNameIsWrittenTwice() {
        db.execute("CREATE TABLE quoted (\"it's_1\" DOUBLE, \"it's_2\" DOUBLE, \"it's_3\" DOUBLE)");
        List<String> sql = sql("quoted");
        assertTrue(sql.toString(), sql.contains("SELECT avg(COLUMNS('^it''s_[0-9]+$')) FROM quoted"));
        allRunEmptyAndFull("quoted", "INSERT INTO quoted VALUES (1, 2, 3)");
        assertEquals(6.0, ((Number) guard.run(find("quoted", "The total")).getRows().get(0)[0]).doubleValue(), 0);
    }

    @Test
    public void namesThatNeedQuotesGetThem() {
        db.execute("CREATE TABLE \"Odd Names\" (\"order\" BIGINT, \"Group Name\" VARCHAR, \"when\" DATE)");
        List<String> sql = sql("Odd Names");
        assertEquals("SELECT count(*) AS rows FROM \"Odd Names\"", sql.get(0));
        assertTrue(sql.contains("SUMMARIZE \"Odd Names\""));
        assertTrue(sql.toString(), sql.contains("SELECT date_trunc('month', \"when\") AS month, count(*) AS rows,"
                + " avg(\"order\") AS mean_order FROM \"Odd Names\" WHERE \"when\" IS NOT NULL GROUP BY 1 ORDER BY 1"));
        allRunEmptyAndFull("Odd Names", "INSERT INTO \"Odd Names\" VALUES (1, 'a', DATE '2026-01-05'),"
                + " (2, 'b', DATE '2026-02-05')");
    }

    @Test
    public void aWideTableWithoutAFamilyLooksForTheNameOfAColumnNotShown() {
        StringBuilder ddl = new StringBuilder("CREATE TABLE many (");
        for (int i = 0; i < 30; i++) {
            ddl.append(i == 0 ? "" : ", ").append("col").append((char) ('a' + i % 26)).append(i < 26 ? "" : "z")
                    .append(" INTEGER");
        }
        db.execute(ddl.append(")").toString());
        assertTrue(sql("many").contains("SELECT column_name, column_type FROM (DESCRIBE many) WHERE column_name"
                + " ILIKE '%colk%'"));
        allRun("many");
        assertEquals(1, guard.run(find("many", "Columns whose name")).getRowCount());
    }

    @Test
    public void aJoinAndTheOtherTablesFirstRowsForEachTableSharingAColumn() {
        db.execute("CREATE TABLE readings (id BIGINT, device BIGINT, value DOUBLE)");
        db.execute("CREATE TABLE device_names (device BIGINT, name VARCHAR)");
        db.execute("CREATE TABLE codes (id VARCHAR, label VARCHAR)");   // same name, other kind: no join
        List<String> sql = sql("readings");
        int join = sql.indexOf("SELECT * FROM readings JOIN device_names USING (device) LIMIT 10");
        assertTrue(sql.toString(), join > 0);
        assertEquals("SELECT * FROM device_names LIMIT 10", sql.get(join + 1));
        assertTrue(sql.toString(), sql.stream().noneMatch(s -> s.contains("codes")));
        allRunEmptyAndFull("readings", "INSERT INTO readings VALUES (1, 7, 10), (2, 8, 20)",
                "INSERT INTO device_names VALUES (7, 'ann')");
        assertEquals(1, guard.run(sql.get(join)).getRowCount());
    }

    @Test
    public void withoutCountsTheGroupColumnIsChosenByItsName() {
        db.execute("CREATE TABLE events (name VARCHAR, message VARCHAR, status VARCHAR, host VARCHAR)");
        assertTrue(sql("events").toString(), sql("events").contains(
                "SELECT host, count(*) AS rows FROM events GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
        db.execute("CREATE TABLE notes (message VARCHAR, body VARCHAR)");
        assertTrue("free text is no group", sql("notes").stream().noneMatch(s -> s.contains("GROUP BY")));
        db.execute("CREATE TABLE pairs (a VARCHAR, b VARCHAR)");
        assertTrue(sql("pairs").contains("SELECT a, count(*) AS rows FROM pairs GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
    }

    @Test
    public void withCountsTheGroupColumnIsOneWithFewValues() {
        db.execute("CREATE TABLE people_seen (visitor VARCHAR, colour VARCHAR, host VARCHAR, weight DOUBLE)");
        db.execute("INSERT INTO people_seen SELECT 'v' || x, CASE WHEN x % 2 = 0 THEN 'red' ELSE 'blue' END,"
                + " 'h' || x, x FROM range(5000) r(x)");
        String byName = "SELECT host, count(*) AS rows, avg(weight) AS mean_weight, max(weight) AS highest_weight FROM people_seen"
                + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20";
        assertTrue("told nothing, host goes by its name", sql("people_seen").contains(byName));

        Profile profile = catalog.profile("people_seen");
        assertEquals(2L, (long) profile.distinctValues().get("colour"));
        List<String> sql = catalog.examples("people_seen", profile.distinctValues()).stream()
                .map(QueryExamples.Example::sql).collect(Collectors.toList());
        assertTrue(sql.toString(), sql.contains("SELECT colour, count(*) AS rows, avg(weight) AS mean_weight, max(weight) AS highest_weight"
                + " FROM people_seen GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
        assertFalse("host has 5,000 values", sql.contains(byName));

        Map<String, Long> allMany = Map.of("VISITOR", 5000L, "colour", 4000L, "host", 5000L);
        assertTrue("none has few values, so no group", catalog.examples("people_seen", allMany).stream()
                .noneMatch(e -> e.sql().contains("GROUP BY 1 ORDER BY 2")));
        for (QueryExamples.Example example : catalog.examples("people_seen", profile.distinctValues())) {
            assertNotNull(guard.run(example.sql()));
        }
    }

    @Test
    public void aTableWithNothingToGroupOrSearchStillGetsACountRowsAndASummary() {
        db.execute("CREATE TABLE flags (ok BOOLEAN)");
        assertEquals(List.of("SELECT count(*) AS rows FROM flags", "SELECT * FROM flags LIMIT 10", "SUMMARIZE flags"),
                sql("flags"));
        allRunEmptyAndFull("flags", "INSERT INTO flags VALUES (true), (false)");
    }

    @Test
    public void aViewOverJavaObjectsOrAFileGetsThemToo() {
        db.execute("CREATE VIEW numbers AS SELECT x AS n, 'v' || x AS label FROM range(30) r(x)");
        allRun("numbers");
        assertTrue(sql("numbers").toString(), sql("numbers").contains("SELECT label, count(*) AS rows FROM numbers"
                + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
        assertTrue("n counts rows, so no percentiles", sql("numbers").stream().noneMatch(s -> s.contains("quantile")));
    }

    @Test
    public void everyExampleRunsOnEveryKindOfColumnEmptyAndFull() {
        db.execute("CREATE TABLE kinds (i TINYINT, u UBIGINT, h HUGEINT, d DECIMAL(10, 2), f FLOAT, day DATE,"
                + " \"at\" TIMESTAMP WITH TIME ZONE, ns TIMESTAMP_NS, txt VARCHAR, b BLOB, l INTEGER[], s STRUCT(a INT))");
        allRunEmptyAndFull("kinds", "INSERT INTO kinds VALUES (1, 2, 3, 4.5, 6.5, DATE '2026-01-02',"
                + " TIMESTAMPTZ '2026-01-02 03:04:05+00', TIMESTAMP_NS '2026-01-02 03:04:05', 'x', 'y'::BLOB, [1],"
                + " {'a': 1})");
        assertTrue(Arrays.toString(sql("kinds").toArray()), sql("kinds").stream().anyMatch(s -> s.contains("quantile")));
    }

    @Test
    public void rowsWithoutATimeOrALevelAreNoGroupOfTheirOwn() {
        db.execute(LOG);
        db.execute("INSERT INTO log VALUES (1, TIMESTAMP '2026-01-01 10:00:00', 'ERROR', 'w', 'app', 'boom'),"
                + " (2, NULL, NULL, 'w', 'app', '    at app.Main.run(Main.java:12)'),"
                + " (3, TIMESTAMP '2026-01-01 11:30:00', 'INFO', 'w', 'app', 'ok')");
        GuardedResult hours = guard.run(find("log", "Rows each hour"));
        assertEquals("10:00 and 11:00, no hour for the stack trace's line", 2, hours.getRowCount());
        GuardedResult levels = guard.run(find("log", "Lines per level"));
        assertEquals(2, levels.getRowCount());
        assertTrue(levels.getRows().stream().noneMatch(r -> r[0] == null));
        assertTrue("line counts rows: no range of it", catalog.examples("log").stream()
                .noneMatch(e -> e.title().equals("The range of line")));
    }
}
