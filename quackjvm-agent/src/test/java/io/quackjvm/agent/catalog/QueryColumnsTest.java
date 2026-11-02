package io.quackjvm.agent.catalog;

import io.quackjvm.core.Quack;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which columns a query reads, planned and never run - the cases from issue #7. */
public class QueryColumnsTest {

    private Quack db;
    private QueryColumns planner;
    private List<String> all;

    @Before
    public void open() {
        db = Quack.inMemory();
        StringBuilder ddl = new StringBuilder("CREATE TABLE t (line BIGINT, host VARCHAR, \"odd, \"\"name\" VARCHAR");
        all = new ArrayList<>(List.of("line", "host", "odd, \"name"));
        for (int i = 0; i < 20; i++) {
            ddl.append(", metric_").append(i).append(" DOUBLE");
            all.add("metric_" + i);
        }
        db.execute(ddl.append(")").toString());
        // Rows in the source, to show the planner never reads them: its copy is empty.
        db.execute("INSERT INTO t (line, host) SELECT range, 'h' || (range % 3) FROM range(1000)");
        db.execute("CREATE VIEW recent AS SELECT host, metric_1 FROM t WHERE line > 500");
        planner = QueryColumns.of(db);
    }

    @After
    public void close() {
        planner.close();
        db.close();
    }

    private Set<String> reads(String sql, Object... parameters) {
        QueryColumns.Read read = planner.read(sql, parameters);
        assertTrue(sql + ": " + read.reason(), read.isKnown());
        assertEquals(sql + " scanned " + read.columns().keySet(), Set.of("t"), read.columns().keySet());
        return read.columns().get("t");
    }

    private static Set<String> set(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }

    @Test
    public void filtersGroupsAndAggregatesAreAllRead() {
        assertEquals(set("metric_9", "host", "metric_7"),
                reads("SELECT host, sum(metric_7) FROM t WHERE metric_9 > 3 GROUP BY host"));
        assertEquals(set("metric_9"), reads("SELECT count(*) FROM t WHERE metric_9 > 3"));
        assertEquals(set("metric_9"), reads("SELECT count(*) FROM t WHERE metric_9 > ?", 3.0));
    }

    @Test
    public void countingRowsReadsTheTableButNoColumn() {
        assertEquals(set(), reads("SELECT count(*) FROM t"));
    }

    @Test
    public void starReadsEveryColumnAndExcludeAllButThose() {
        assertEquals(new LinkedHashSet<>(all), reads("SELECT * FROM t LIMIT 5"));
        Set<String> allButHost = new LinkedHashSet<>(all);
        allButHost.remove("host");
        assertEquals(allButHost, reads("SELECT * EXCLUDE (host) FROM t"));
    }

    @Test
    public void namesComeBackAsTheyAreNotAsTheyAreQuoted() {
        assertEquals(set("odd, \"name", "metric_5"), reads("SELECT \"odd, \"\"name\" FROM t ORDER BY metric_5 LIMIT 3"));
    }

    @Test
    public void theShapesAQueryCanTake() {
        assertEquals(set("host", "metric_1"),
                reads("WITH x AS (SELECT host h, metric_1 FROM t) SELECT h, avg(metric_1) FROM x GROUP BY h"));
        assertEquals(set("metric_1", "metric_10", "metric_11", "metric_12", "metric_13", "metric_14", "metric_15",
                "metric_16", "metric_17", "metric_18", "metric_19"), reads("SELECT COLUMNS('metric_1.?') FROM t"));
        assertEquals(set("metric_2", "metric_1", "metric_4"), reads("SELECT metric_1 FROM t WHERE metric_2 IN (SELECT metric_4 FROM t)"));
        assertEquals(set("host", "line", "metric_6"),
                reads("SELECT host FROM t QUALIFY row_number() OVER (PARTITION BY host ORDER BY line) = 1 AND metric_6 > 0"));
        // Two scans of one table, joined: what either reads.
        assertEquals(set("line", "host", "metric_3"),
                reads("SELECT a.host FROM t a JOIN t b ON a.line = b.line + 1 WHERE b.metric_3 = 1"));
    }

    @Test
    public void aViewIsSeenThroughToItsTable() {
        assertEquals(set("line", "host", "metric_1"), reads("SELECT host, metric_1 FROM recent"));
    }

    @Test
    public void whatScansNoTableReadsNothing() {
        QueryColumns.Read read = planner.read("DESCRIBE t");
        assertTrue(read.reason(), read.isKnown());
        assertEquals(Map.of(), read.columns());
    }

    @Test
    public void summarizeReadsEverything() {
        assertEquals(new LinkedHashSet<>(all), reads("SUMMARIZE t"));
    }

    @Test
    public void whatCannotBePlannedSaysSoRatherThanGuessing() {
        for (String sql : List.of(
                "PIVOT t ON host USING sum(metric_8)",              // rewritten into several statements
                "SELECT 1; SELECT host FROM t",                      // more than one
                "SELECT * FROM read_csv('/etc/hosts')",              // a file, out of the planner's reach
                "SELECT * FROM no_such_table",
                "")) {
            QueryColumns.Read read = planner.read(sql);
            assertFalse(sql + " was said to read " + read.columns(), read.isKnown());
            assertTrue(read.reason(), read.reason().contains("Assume every column"));
        }
    }

    @Test
    public void planningNeverTouchesTheSource() {
        planner.read("SELECT * FROM t");
        planner.read("DELETE FROM t");
        assertEquals(Long.valueOf(1000), db.query("SELECT count(*) FROM t").scalar(Long.class));
    }

    @Test
    public void aRefreshSeesTablesAddedSince() {
        db.execute("CREATE TABLE u (a INTEGER, b INTEGER)");
        assertFalse(planner.read("SELECT a FROM u").isKnown());
        planner.refresh();
        QueryColumns.Read read = planner.read("SELECT a FROM u");
        assertEquals(Map.of("u", Set.of("a")), read.columns());
    }

    @Test
    public void sixThousandColumns() {
        StringBuilder ddl = new StringBuilder("CREATE TABLE wide (line BIGINT");
        for (int i = 0; i < 6_000; i++) {
            ddl.append(", c").append(i).append(" DOUBLE");
        }
        db.execute(ddl.append(")").toString());

        long started = System.nanoTime();
        try (QueryColumns wide = QueryColumns.of(db)) {
            long made = System.nanoTime();
            QueryColumns.Read some = wide.read("SELECT line, avg(c17) FROM wide WHERE c4000 > 1 GROUP BY line");
            long readSome = System.nanoTime();
            QueryColumns.Read star = wide.read("SELECT * FROM wide LIMIT 5");
            long readStar = System.nanoTime();

            assertEquals(Set.of("line", "c17", "c4000"), some.columns().get("wide"));
            assertEquals(6_001, star.columns().get("wide").size());
            System.out.printf("QueryColumns, 6,000 columns: planner made in %d ms, a 3-column query read in %d ms,"
                            + " SELECT * in %d ms%n", (made - started) / 1_000_000, (readSome - made) / 1_000_000,
                    (readStar - readSome) / 1_000_000);
        }
    }
}
