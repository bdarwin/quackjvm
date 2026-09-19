package io.quackjvm.core.metrics;

import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class QueryProfileTest {

    /** A heavy statement, with a value written into it and another bound to it. */
    private static final String HEAVY = "SELECT g, count(DISTINCT i % 5000), quantile_cont(i, 0.9) FROM t"
            + " WHERE label <> 'EMEA-SECRET' AND i > ? GROUP BY g";

    private DuckDBConnection root;
    private QuackMetrics metrics;
    private Connection metered;

    @Before
    public void open() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("jdbc_stream_results", "true");
        root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", properties);
        try (Statement s = root.createStatement()) {
            s.execute("CREATE TABLE t AS SELECT i, i % 7 AS g, 'label' || (i % 3) AS label FROM range(2000000) r(i)");
        }
        metrics = new QuackMetrics();
        metered = metrics.meter(root.duplicate());
    }

    @After
    public void close() throws Exception {
        metered.close();
        root.close();
    }

    private void runHeavy(int times) throws Exception {
        for (int n = 0; n < times; n++) {
            try (PreparedStatement statement = metered.prepareStatement(HEAVY)) {
                statement.setInt(1, 777_123);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        // Read to the end, as the profile is only complete then.
                    }
                }
            }
        }
    }

    private QueryProfile heavyProfile() {
        return metrics.profile(QuackMetrics.shapeOf(HEAVY));
    }

    @Test
    public void aHeavyStatementIsProfiledFromOneOfItsOwnExecutions() throws Exception {
        runHeavy(8);
        QueryProfile profile = heavyProfile();
        assertNotNull("expected a profile among " + metrics.profiles().keySet(), profile);
        // Fewer than the table's 2,000,000: the filter i > ? is pushed into the scan, and DuckDB
        // skips the blocks of rows it rules out - which a profile is exactly the place to see.
        assertTrue("rows scanned " + profile.getRowsScanned(),
                profile.getRowsScanned() > 1_000_000 && profile.getRowsScanned() < 2_000_000);
        assertEquals(7, profile.getRowsReturned(), 0);
        assertTrue(profile.getLatencyMillis() > 0);
        assertTrue(profile.getCpuMillis() > 0);
        assertTrue(profile.getOperators().stream().anyMatch(o -> o.name().contains("GROUP_BY")));
        assertTrue(profile.getOperators().stream().anyMatch(o -> o.rowsScanned() == profile.getRowsScanned()));
    }

    @Test
    public void noValueReachesTheProfile() throws Exception {
        runHeavy(8);
        String text = heavyProfile().toString();
        assertFalse(text, text.contains("EMEA-SECRET"));
        assertFalse(text, text.contains("777123"));
        assertFalse(text, text.contains("777_123"));
    }

    @Test
    public void aStatementIsProfiledAtMostOncePerInterval() throws Exception {
        runHeavy(8);
        QueryProfile first = heavyProfile();
        runHeavy(10);
        assertSame(first, heavyProfile());
    }

    /**
     * DuckDB's own "latency" in a profile measures from when the profiler was last switched, not
     * from when the statement began: a 2 ms statement sampled 300 ms after the previous sample
     * reported 300 ms. The profile's time must be the statement's own.
     */
    @Test
    public void aProfilesTimeIsTheStatementsOwnNotTheGapSinceTheLastSample() throws Exception {
        metrics.setProfileInterval(java.time.Duration.ofMillis(200));
        runHeavy(8);
        QueryProfile first = heavyProfile();
        Thread.sleep(1_500);
        runHeavy(1);
        QueryProfile second = heavyProfile();
        assertTrue("expected a second profile", second != first);
        long start = System.nanoTime();
        runHeavy(1);
        double oneCall = (System.nanoTime() - start) / 1e6;
        assertTrue("profile says " + second.getLatencyMillis() + " ms; one call takes about " + oneCall + " ms",
                second.getLatencyMillis() < Math.max(200, 5 * oneCall));
    }

    @Test
    public void fastStatementsAreNeverProfiled() throws Exception {
        for (int n = 0; n < 50; n++) {
            try (PreparedStatement statement = metered.prepareStatement("SELECT ? + 1")) {
                statement.setInt(1, n);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                }
            }
        }
        assertTrue(metrics.profiles().toString(), metrics.profiles().isEmpty());
    }

    @Test
    public void nothingIsProfiledWhenProfilingIsOff() throws Exception {
        metrics.setProfiling(false);
        runHeavy(8);
        assertTrue(metrics.profiles().isEmpty());
        assertProfilerOff();
    }

    /**
     * Running any statement on a DuckDB connection closes a result set still being read on it, so
     * the profiler must be switched off only just before the application's own next statement -
     * never after the profiled query, when the application may already be reading another.
     */
    @Test
    public void aResultOpenedAfterAProfiledQueryIsNotCutOff() throws Exception {
        runHeavy(8);
        assertNotNull(heavyProfile());
        long count = 0;
        try (PreparedStatement statement = metered.prepareStatement("SELECT i FROM t");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                count++;
            }
        }
        assertEquals(2_000_000, count);
        assertProfilerOff();
    }

    @Test
    public void valuesAreScrubbedFromProfileText() {
        assertEquals("(i > ? AND label != ?)", QueryProfile.scrub("(i > 777123 AND label != 'EMEA')"));
        assertEquals("count(DISTINCT #1), quantile_cont(#2)", QueryProfile.scrub("count(DISTINCT #1), quantile_cont(#2)"));
        assertEquals("price >= ?", QueryProfile.scrub("price >= -12.5"));
        assertEquals("col2 = ?", QueryProfile.scrub("col2 = 3"));
    }

    @Test
    public void profileJsonIsRead() {
        Object parsed = ProfileJson.parse("{\"a\": [1, 2.5e3, \"x\\\"y\\u0041\"], \"b\": {\"c\": null, \"d\": true}}");
        Map<?, ?> root = (Map<?, ?>) parsed;
        assertEquals(List.of(1.0, 2500.0, "x\"yA"), root.get("a"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) root.get("b")).get("d"));
        assertNull(((Map<?, ?>) root.get("b")).get("c"));
    }

    @Test
    public void aProfileOfAnotherStatementIsRejected() {
        String json = "{\"query_name\": \"SELECT 2\", \"latency\": 0.01, \"children\": []}";
        assertNull(QueryProfile.fromDuckDB(json, "SELECT 1", "SELECT ?", java.time.Instant.now(), 1));
        assertNull(QueryProfile.fromDuckDB("{\"query_name\": \"\", \"latency\": 0.0}", "SELECT 1", "SELECT ?",
                java.time.Instant.now(), 1));
    }

    private void assertProfilerOff() throws Exception {
        try (Statement statement = metered.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_setting('enable_profiling')")) {
            row.next();
            String setting = row.getString(1);
            assertTrue("profiler left on: " + setting, setting == null || setting.isEmpty());
        }
    }
}
