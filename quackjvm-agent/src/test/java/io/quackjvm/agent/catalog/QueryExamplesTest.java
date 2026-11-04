package io.quackjvm.agent.catalog;

import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.core.Quack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Queries written from a table's columns - issue #14's optional part. Every one has to run. */
public class QueryExamplesTest {

    private Quack db;
    private Catalog catalog;
    private GuardedQuery guard;

    @Before
    public void open() {
        db = Quack.inMemory();
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

    /** Runs every example; a broken one fails the test with its SQL. */
    private void allRun(String table) {
        for (QueryExamples.Example example : catalog.examples(table)) {
            GuardedResult result = guard.run(example.sql());
            assertTrue(example.sql(), result.getColumns().size() > 0);
            assertFalse(example.title(), example.title().isBlank());
        }
    }

    @Test
    public void aLogGetsACountRowsARangeCountsByLevelRowsEachHourAndASearch() {
        db.execute("CREATE TABLE log (line BIGINT, time TIMESTAMP, level VARCHAR, thread VARCHAR, message VARCHAR)");
        db.execute("INSERT INTO log SELECT x, TIMESTAMP '2026-01-01' + INTERVAL (x) MINUTE,"
                + " CASE WHEN x % 10 = 0 THEN 'WARN' ELSE 'INFO' END, 'main', 'step ' || x || CASE WHEN x % 50 = 0"
                + " THEN ' error' ELSE '' END FROM range(500) r(x)");
        assertEquals(List.of(
                "SELECT count(*) AS rows FROM log",
                "SELECT * FROM log LIMIT 10",
                "SELECT min(line) AS lowest, max(line) AS highest, avg(line) AS mean FROM log",
                "SELECT level, count(*) AS rows, avg(line) AS mean_line FROM log GROUP BY 1 ORDER BY 2 DESC LIMIT 20",
                "SELECT date_trunc('hour', time) AS hour, count(*) AS rows FROM log GROUP BY 1 ORDER BY 1",
                "SELECT * FROM log WHERE message ILIKE '%error%' LIMIT 50"), sql("log"));
        allRun("log");
        assertEquals(10, guard.run(sql("log").get(5)).getRowCount());
    }

    @Test
    public void aFamilyOfNumberedColumnsIsQueriedAllAtOnce() {
        StringBuilder ddl = new StringBuilder("CREATE TABLE wide (host VARCHAR");
        for (int i = 1; i <= 1500; i++) {
            ddl.append(", cpu_").append(String.format("%04d", i)).append(" DOUBLE");
        }
        ddl.append(", mem_1 BIGINT, mem_2 BIGINT, mem_3 BIGINT, disk2 BIGINT)");
        db.execute(ddl.toString());
        db.execute("INSERT INTO wide (host, cpu_0001, cpu_1500, disk2) VALUES ('a', 1, 2, 3), ('b', 4, 5, 6)");

        List<String> sql = sql("wide");
        assertTrue(sql.toString(), sql.contains("SELECT avg(COLUMNS('^cpu_[0-9]+$')) FROM wide"));
        assertTrue(sql.toString(), sql.contains("SELECT avg(COLUMNS('^mem_[0-9]+$')) FROM wide"));
        assertTrue("disk2 is alone, so it is a column of its own",
                sql.contains("SELECT min(disk2) AS lowest, max(disk2) AS highest, avg(disk2) AS mean FROM wide"));
        assertTrue(sql.get(1), sql.get(1).startsWith("SELECT host, cpu_0001, cpu_0002,"));
        assertTrue(sql.get(1), sql.get(1).endsWith("cpu_0009 FROM wide LIMIT 10"));
        allRun("wide");
        assertEquals(1500, guard.run("SELECT avg(COLUMNS('^cpu_[0-9]+$')) FROM wide").getColumns().size());
    }

    @Test
    public void numberedColumnsOfMixedKindsAreNoFamily() {
        db.execute("CREATE TABLE mixed (x1 INTEGER, x2 VARCHAR, x3 INTEGER)");
        assertTrue(sql("mixed").stream().noneMatch(s -> s.contains("COLUMNS(")));
        allRun("mixed");
    }

    @Test
    public void aFamilyWhosePatternWouldCatchAnotherColumnIsLeftOut() {
        // ^v[0-9]+$ would catch v10 as well as v1..v3 - fine, they are all v<number>. But a prefix
        // "p.q_" must not catch "pxq_1": the dot is matched as a dot.
        db.execute("CREATE TABLE dotted (\"p.q_1\" DOUBLE, \"p.q_2\" DOUBLE, \"p.q_3\" DOUBLE, pxq_1 DOUBLE)");
        db.execute("INSERT INTO dotted VALUES (1, 2, 3, 4)");
        List<String> sql = sql("dotted");
        assertTrue(sql.toString(), sql.contains("SELECT avg(COLUMNS('^p\\.q_[0-9]+$')) FROM dotted"));
        allRun("dotted");
        assertEquals(3, guard.run("SELECT avg(COLUMNS('^p\\.q_[0-9]+$')) FROM dotted").getColumns().size());
    }

    @Test
    public void namesThatNeedQuotesGetThem() {
        db.execute("CREATE TABLE \"Odd Names\" (\"order\" BIGINT, \"Group Name\" VARCHAR, \"when\" DATE)");
        db.execute("INSERT INTO \"Odd Names\" VALUES (1, 'a', DATE '2026-01-05'), (2, 'b', DATE '2026-02-05')");
        List<String> sql = sql("Odd Names");
        assertEquals("SELECT count(*) AS rows FROM \"Odd Names\"", sql.get(0));
        assertTrue(sql.toString(), sql.contains("SELECT date_trunc('month', \"when\") AS month, count(*) AS rows"
                + " FROM \"Odd Names\" GROUP BY 1 ORDER BY 1"));
        allRun("Odd Names");
    }

    @Test
    public void aJoinForEachOtherTableWithAColumnOfTheSameNameAndKind() {
        db.execute("CREATE TABLE readings (id BIGINT, device BIGINT, value DOUBLE)");
        db.execute("CREATE TABLE device_names (device BIGINT, name VARCHAR)");
        db.execute("CREATE TABLE codes (id VARCHAR, label VARCHAR)");   // same name, other kind: no join
        db.execute("INSERT INTO readings VALUES (1, 7, 10), (2, 8, 20)");
        db.execute("INSERT INTO device_names VALUES (7, 'ann')");
        List<String> sql = sql("readings");
        assertTrue(sql.toString(), sql.contains("SELECT * FROM readings JOIN device_names USING (device) LIMIT 10"));
        assertTrue(sql.toString(), sql.stream().noneMatch(s -> s.contains("JOIN codes")));
        allRun("readings");
        assertEquals(1, guard.run("SELECT * FROM readings JOIN device_names USING (device) LIMIT 10").getRowCount());
    }

    @Test
    public void aTableWithNothingToGroupOrSearchStillGetsACountAndRows() {
        db.execute("CREATE TABLE flags (ok BOOLEAN)");
        assertEquals(List.of("SELECT count(*) AS rows FROM flags", "SELECT * FROM flags LIMIT 10"), sql("flags"));
        allRun("flags");
    }

    @Test
    public void aViewOverJavaObjectsOrAFileGetsThemToo() {
        db.execute("CREATE VIEW numbers AS SELECT x AS n, 'v' || x AS label FROM range(30) r(x)");
        allRun("numbers");
        assertTrue(sql("numbers").contains("SELECT label, count(*) AS rows, avg(n) AS mean_n FROM numbers"
                + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
    }
}
