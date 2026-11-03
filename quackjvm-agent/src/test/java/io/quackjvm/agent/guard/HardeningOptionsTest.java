package io.quackjvm.agent.guard;

import io.quackjvm.core.Quack;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Locked down, but with folders of its own, a temp folder and limits. */
public class HardeningOptionsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File logs;
    private File other;
    private File neighbour;
    private File temp;

    @Before
    public void files() throws Exception {
        logs = folder.newFolder("logs");
        other = folder.newFolder("other");
        // Starts with the allowed folder's name, so a check by prefix alone would let it in.
        neighbour = folder.newFolder("logs2");
        temp = folder.newFolder("temp");
        for (File dir : new File[] {logs, other, neighbour}) {
            Files.writeString(new File(dir, "a.csv").toPath(), "x,y\n1,2\n3,4\n");
        }
        new File(logs, "below").mkdirs();
        Files.writeString(new File(logs, "below/b.csv").toPath(), "x\n1\n");
    }

    private Hardening.Options options() {
        return Hardening.options().allowedFolders(logs.getPath()).tempFolder(temp.getPath())
                .memoryLimit("200MB").threads(2);
    }

    private static String csv(File dir, String name) {
        return "read_csv('" + new File(dir, name).getPath() + "')";
    }

    private static void refused(Quack db, String sql) {
        try {
            db.execute(sql);
            fail("ran: " + sql);
        }
        catch (RuntimeException expected) {
            // refused
        }
    }

    private static void allowsTheFoldersAndNothingElse(Quack db, File logs, File other, File neighbour) {
        assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM " + csv(logs, "a.csv")).scalar(Long.class));
        assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM " + csv(logs, "below/b.csv")).scalar(Long.class));

        refused(db, "CREATE TABLE t AS SELECT * FROM " + csv(other, "a.csv"));
        refused(db, "CREATE TABLE t AS SELECT * FROM " + csv(neighbour, "a.csv"));
        refused(db, "CREATE TABLE t AS SELECT * FROM read_csv('" + logs.getPath() + "/../other/a.csv')");
        refused(db, "CREATE TABLE t AS SELECT * FROM read_csv('https://example.com/a.csv')");
        refused(db, "ATTACH '" + new File(other, "x.duckdb").getPath() + "' AS x");
        refused(db, "INSTALL httpfs");
        refused(db, "SET threads = 3");
        refused(db, "SET enable_external_access = true");
    }

    @Test
    public void openedThisWayItUsesItsFoldersAndReachesNothingElse() {
        try (Quack db = Quack.builder(null).settings(options().properties()).open()) {
            allowsTheFoldersAndNothingElse(db, logs, other, neighbour);
            assertEquals("2", db.query("SELECT current_setting('threads')::VARCHAR").scalar(String.class));
            assertTrue(Hardening.isLocked(db.sharedConnection()));
            assertTrue(Hardening.isHardened(db.sharedConnection()));
        }
    }

    @Test
    public void appliedToADatabaseAlreadyOpenItEndsUpTheSame() {
        try (Quack db = Quack.inMemory()) {
            Hardening.apply(db, options());
            allowsTheFoldersAndNothingElse(db, logs, other, neighbour);
        }
    }

    @Test
    public void anAllowedFolderCanBeWrittenAsWellAsRead() {
        // DuckDB has no read-only form of an allowed folder. GuardedQuery refuses both statements.
        try (Quack db = Quack.builder(null).settings(options().properties()).open()) {
            db.execute("COPY (SELECT 1 AS x) TO '" + new File(logs, "out.csv").getPath() + "'");
            assertTrue(new File(logs, "out.csv").exists());
            db.execute("ATTACH '" + new File(logs, "x.duckdb").getPath() + "' AS x");
        }
    }

    @Test
    public void whatDoesNotFitInMemorySpillsIntoTheTempFolder() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong();
        Thread watcher = new Thread(() -> {
            while (running.get()) {
                long bytes = 0;
                File[] files = temp.listFiles();
                for (File file : files == null ? new File[0] : files) {
                    bytes += file.length();
                }
                peak.accumulateAndGet(bytes, Math::max);
                try {
                    Thread.sleep(20);
                }
                catch (InterruptedException e) {
                    return;
                }
            }
        });
        watcher.start();
        try (Quack db = Quack.builder(null).settings(options().properties()).open()) {
            // Sorting 8 million rows of text under a 200 MB limit has to spill.
            assertEquals(Long.valueOf(8_000_000), db.query("SELECT count(*) FROM (SELECT range x, md5(range::VARCHAR) s"
                    + " FROM range(8000000) ORDER BY s)").scalar(Long.class));
        }
        finally {
            running.set(false);
            watcher.join();
        }
        assertTrue("spilled " + peak.get() / 1024 / 1024 + " MB", peak.get() > 50L * 1024 * 1024);
        // ...without the temp folder being listed: DuckDB spills there either way.
        assertFalse(options().getAllowedFolders().contains(temp.getPath()));
    }

    /**
     * Measured on 2.0.0.0: DuckDB lets SQL read and write in its temp folder, listed or not. If a
     * later DuckDB stops doing so, this fails and the docs can say less.
     */
    @Test
    public void sqlCanUseTheTempFolderAsIfItWereAnAllowedOne() throws Exception {
        try (Quack db = Quack.builder(null).settings(options().properties()).open()) {
            Files.writeString(new File(temp, "a.csv").toPath(), "x\n1\n");
            assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM " + csv(temp, "a.csv")).scalar(Long.class));
            db.execute("COPY (SELECT 1 AS x) TO '" + new File(temp, "out.csv").getPath() + "'");
            assertTrue(new File(temp, "out.csv").exists());
        }
        // Without one, the same folder is out of reach.
        try (Quack db = Quack.builder(null).settings(Hardening.options().allowedFolders(logs.getPath()).properties())
                .open()) {
            refused(db, "CREATE TABLE t AS SELECT * FROM " + csv(temp, "a.csv"));
        }
    }

    /** So that it cannot be pointed at a folder of other files, which SQL could then read. */
    @Test
    public void aTempFolderMustHoldNothingButDuckDbsOwnFiles() throws Exception {
        Files.writeString(new File(temp, "notes.txt").toPath(), "not DuckDB's");
        try {
            options().properties();
            fail("took a temp folder with other files in it");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("notes.txt"));
        }
        try (Quack db = Quack.inMemory()) {
            Hardening.apply(db, options());
            fail("took a temp folder with other files in it");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("notes.txt"));
        }

        // What DuckDB leaves behind is fine, and so is a folder that is not there yet.
        new File(temp, "notes.txt").delete();
        Files.writeString(new File(temp, "duckdb_temp_4242_0_owner").toPath(), "");
        options().properties();
        Hardening.options().tempFolder(new File(folder.getRoot(), "not-yet").getPath()).properties();
    }

    @Test
    public void applyingItToADatabaseLockedAlreadyChangesNothing() {
        try (Quack db = Quack.builder(null).settings(options().properties()).open()) {
            Hardening.apply(db, options());
            Hardening.apply(db, Hardening.options().threads(5));
            assertEquals("2", db.query("SELECT current_setting('threads')::VARCHAR").scalar(String.class));
        }
    }

    /**
     * One JVM opening a file twice shares one database. With the same options that is fine; with
     * different ones the driver refuses - measured on 2.0.0.0 - so the second never meets a lock it
     * did not expect.
     */
    @Test
    public void aSecondQuackOnTheSameFileInOneJvmSharesTheLockedDatabase() {
        String path = new File(folder.getRoot(), "shared.duckdb").getPath();
        try (Quack first = Quack.builder(path).settings(options().properties()).open();
             Quack second = Quack.builder(path).settings(options().properties()).open()) {
            Hardening.apply(second, options());
            assertTrue(Hardening.isLocked(second.sharedConnection()));
            allowsTheFoldersAndNothingElse(second, logs, other, neighbour);

            try (Quack unlocked = Quack.open(path)) {
                fail("opened the locked database without its settings");
            }
            catch (IllegalStateException expected) {
                assertTrue(String.valueOf(expected.getCause()), String.valueOf(expected.getCause().getMessage())
                        .contains("different configuration"));
            }
        }
    }

    @Test
    public void foldersCannotBeLetInOnceExternalAccessIsGone() {
        try (Quack db = Quack.inMemory()) {
            Hardening.apply(db);
            try {
                Hardening.apply(db, options());
                fail("let folders in after external access was taken away");
            }
            catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("only before"));
            }
        }
    }
}
