package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.QueryPolicy;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #13's first acceptance test: a 300 MB log of 3 million lines, loaded once, then opened again
 * after a restart and queried without reading the file. {@code -Dfiletable.lines=} makes it smaller.
 */
public class FileTableLargeTest {

    private static final int LINES = Integer.getInteger("filetable.lines", 3_000_000);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void threeMillionLinesLoadOnceAndOpenAgainWithoutBeingRead() throws Exception {
        Path log = folder.getRoot().toPath().resolve("big.log");
        Path cache = folder.newFolder("cache").toPath();
        String padding = " - " + "x".repeat(48);
        try (BufferedWriter out = Files.newBufferedWriter(log)) {
            for (long i = 0; i < LINES; i++) {
                out.write(FileTableTest.line(i, "message " + i + padding));
            }
        }
        long bytes = Files.size(log);

        long started = System.nanoTime();
        try (FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(cache).threads(8).version("large-1").open()) {
            table.update();
            assertEquals(LINES, table.rowsLoaded());
        }
        long loadMs = (System.nanoTime() - started) / 1_000_000;

        started = System.nanoTime();
        long warns;
        long read;
        try (FileTable table = FileTables.builder(log, FileTableTest.COLUMNS, LineSource.of(log), FileTableTest.PARSER)
                .cacheDir(cache).threads(8).version("large-1").open()) {
            warns = ((Number) table.query("SELECT count(*) FROM t WHERE level = 'WARN'", QueryPolicy.readOnly(), null)
                    .getRows().get(0)[0]).longValue();
            read = table.rowsRead();
        }
        long reopenMs = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("%,d lines, %,d MB: first load %,d ms; open again and query %,d ms; cache %,d MB%n", LINES,
                bytes >> 20, loadMs, reopenMs, FileTables.size(cache) >> 20);
        assertEquals(LINES / 10, warns);
        assertEquals("nothing read after the restart", 0, read);
        assertTrue("open again and query took " + reopenMs + " ms", reopenMs < 200 || LINES < 3_000_000);
    }
}
