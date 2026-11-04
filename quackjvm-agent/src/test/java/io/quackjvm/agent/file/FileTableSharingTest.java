package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.QueryPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Caches shared between tables and processes, the room they take, and queries during an update - issue #13. */
public class FileTableSharingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path cache;
    private final List<FileTable> opened = new ArrayList<>();

    @Before
    public void files() throws Exception {
        cache = folder.newFolder("cache").toPath();
    }

    @After
    public void close() {
        for (FileTable table : opened) {
            table.close();
        }
    }

    private Path log(String name, int lines) throws Exception {
        Path log = folder.getRoot().toPath().resolve(name);
        FileTableTest.write(log, 0, lines, false);
        return log;
    }

    private FileTable open(Path log) {
        FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(cache).version("test-1").open();
        opened.add(table);
        return table;
    }

    private static long count(FileTable table) {
        return ((Number) table.query("SELECT count(*) FROM t", QueryPolicy.readOnly(), null).getRows().get(0)[0])
                .longValue();
    }

    @Test
    public void aSecondProcessGetsACopyNotDatabaseInUse() throws Exception {
        Path log = log("app.log", 2000);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FileTableChild.class.getName(), log.toString(),
                cache.toString()).redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = out.readLine();
            } while (line != null && !line.startsWith("ready "));
            assertTrue("the other process opened it", line != null);
            Path theirs = Path.of(line.substring("ready ".length()));
            assertTrue(FileTables.isInUse(theirs));

            FileTable mine = open(log);
            assertNotEquals(theirs, mine.cacheFile());
            assertTrue(mine.cacheFile().getFileName().toString(), mine.cacheFile().getFileName().toString().endsWith(".2.duckdb"));
            mine.update();
            assertEquals(2000, count(mine));

            // Neither trimming nor clearing takes away the one the other process has open.
            FileTables.clear(cache, Set.of());
            assertTrue(Files.exists(theirs));
            assertTrue(Files.exists(mine.cacheFile()));
        }
        finally {
            child.getOutputStream().close();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aProcessKilledMidUpdateLeavesWholeBatchesAndTheNextUpdateGoesOn() throws Exception {
        Path log = log("big.log", 600_000);
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FileTableChild.class.getName(), log.toString(),
                cache.toString(), "load").redirectErrorStream(true).start();
        BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        String line;
        int batches = 0;
        do {
            line = out.readLine();
            if (line != null && line.startsWith("stored ")) {
                batches++;
            }
        } while (line != null && batches < 3);
        child.destroyForcibly();
        assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        assertEquals("it was killed mid-update", 3, batches);

        FileTable table = open(log);
        long kept = table.rowsLoaded();
        long batch = 2 * FileTable.BLOCKS_PER_THREAD * 1000;
        assertTrue("kept " + kept, kept >= 3 * batch && kept < 600_000);
        assertEquals("whole batches", 0, kept % batch);
        assertEquals("the count stored is the rows there", kept, count(table));
        assertEquals(600_000 - kept, table.update().rowsAdded());
        assertEquals(600_000, count(table));
        assertEquals(600_000, ((Number) table.query("SELECT count(DISTINCT line) FROM t", QueryPolicy.readOnly(), null)
                .getRows().get(0)[0]).longValue());
    }

    @Test
    public void aSecondTableInTheSameJvmGetsACopyToo() throws Exception {
        Path log = log("app.log", 500);
        FileTable first = open(log);
        FileTable second = open(log);
        assertNotEquals(first.cacheFile(), second.cacheFile());
        first.update();
        second.update();
        assertEquals(500, count(first));
        assertEquals(500, count(second));
        first.close();
        opened.remove(first);
        FileTable third = open(log);
        assertEquals("the first copy is free again", first.cacheFile(), third.cacheFile());
        assertEquals("and still holds its rows", 500, third.rowsLoaded());
    }

    @Test
    public void overTheRoomLimitTheLongestUnusedCacheThatIsNotOpenGoes() throws Exception {
        List<Path> caches = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            FileTable table = open(log("log" + i + ".log", 3000));
            table.update();
            caches.add(table.cacheFile());
            table.close();
            opened.remove(table);
        }
        // log0 is the longest unused; log1 is kept open.
        for (int i = 0; i < 3; i++) {
            Files.setLastModifiedTime(caches.get(i).resolveSibling(caches.get(i).getFileName() + ".used"),
                    FileTime.fromMillis(1_000_000L * (i + 1)));
        }
        FileTable held = FileTables.builder(folder.getRoot().toPath().resolve("log1.log"), FileTableTest.COLUMNS,
                LineSource.of(folder.getRoot().toPath().resolve("log1.log")), FileTableTest.PARSER).cacheDir(cache)
                .version("test-1").open();
        opened.add(held);
        Files.setLastModifiedTime(caches.get(1).resolveSibling(caches.get(1).getFileName() + ".used"), FileTime.fromMillis(1));
        long all = FileTables.size(cache);
        long freed = FileTables.trim(cache, all - 1);
        assertTrue(freed > 0);
        assertTrue("held open, though longest unused", Files.exists(caches.get(1)));
        assertFalse("the longest unused of the rest went", Files.exists(caches.get(0)));
        assertTrue(Files.exists(caches.get(2)));
        assertEquals(all - freed, FileTables.size(cache));
    }

    @Test
    public void openingWithARoomLimitTrimsTheFolder() throws Exception {
        for (int i = 0; i < 3; i++) {
            FileTable table = open(log("log" + i + ".log", 3000));
            table.update();
            table.close();
            opened.remove(table);
        }
        long three = FileTables.size(cache);
        Path fourth = log("log3.log", 3000);
        FileTable table = FileTables.builder(fourth, FileTableTest.COLUMNS, LineSource.of(fourth), FileTableTest.PARSER)
                .cacheDir(cache).version("test-1").roomLimit(three / 2).open();
        opened.add(table);
        table.update();
        assertTrue(FileTables.size(cache) <= three / 2 || FileTables.caches(cache).size() == 1);
        assertTrue(Files.exists(table.cacheFile()));
    }

    @Test
    public void clearKeepsTheCachesOfFilesInUse() throws Exception {
        Path keep = log("keep.log", 100);
        Path drop = log("drop.log", 100);
        for (Path log : new Path[] {keep, drop}) {
            FileTable table = open(log);
            table.update();
            table.close();
            opened.remove(table);
        }
        assertEquals(2, FileTables.caches(cache).size());
        long freed = FileTables.clear(cache, Set.of(keep.toAbsolutePath().normalize().toString()));
        assertTrue(freed > 0);
        assertEquals(1, FileTables.caches(cache).size());
        assertEquals(keep.toAbsolutePath().normalize().toString(), FileTables.caches(cache).get(0).source());
        Files.writeString(cache.resolve("not-ours.txt"), "x");
        FileTables.clear(cache, Set.of());
        assertTrue("only its own files", Files.exists(cache.resolve("not-ours.txt")));
    }

    @Test
    public void aQuerySeesWholeBatchesWhileAnUpdateStoresThem() throws Exception {
        Path log = log("big.log", 200_000);
        FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(cache).threads(2).blockSize(1000).version("test-1").open();
        opened.add(table);
        AtomicBoolean loading = new AtomicBoolean(true);
        List<Long> seen = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            while (loading.get()) {
                seen.add(count(table));
            }
        });
        reader.start();
        table.update();
        loading.set(false);
        reader.join();
        long batch = 2 * FileTable.BLOCKS_PER_THREAD * 1000;
        for (long n : seen) {
            assertTrue("saw " + n, n % batch == 0 || n == 200_000);
        }
        assertTrue("queries ran during the update: " + seen.size(), seen.size() > 2);
        assertEquals(200_000, count(table));
    }
}
