package io.quackjvm.core;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Another process holding the file is told apart from every other failure to open it. */
public class DatabaseInUseTest {

    /** Opens the file, says so, and keeps it open until its standard input closes. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            try (Quack db = Quack.builder(args[0]).readOnly("read-only".equals(args[1])).open()) {
                if (!"read-only".equals(args[1])) {
                    db.execute("CREATE TABLE IF NOT EXISTS item AS SELECT 1 AS id");
                }
                System.out.println("holding");
                System.out.flush();
                while (System.in.read() >= 0) {
                    // until the test closes our input
                }
            }
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final List<Process> holders = new ArrayList<>();

    @After
    public void stopHolders() throws Exception {
        for (Process holder : holders) {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, TimeUnit.SECONDS)) {
                holder.destroyForcibly();
            }
        }
    }

    private String path() {
        return new File(folder.getRoot(), "held.duckdb").getPath();
    }

    /** Another JVM holding the file, as written or read-only, once it says it has it. */
    private Process hold(String path, String mode) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process holder = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), Holder.class.getName(), path, mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        holders.add(holder);
        BufferedReader output = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
        String line = output.readLine();
        assertEquals("the holder did not start", "holding", line);
        return holder;
    }

    private DatabaseInUse refused(Quack.Builder builder) {
        try (Quack opened = builder.open()) {
            fail("opened a file another process holds");
            return null;
        }
        catch (DatabaseInUse expected) {
            return expected;
        }
    }

    @Test
    public void aFileAnotherProcessWritesIsInUseAndSaysWho() throws Exception {
        String path = path();
        Process holder = hold(path, "write");

        DatabaseInUse inUse = refused(Quack.builder(path));
        assertEquals(path, inUse.getPath());
        assertEquals(holder.pid(), inUse.holderPid().getAsLong());
        assertTrue(inUse.getMessage(), inUse.getMessage().contains("PID " + holder.pid()));

        // Asking only to read does not help while the other process writes.
        refused(Quack.builder(path).readOnly(true));
    }

    @Test
    public void readersShareTheFileAndKeepAWriterOut() throws Exception {
        String path = path();
        try (Quack db = Quack.open(path)) {
            db.execute("CREATE TABLE item AS SELECT 1 AS id");
        }
        hold(path, "read-only");

        try (Quack reader = Quack.builder(path).readOnly(true).open()) {
            assertEquals(Long.valueOf(1), reader.query("SELECT count(*) FROM item").scalar(Long.class));
            try {
                reader.execute("INSERT INTO item VALUES (2)");
                fail("a read-only database wrote");
            }
            catch (RuntimeException expected) {
                // read-only
            }
        }
        refused(Quack.builder(path));
    }

    @Test
    public void otherFailuresToOpenAreNotInUse() throws Exception {
        File notADatabase = new File(folder.getRoot(), "notes.duckdb");
        Files.writeString(notADatabase.toPath(), "this is not a database file, only some words in one".repeat(200));
        File noSuchFolder = new File(folder.getRoot(), "missing/folder/db.duckdb");

        for (String path : List.of(notADatabase.getPath(), noSuchFolder.getPath())) {
            try (Quack opened = Quack.open(path)) {
                fail("opened " + path);
            }
            catch (DatabaseInUse wrong) {
                fail("reported as in use: " + path + ": " + wrong.getMessage());
            }
            catch (IllegalStateException expected) {
                assertFalse(expected instanceof DatabaseInUse);
            }
        }
    }

    @Test
    public void theSameFileTwiceInOneJvmIsNotInUse() {
        String path = path();
        try (Quack first = Quack.open(path); Quack second = Quack.open(path)) {
            first.execute("CREATE TABLE item AS SELECT 1 AS id");
            assertEquals(Long.valueOf(1), second.query("SELECT count(*) FROM item").scalar(Long.class));
        }
    }
}
