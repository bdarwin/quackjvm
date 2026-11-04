package io.quackjvm.agent.file;

import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.session.QuerySession;
import io.quackjvm.core.duckdb.ColumnDef;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A CSV with a header, blank lines and quoted values spanning lines: the parser leaves out the lines
 * that are not rows, and the table holds the real rows only, with their own line numbers.
 */
public class FileTableRowsLeftOutTest {

    static final List<ColumnDef> COLUMNS = List.of(new ColumnDef("line", Long.class), new ColumnDef("id", Long.class),
            new ColumnDef("name", String.class), new ColumnDef("note", String.class));

    /** A row starts with its id and a comma; the header, blank lines and a value's later lines do not. */
    static final RowParser PARSER = new RowParser() {
        @Override
        public boolean isRow(String raw, long rowNo) {
            int comma = raw.indexOf(',');
            if (rowNo == 0 || comma <= 0) {
                return false;
            }
            for (int i = 0; i < comma; i++) {
                if (!Character.isDigit(raw.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void parse(String raw, long rowNo, Object[] cells) {
            String[] parts = raw.split(",", 3);
            cells[0] = rowNo + 1;
            cells[1] = Long.parseLong(parts[0]);
            cells[2] = parts[1];
            cells[3] = parts[2];
        }
    };

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path csv;
    private Path cache;
    private final List<FileTable> opened = new ArrayList<>();
    private final List<QuerySession> sessions = new ArrayList<>();

    /** The rows written so far: their ids and the line each starts on, counted from 1. */
    private final List<long[]> expected = new ArrayList<>();
    private long lines;

    /** Rows {@code from} to {@code to - 1}: every 5th followed by a blank line, every 7th with a note on two lines. */
    private String rows(long from, long to, String prefix) {
        StringBuilder text = new StringBuilder();
        for (long id = from; id < to; id++) {
            expected.add(new long[] {id, lines + 1});
            if (id % 7 == 0) {
                text.append(id).append(",").append(prefix).append(id).append(",\"spans\nlines ").append(id).append("\"\n");
                lines += 2;
            }
            else {
                text.append(id).append(",").append(prefix).append(id).append(",plain ").append(id).append('\n');
                lines++;
            }
            if (id % 5 == 0) {
                text.append('\n');
                lines++;
            }
        }
        return text.toString();
    }

    private void writeNew(long rows, String prefix) throws Exception {
        expected.clear();
        lines = 1;
        // Written beside it and moved over it, as a file is rotated.
        Path next = folder.getRoot().toPath().resolve("next.csv");
        Files.writeString(next, "id,name,note\n" + rows(0, rows, prefix));
        Files.move(next, csv, StandardCopyOption.REPLACE_EXISTING);
    }

    @Before
    public void files() throws Exception {
        csv = folder.getRoot().toPath().resolve("people.csv");
        cache = folder.newFolder("cache").toPath();
        writeNew(3000, "n");
    }

    @After
    public void close() {
        for (QuerySession session : sessions) {
            session.close();
        }
        for (FileTable table : opened) {
            table.close();
        }
    }

    private FileTable open(boolean lazy, boolean cached) {
        FileTable.Builder builder = FileTables.builder(csv, COLUMNS, LineSource.of(csv), PARSER).threads(4).blockSize(100)
                .lazyColumns(lazy).version("left-out-1");
        if (cached) {
            builder.cacheDir(cache);
        }
        FileTable table = builder.open();
        opened.add(table);
        return table;
    }

    private static GuardedResult run(FileTable table, String sql) {
        return table.query(sql, QueryPolicy.readOnly().maxRows(100_000), null);
    }

    private static long count(FileTable table, String sql) {
        return ((Number) run(table, sql).getRows().get(0)[0]).longValue();
    }

    /** Every row's id, line, name and note, as they were written, in order. */
    private void checkRows(List<Object[]> rows, String prefix) {
        assertEquals(expected.size(), rows.size());
        for (int i = 0; i < rows.size(); i++) {
            long id = expected.get(i)[0];
            Object[] row = rows.get(i);
            assertEquals("id of row " + i, id, ((Number) row[0]).longValue());
            assertEquals("line of id " + id, expected.get(i)[1], ((Number) row[1]).longValue());
            assertEquals(prefix + id, row[2]);
            assertEquals(id % 7 == 0 ? "\"spans" : "plain " + id, row[3]);
        }
    }

    private void checkAll(FileTable table, String prefix) {
        assertEquals(expected.size(), count(table, "SELECT count(*) FROM t"));
        checkRows(run(table, "SELECT id, line, name, note FROM t ORDER BY id").getRows(), prefix);
    }

    @Test
    public void onlyTheRealRowsAreStoredWithTheirOwnLineNumbersAndTheSourceRowsAreStillCounted() {
        FileTable table = open(false, true);
        FileTable.Updated updated = table.update();
        assertEquals("every line read", lines, updated.rows());
        assertEquals(lines, table.rowsLoaded());
        assertEquals("the header, blank lines and second lines left out", 3000, table.rowsStored());
        assertTrue(lines > 3000 + 600);
        checkAll(table, "n");
        assertEquals(0, count(table, "SELECT count(*) FROM t WHERE id IS NULL OR name IS NULL"));
        // The table's own row number is the source's too: one less than the line.
        assertEquals(0, count(table, "SELECT count(*) FROM " + FileTable.DATA + " WHERE " + FileTable.ROW + " + 1 <> line"));
    }

    @Test
    public void growingKeepsTheLineNumbersAndALazyColumnAddedLaterLinesUp() throws Exception {
        FileTable table = open(true, true);
        table.update();
        assertEquals(3000, count(table, "SELECT count(*) FROM t"));
        assertEquals(3000, count(table, "SELECT count(DISTINCT id) FROM t"));
        assertEquals(List.of("id"), table.storedColumns());

        Files.writeString(csv, rows(3000, 3500, "n"), StandardOpenOption.APPEND);
        FileTable.Updated grown = table.update();
        assertFalse(grown.rebuilt());
        assertEquals("the lines added, blank ones and second ones too", lines, table.rowsLoaded());
        assertEquals(3500, table.rowsStored());

        // line, name and note are stored now, by a second pass over the file: it leaves out the same rows.
        long read = table.rowsRead();
        checkAll(table, "n");
        assertEquals("one more pass", read + lines, table.rowsRead());
        assertEquals(3500, table.rowsStored());

        Files.writeString(csv, rows(3500, 3600, "n"), StandardOpenOption.APPEND);
        table.update();
        checkAll(table, "n");
    }

    @Test
    public void reopenedFromTheCacheAndRebuiltWhenTheFileIsReplaced() throws Exception {
        FileTable table = open(true, true);
        table.update();
        run(table, "SELECT id, name FROM t");
        table.close();
        opened.remove(table);

        FileTable again = open(true, true);
        assertEquals(lines, again.rowsLoaded());
        assertEquals(3000, again.rowsStored());
        assertEquals(3000, count(again, "SELECT count(*) FROM t"));
        assertEquals("nothing read after the restart", 0, again.rowsRead());
        checkAll(again, "n");    // line and note added now

        writeNew(4200, "m");
        FileTable.Updated rebuilt = again.update();
        assertTrue(rebuilt.rebuilt());
        assertEquals(4200, again.rowsStored());
        assertEquals(lines, again.rowsLoaded());
        checkAll(again, "m");
    }

    @Test
    public void inMemoryTheSame() throws Exception {
        FileTable table = open(true, false);
        table.update();
        checkAll(table, "n");
        Files.writeString(csv, rows(3000, 3100, "n"), StandardOpenOption.APPEND);
        table.update();
        checkAll(table, "n");
    }

    @Test
    public void inAQuerySessionCachedOrInMemory() throws Exception {
        for (boolean cached : new boolean[] {true, false}) {
            writeNew(3000, "n");
            FileTable table = open(true, cached);
            table.update();
            QuerySession session = QuerySession.open(Hardening.options(), QueryPolicy.readOnly().maxRows(100_000));
            sessions.add(session);
            session.register("people", table);
            session.register("tags", "SELECT range AS id, 'tag ' || range AS tag FROM range(0, 100000, 10)");
            assertEquals(3000L, ((Number) session.run("SELECT count(*) FROM people").getRows().get(0)[0]).longValue());
            assertEquals(3000, session.describe("people").rows());
            List<Object[]> joined = session.run("SELECT p.id, p.line, p.name, g.tag FROM people p JOIN tags g USING (id)"
                    + " ORDER BY id").getRows();
            assertEquals(300, joined.size());
            for (Object[] row : joined) {
                long id = ((Number) row[0]).longValue();
                assertEquals(expected.get((int) id)[1], ((Number) row[1]).longValue());
                assertEquals("n" + id, row[2]);
                assertEquals("tag " + id, row[3]);
            }
            Files.writeString(csv, rows(3000, 3050, "n"), StandardOpenOption.APPEND);
            table.update();
            checkRows(session.run("SELECT id, line, name, note FROM people ORDER BY id").getRows(), "n");
            session.close();
        }
    }

    @Test
    public void aComputedColumnIsWorkedOutForTheRowsKeptOnly() {
        java.util.concurrent.atomic.AtomicLong parsed = new java.util.concurrent.atomic.AtomicLong();
        RowParser counting = new RowParser() {
            @Override
            public boolean isRow(String raw, long rowNo) {
                return PARSER.isRow(raw, rowNo);
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells) {
                parsed.incrementAndGet();
                PARSER.parse(raw, rowNo, cells);
            }
        };
        FileTable table = FileTables.builder(csv, COLUMNS, LineSource.of(csv), counting).cacheDir(cache).lazyColumns(true)
                .threads(4).blockSize(100).version("computed-left-out-1").open();
        opened.add(table);
        table.update();
        run(table, "SELECT id FROM t");
        assertEquals("the rows kept parsed, by the update and for id", 2 * 3000, parsed.get());

        java.util.concurrent.atomic.AtomicLong computed = new java.util.concurrent.atomic.AtomicLong();
        table.column(new ColumnDef("shown_line", Long.class), (raw, rowNo) -> {
            computed.incrementAndGet();
            assertTrue(raw, PARSER.isRow(raw, rowNo));
            return rowNo + 1;
        });
        List<Object[]> rows = run(table, "SELECT id, shown_line FROM t ORDER BY id").getRows();
        assertEquals(3000, computed.get());
        assertEquals("a pass for computed columns alone does not parse", 2 * 3000, parsed.get());
        assertEquals(3000, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(expected.get(i)[1], ((Number) rows.get(i)[1]).longValue());
        }
    }

    @Test
    public void aParserThatLeavesOutOtherRowsOnASecondPassIsRefusedWithTheReason() {
        AtomicBoolean blankAreRows = new AtomicBoolean();
        RowParser fickle = new RowParser() {
            @Override
            public boolean isRow(String raw, long rowNo) {
                return PARSER.isRow(raw, rowNo) || (blankAreRows.get() && raw.isEmpty());
            }

            @Override
            public void parse(String raw, long rowNo, Object[] cells) {
                if (!raw.isEmpty()) {
                    PARSER.parse(raw, rowNo, cells);
                }
            }
        };
        FileTable table = FileTables.builder(csv, COLUMNS, LineSource.of(csv), fickle).cacheDir(cache).lazyColumns(true)
                .version("fickle-1").open();
        opened.add(table);
        table.update();
        assertEquals(3000, count(table, "SELECT count(id) FROM t"));
        blankAreRows.set(true);
        try {
            run(table, "SELECT name FROM t");
            fail("stored a column that does not line up");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("isRow"));
        }
        assertEquals(List.of("id"), table.storedColumns());
        blankAreRows.set(false);
        assertEquals(3000, count(table, "SELECT count(name) FROM t"));
    }
}
