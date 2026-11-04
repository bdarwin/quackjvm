package io.quackjvm.core.journal;

import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.page.Page;
import io.quackjvm.core.page.PageKey;
import io.quackjvm.core.page.Pages;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** An application's own records: appended, grown, paged and searched - issue #15. */
public class JournalTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Quack quack;
    private String path;

    /** A conversation's messages, as the issue lists them. */
    public record Message(Long id, String session, Integer turn, String role, String model, String text,
                          String toolCalls, Integer tokensIn, Integer tokensOut, LocalDateTime ts) {
    }

    public record NoteV0(Long id, String session, String text) {
    }

    public record NoteV1(Long id, String session, String text, Double cost) {
    }

    @Before
    public void open() throws Exception {
        path = new File(folder.getRoot(), "app.duckdb").getPath();
        quack = Quack.open(path);
    }

    @After
    public void close() {
        if (quack != null) {
            quack.close();
        }
    }

    private Journal<Message> messages() {
        return Journal.builder(quack, "message", ColumnarLayout.ofRecord(Message.class))
                .index("session", "ts").searchable("text", "toolCalls").open();
    }

    private static Message message(long id, String session, String text) {
        return new Message(id, session, (int) (id % 50), id % 2 == 0 ? "user" : "assistant", "model-a", text,
                null, 10, 20, START.plusSeconds(id));
    }

    @Test
    public void appendedRecordsReadBackAndTheIndexIsThere() {
        Journal<Message> journal = messages();
        Message first = message(1, "s1", "hello");
        journal.append(first);
        assertEquals(first, journal.get(1L).orElseThrow());
        assertFalse(journal.get(2L).isPresent());
        assertEquals(1, journal.count());
        assertEquals(0, journal.version());
        assertEquals(1L, (long) quack.query("SELECT count(*) FROM duckdb_indexes() WHERE table_name = 'message'")
                .scalar(Long.class));
    }

    @Test
    public void aKeyAlreadyThereIsRefusedAndABatchWithOneIsNotWrittenAtAll() {
        Journal<Message> journal = messages();
        journal.append(message(1, "s1", "a"));
        try {
            journal.append(message(1, "s1", "again"));
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("update"));
        }
        List<Message> batch = new ArrayList<>();
        for (long id = 2; id < 40; id++) {
            batch.add(message(id, "s1", "b"));
        }
        batch.add(message(1, "s1", "dup"));
        try {
            journal.append(batch);
            fail();
        }
        catch (IllegalArgumentException expected) {
            // refused
        }
        assertEquals("none of the batch was written", 1, journal.count());
        assertEquals("a", journal.get(1L).orElseThrow().text());
    }

    @Test
    public void aMessageThatGrowsAsItStreamsInIsSeenWholeByReaders() throws Exception {
        Journal<Message> journal = messages();
        // No parts yet: the updates below count them from 1.
        journal.append(new Message(7L, "s1", 1, "assistant", "model-a", "", null, 10, 0, START));
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicReference<String> problem = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            int seen = 0;
            while (writing.get()) {
                Message now = journal.get(7L).orElseThrow();
                int parts = now.tokensOut();
                if (now.text().length() != parts * 5) {
                    problem.set("text of " + now.text().length() + " for " + parts + " parts");
                }
                if (parts < seen) {
                    problem.set("went back from " + seen + " to " + parts);
                }
                seen = parts;
            }
        });
        reader.start();
        for (int part = 1; part <= 300; part++) {
            assertTrue(journal.update(7L, Map.of("text", "word ".repeat(part).replace(' ', '.'), "tokensOut", part)));
        }
        writing.set(false);
        reader.join();
        assertNull(problem.get(), problem.get());
        assertEquals(300, (int) journal.get(7L).orElseThrow().tokensOut());
    }

    @Test
    public void updatesSayWhatTheyCannotDo() {
        Journal<Message> journal = messages();
        journal.append(message(1, "s1", "a"));
        assertFalse(journal.update(99L, Map.of("text", "x")));
        try {
            journal.update(1L, Map.of("id", 2L));
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot be changed"));
        }
        try {
            journal.update(1L, Map.of("nope", 2L));
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("No column nope"));
        }
        assertTrue(journal.update(1L, Map.of("TEXT", "case does not matter")));
        assertEquals("case does not matter", journal.get(1L).orElseThrow().text());
    }

    @Test
    public void a100000MessageHistoryPagesBackQuicklyWithoutRepeatsWhileItGrows() throws Exception {
        Journal<Message> journal = messages();
        List<Message> history = new ArrayList<>();
        for (long id = 0; id < 100_000; id++) {
            history.add(message(id, "s" + (id % 3), "message " + id));
        }
        journal.append(history);
        Pages pages = journal.pages("session = ?", "s1").orderBy("ts", "id").size(50);
        Page page = pages.last();
        assertEquals(Long.valueOf(99_997), page.as(journal.layout()).get(49).id());
        Thread writer = new Thread(() -> {
            for (long id = 100_000; id < 100_500; id++) {
                journal.append(message(id, "s1", "new " + id));
            }
        });
        writer.start();
        Set<Long> seen = new HashSet<>();
        List<Double> took = new ArrayList<>();
        for (Message m : page.as(journal.layout())) {
            seen.add(m.id());
        }
        while (page.hasBefore()) {
            long started = System.nanoTime();
            page = pages.before(page.firstKey());
            took.add((System.nanoTime() - started) / 1e6);
            for (Message m : page.as(journal.layout())) {
                assertTrue("repeated " + m.id(), seen.add(m.id()));
            }
        }
        writer.join();
        assertEquals(33_333, seen.size());
        Collections.sort(took);
        System.out.printf("journal page back: median %.2f ms, 99th %.2f ms%n", took.get(took.size() / 2),
                took.get((int) (took.size() * 0.99)));
        assertTrue(took.get((int) (took.size() * 0.99)) < 5);

        Message hit = journal.get(50_002L).orElseThrow();
        Page around = journal.pages("session = ?", hit.session()).orderBy("ts", "id").around(PageKey.of(hit.ts(), hit.id()), 25);
        assertEquals(51, around.size());
        assertEquals(hit, around.as(journal.layout()).get(25));
    }

    @Test
    public void searchFindsEveryWordInAnyCaseNewestFirstWithASnippet() {
        Journal<Message> journal = messages();
        journal.append(message(1, "s1", "The connection was refused by the server at 10:42."));
        journal.append(message(2, "s1", "Connection REFUSED again, retrying in 5 s."));
        journal.append(message(3, "s2", "connection fine"));
        journal.append(new Message(4L, "s2", 1, "tool", "model-a", "ran a tool", "{\"error\":\"connection refused\"}",
                1, 1, START));
        journal.append(message(5, "s2", "100% done_now"));
        journal.append(message(6, "s2", "1000 doneXnow"));

        List<Journal.Hit<Message>> hits = journal.search("connection refused", 10);
        assertEquals(List.of(4L, 2L, 1L), hits.stream().map(h -> h.record().id()).toList());
        assertEquals("toolCalls", hits.get(0).column());
        assertEquals("text", hits.get(1).column());
        assertTrue(hits.get(2).snippet(), hits.get(2).snippet().contains("connection was refused"));
        assertEquals(1, journal.search("connection refused", 1).size());
        assertEquals("% and _ are not wildcards", List.of(5L),
                journal.search("100% done_now", 10).stream().map(h -> h.record().id()).toList());
        assertTrue(journal.search("   ", 10).isEmpty());
        assertTrue(journal.search("nothing-like-this", 10).isEmpty());
    }

    @Test
    public void aSnippetIsTheTextAroundTheWord() {
        String text = "start " + "a ".repeat(60) + "the word here " + "b ".repeat(60) + "end";
        String snippet = Journal.snippet(text, text.indexOf("word"), 4);
        assertTrue(snippet, snippet.startsWith("…"));
        assertTrue(snippet, snippet.endsWith("…"));
        assertTrue(snippet, snippet.contains("the word here"));
        assertTrue(snippet, snippet.length() < 140);
        assertEquals("short word", Journal.snippet("short word", 6, 4));
    }

    @Test
    public void searchingWithNoSearchableColumnSaysSo() {
        Journal<Message> journal = Journal.builder(quack, "message", ColumnarLayout.ofRecord(Message.class)).open();
        try {
            journal.search("x", 5);
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("searchable"));
        }
    }

    @Test
    public void totalsPerModelPerDayArePlainSql() {
        Journal<Message> journal = messages();
        for (long id = 0; id < 10; id++) {
            journal.append(new Message(id, "s1", 1, "assistant", id < 4 ? "model-a" : "model-b", "x", null, 100, 10,
                    START.plusHours(id * 6)));
        }
        List<Long> perDay = quack.query("SELECT sum(tokensIn + tokensOut)::BIGINT FROM message GROUP BY model, ts::DATE"
                + " ORDER BY model, ts::DATE").list(Long.class);
        assertEquals("model-a on day 1; model-b on days 2 and 3", List.of(440L, 440L, 220L), perDay);
    }

    // ---------- versions ----------

    @Test
    public void aMigrationAddsAColumnToAnExistingTableAndOldRowsReadItAsNull() {
        Journal<NoteV0> before = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV0.class)).open();
        before.append(new NoteV0(1L, "s", "old"));
        quack.close();

        quack = Quack.open(path);
        Journal<NoteV1> after = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE").open();
        assertEquals(1, after.version());
        assertEquals(new NoteV1(1L, "s", "old", null), after.get(1L).orElseThrow());
        after.append(new NoteV1(2L, "s", "new", 0.5));
        assertEquals(0.5, after.get(2L).orElseThrow().cost(), 0);
        quack.close();

        quack = Quack.open(path);
        Journal<NoteV1> again = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE").open();
        assertEquals("a migration runs once", 1, again.version());
        assertEquals(2, again.count());
    }

    @Test
    public void aNewTableStartsAtTheLatestVersionWithoutRunningMigrations() {
        // Running it would fail: the table already has cost.
        Journal<NoteV1> fresh = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE").open();
        assertEquals(1, fresh.version());
        fresh.append(new NoteV1(1L, "s", "t", 2.0));
    }

    @Test
    public void aFailedMigrationLeavesTheTableAsItWas() {
        Journal<NoteV0> before = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV0.class)).open();
        before.append(new NoteV0(1L, "s", "old"));
        try {
            Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                    .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE", "ALTER TABLE nowhere ADD COLUMN x INT")
                    .open();
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("still at version 0"));
        }
        assertEquals("the first statement was rolled back too", List.of("id", "session", "text"),
                quack.query("SELECT column_name FROM duckdb_columns() WHERE table_name = 'note' ORDER BY column_index")
                        .list(String.class));
        assertEquals(1, Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV0.class)).open().count());
    }

    @Test
    public void aTableFromALaterVersionOrOfAnotherShapeIsRefused() {
        Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE")
                .migrate(2, "ALTER TABLE note ADD COLUMN extra INT").open();
        try {
            Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class))
                    .migrate(1, "ALTER TABLE note ADD COLUMN cost DOUBLE").open();
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("later version"));
        }
        try {
            Journal.builder(quack, "other", ColumnarLayout.ofRecord(NoteV0.class)).open();
            Journal.builder(quack, "other", ColumnarLayout.ofRecord(NoteV1.class)).open();
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("has columns"));
        }
    }

    @Test
    public void migrationsAreNumberedFromOneOnce() {
        Journal.Builder<NoteV1> builder = Journal.builder(quack, "note", ColumnarLayout.ofRecord(NoteV1.class));
        for (int bad : new int[] {0, -1}) {
            try {
                builder.migrate(bad, "SELECT 1");
                fail();
            }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("start at 1"));
            }
        }
        builder.migrate(1, "SELECT 1");
        try {
            builder.migrate(1, "SELECT 2");
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("already"));
        }
    }

    // ---------- the process ending mid-write ----------

    @Test
    public void killedMidWriteTheFileOpensAndEveryRecordIsWhole() throws Exception {
        quack.close();
        quack = null;
        String crashed = new File(folder.getRoot(), "crashed.duckdb").getPath();
        for (int run = 0; run < 3; run++) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), JournalCrashChild.class.getName(), crashed)
                    .redirectErrorStream(true).start();
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            String line;
            do {
                line = out.readLine();   // the JVM may warn first, about the driver's native library
            } while (line != null && !line.equals("ready"));
            assertEquals("the writer started", "ready", line);
            Thread.sleep(200 + run * 150L);
            child.destroyForcibly();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));

            try (Quack reopened = Quack.open(crashed)) {
                Journal<JournalCrashChild.Streamed> journal = Journal.builder(reopened, "streamed",
                        ColumnarLayout.ofRecord(JournalCrashChild.Streamed.class)).open();
                long rows = journal.count();
                assertTrue("rows were written: " + rows, rows > 0);
                Map<String, Long> broken = new HashMap<>();
                long bad = reopened.query("SELECT count(*) FROM streamed WHERE length(text) <> parts * 25")
                        .scalar(Long.class);
                broken.put("text and parts disagree", bad);
                assertEquals(broken.toString(), 0L, bad);
                // The next run appends after what is there.
                reopened.execute("DELETE FROM streamed");
            }
        }
    }
}
