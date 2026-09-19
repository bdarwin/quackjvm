package io.quackjvm.dashboard;

import io.quackjvm.core.metrics.QuackMetrics;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecorderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void whatTheDashboardSamplesCanBeQueriedWithDuckDB() throws Exception {
        Path directory = folder.getRoot().toPath().resolve("metrics");
        QuackMetrics metrics = new QuackMetrics();
        try (QuackDashboard dashboard = QuackDashboard.builder(metrics).port(0).recordTo(directory).start()) {
            assertEquals(directory.toAbsolutePath().normalize(), dashboard.recordingDirectory());
            for (int second = 0; second < Sampler.SHORT_WINDOW + 1; second++) {
                for (int i = 0; i < 50; i++) {
                    metrics.timer(QuackMetrics.REQUEST_WRITE, "orders").record(1_000_000);
                    metrics.timer(QuackMetrics.WRITE_LOCK_WAIT, "orders").record(9_000_000);
                    metrics.statementTimer("DELETE FROM orders WHERE id IN (?, ?)").record(500_000);
                }
                dashboard.sampleNow();
            }
        }

        try (Connection duckdb = DriverManager.getConnection("jdbc:duckdb:");
             Statement sql = duckdb.createStatement()) {
            String files = directory.toString().replace("'", "''");
            try (ResultSet row = sql.executeQuery("SELECT count(*), max(lockWaitShare), typeof(any_value(ts))"
                    + " FROM '" + files + "/metrics-*.jsonl'")) {
                assertTrue(row.next());
                assertTrue("one line a second", row.getLong(1) >= Sampler.SHORT_WINDOW);
                assertEquals(0.9, row.getDouble(2), 0.01);
                assertTrue("ts should read as a time: " + row.getString(3), row.getString(3).startsWith("TIMESTAMP"));
            }
            try (ResultSet row = sql.executeQuery("SELECT shape, count FROM '" + files + "/statements-*.jsonl'")) {
                assertTrue(row.next());
                assertEquals("DELETE FROM orders WHERE id IN (?, ...)", row.getString(1));
            }
            try (ResultSet row = sql.executeQuery("SELECT name, waitShare FROM '" + files + "/collections-*.jsonl'")) {
                assertTrue(row.next());
                assertEquals("orders", row.getString(1));
            }
            try (ResultSet row = sql.executeQuery("SELECT cause FROM '" + files + "/findings-*.jsonl'")) {
                assertTrue(row.next());
                assertEquals("WRITE_LOCK", row.getString(1));
            }
        }
    }

    @Test
    public void oldFilesAreDeletedAndNothingElseIs() throws Exception {
        Path directory = folder.getRoot().toPath();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Path expired = Files.writeString(directory.resolve("metrics-" + today.minusDays(8) + ".jsonl"), "{}\n");
        Path kept = Files.writeString(directory.resolve("findings-" + today.minusDays(6) + ".jsonl"), "{}\n");
        Path notOurs = Files.writeString(directory.resolve("notes-" + today.minusDays(30) + ".jsonl"), "{}\n");
        Path alsoNotOurs = Files.writeString(directory.resolve("metrics-backup.jsonl"), "{}\n");

        try (Recorder recorder = new Recorder(directory, Duration.ofDays(7))) {
            recorder.write(Recorder.Kind.METRICS, java.time.Instant.now(), "{\"ts\":\"now\"}");
            recorder.flush();
        }

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(kept));
        assertTrue(Files.exists(notOurs));
        assertTrue(Files.exists(alsoNotOurs));
        assertTrue(Files.exists(directory.resolve("metrics-" + today + ".jsonl")));
    }

    @Test
    public void recordingCanBeTurnedOff() {
        try (QuackDashboard dashboard = QuackDashboard.builder(new QuackMetrics()).port(0).recordTo(null).start()) {
            assertNull(dashboard.recordingDirectory());
            assertTrue(dashboard.state().contains("\"recording\":{\"directory\":null"));
        }
    }

    @Test
    public void aDirectoryThatCannotBeWrittenLeavesThePageWorking() throws Exception {
        Path aFile = Files.writeString(folder.getRoot().toPath().resolve("in-the-way"), "x");
        try (QuackDashboard dashboard = QuackDashboard.builder(new QuackMetrics()).port(0).recordTo(aFile).start()) {
            assertNull(dashboard.recordingDirectory());
            String state = dashboard.state();
            assertTrue(state, state.contains("Could not record to"));
        }
    }
}
