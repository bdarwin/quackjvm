package io.quackjvm.core.page;

import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Keyset pages for scrolling - issue #15. */
public class PagesTest {

    private static final int ROWS = 100_000;
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);

    private Quack db;

    public record Message(Long id, String session, LocalDateTime ts, String text) {
    }

    @Before
    public void open() {
        db = Quack.inMemory();
        db.execute("CREATE TABLE message (id BIGINT, session VARCHAR, ts TIMESTAMP, text VARCHAR)");
        // Three messages a second, so pages end in the middle of rows that share a time: only the
        // id tells them apart.
        db.execute("INSERT INTO message SELECT x, 's' || (x % 4), TIMESTAMP '2026-01-01' + INTERVAL (x // 3) SECOND,"
                + " 'message ' || x FROM range(" + ROWS + ") r(x)");
    }

    @After
    public void close() {
        db.close();
    }

    private Pages pages() {
        return Pages.of(db, "SELECT * FROM message").orderBy("ts", "id").size(50);
    }

    private static long id(Page page, int row) {
        return ((Number) page.get(row, "id")).longValue();
    }

    private static List<Long> ids(Page page) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            ids.add(id(page, i));
        }
        return ids;
    }

    @Test
    public void theLastPageThen2000PagesBackEachInAFewMillisecondsReadEveryRowOnce() {
        Pages pages = pages();
        for (int warm = 0; warm < 20; warm++) {
            pages.before(pages.last().firstKey());
        }
        List<Long> seen = new ArrayList<>();
        List<Double> took = new ArrayList<>();
        Page page = pages.last();
        assertFalse(page.hasAfter());
        seen.addAll(0, ids(page));
        for (int i = 1; i < 2000; i++) {
            long started = System.nanoTime();
            page = pages.before(page.firstKey());
            took.add((System.nanoTime() - started) / 1e6);
            seen.addAll(0, ids(page));
        }
        assertFalse("the first page has nothing before it", page.hasBefore());
        assertEquals(ROWS, seen.size());
        for (int i = 0; i < ROWS; i++) {
            assertEquals("in order, none repeated or missing", Long.valueOf(i), seen.get(i));
        }
        Collections.sort(took);
        double median = took.get(took.size() / 2);
        double p99 = took.get((int) (took.size() * 0.99));
        System.out.printf("before(): median %.2f ms, 99th %.2f ms, slowest %.2f ms%n", median, p99,
                took.get(took.size() - 1));
        // About 1 ms on a developer machine; a shared CI runner is several times slower. The bound
        // matters, not the machine: without it a page here ranks tens of thousands of rows.
        assertTrue("99th percentile " + p99 + " ms", p99 < 20);
    }

    @Test
    public void scrollingBackWhileRowsAreAppendedNeitherRepeatsNorSkips() throws Exception {
        Pages pages = pages();
        Page page = pages.last();
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicLong written = new AtomicLong();
        Thread writer = new Thread(() -> {
            long id = ROWS;
            while (writing.get()) {
                db.execute("INSERT INTO message VALUES (?, 's0', ?, 'new')", id, START.plusDays(10).plusSeconds(id));
                id++;
                written.incrementAndGet();
            }
        });
        writer.start();
        Set<Long> seen = new HashSet<>(ids(page));
        int pagesRead = 1;
        while (page.hasBefore()) {
            page = pages.before(page.firstKey());
            for (long id : ids(page)) {
                assertTrue("repeated " + id, seen.add(id));
            }
            pagesRead++;
        }
        writing.set(false);
        writer.join();
        assertTrue("rows were appended meanwhile: " + written.get(), written.get() > 0);
        assertEquals(ROWS, seen.size());
        assertEquals(2000, pagesRead);
    }

    @Test
    public void scrollingForwardWhileRowsAreAppendedReachesThemInOrder() throws Exception {
        Pages pages = pages().size(1000);
        Thread writer = new Thread(() -> {
            for (long id = ROWS; id < ROWS + 5000; id++) {
                db.execute("INSERT INTO message VALUES (?, 's0', ?, 'new')", id, START.plusDays(10).plusSeconds(id));
            }
        });
        writer.start();
        Page page = pages.first();
        List<Long> seen = new ArrayList<>(ids(page));
        PageKey last = page.lastKey();
        while (true) {
            boolean writerDone = !writer.isAlive();   // read before the page, so nothing is missed after it
            Page next = pages.after(last);
            if (next.isEmpty()) {
                if (writerDone) {
                    break;
                }
                Thread.sleep(1);
                continue;
            }
            seen.addAll(ids(next));
            last = next.lastKey();
        }
        writer.join();
        assertEquals(ROWS + 5000, seen.size());
        for (int i = 1; i < seen.size(); i++) {
            assertEquals("in order, none repeated or missing", seen.get(i - 1) + 1, (long) seen.get(i));
        }
    }

    @Test
    public void aroundAKeyGives25RowsEachSide() {
        Pages pages = pages();
        Page hit = pages.around(PageKey.of(START.plusSeconds(20_000), 60_001L), 25);
        assertEquals(51, hit.size());
        assertEquals(60_001L - 25, id(hit, 0));
        assertEquals(60_001L, id(hit, 25));
        assertEquals(60_001L + 25, id(hit, 50));
        assertTrue(hit.hasBefore());
        assertTrue(hit.hasAfter());
    }

    @Test
    public void aroundAKeyNoRowHasGivesTheRowsEachSideOfWhereItWouldBe() {
        // Between ids 60,001 and 60,002, which share a time.
        Page hit = pages().around(PageKey.of(START.plusSeconds(20_000), 60_001L), 0);
        assertEquals(List.of(60_001L), ids(hit));
        Page between = Pages.of(db, "SELECT * FROM message WHERE id % 2 = 0").orderBy("id").size(50)
                .around(PageKey.of(1001L), 3);
        assertEquals(List.of(996L, 998L, 1000L, 1002L, 1004L, 1006L), ids(between));
    }

    @Test
    public void aroundTheFirstAndLastRowsGivesWhatThereIs() {
        Pages pages = pages();
        Page start = pages.around(PageKey.of(START, 0L), 25);
        assertEquals(26, start.size());
        assertFalse(start.hasBefore());
        Page end = pages.around(pages.last().lastKey(), 25);
        assertEquals(26, end.size());
        assertFalse(end.hasAfter());
        assertEquals(ROWS - 1L, id(end, 25));
    }

    @Test
    public void theCountIsRememberedUntilCountedAgain() {
        Pages pages = pages();
        assertEquals(ROWS, pages.count());
        db.execute("INSERT INTO message VALUES (?, 's0', ?, 'new')", (long) ROWS, START.plusDays(10));
        assertEquals(ROWS, pages.count());
        assertEquals(ROWS + 1, pages.recount());
        assertEquals(ROWS + 1, pages.count());
    }

    @Test
    public void aRowsPositionAndThePageThatStartsThere() {
        Pages pages = pages();
        PageKey key = PageKey.of(START.plusSeconds(1000), 3001L);
        assertEquals(3001, pages.position(key));
        Page there = pages.at(3001);
        assertEquals(key, there.firstKey());
        assertEquals(50, there.size());
        assertTrue(there.hasBefore());
        assertEquals(List.of(ROWS - 2L, ROWS - 1L), ids(pages.at(ROWS - 2)));
        assertFalse(pages.at(ROWS - 2).hasAfter());
    }

    @Test
    public void theQuerysParametersAndFilterApply() {
        Pages pages = Pages.of(db, "SELECT * FROM message WHERE session = ?", "s1").orderBy("ts", "id").size(10);
        Page last = pages.last();
        assertEquals(List.of(99_929L, 99_933L, 99_937L, 99_941L, 99_945L, 99_949L, 99_953L, 99_957L, 99_961L, 99_965L,
                99_969L, 99_973L, 99_977L, 99_981L, 99_985L, 99_989L, 99_993L, 99_997L).subList(8, 18), ids(last));
        assertEquals(ROWS / 4, pages.count());
        assertEquals(10, pages.before(last.firstKey()).size());
    }

    @Test
    public void theQuerysOwnOrderIsOverridden() {
        Page first = Pages.of(db, "SELECT * FROM message ORDER BY id DESC").orderBy("id").size(3).first();
        assertEquals(List.of(0L, 1L, 2L), ids(first));
    }

    @Test
    public void pagesOfAPlainConnectionAndRowsAsRecords() throws Exception {
        try (java.sql.Connection connection = db.connection()) {
            Pages pages = Pages.of(connection, "SELECT id, session, ts, text FROM message", List.of()).orderBy("id")
                    .size(2);
            List<Message> messages = pages.first().as(ColumnarLayout.ofRecord(Message.class));
            assertEquals(new Message(0L, "s0", START, "message 0"), messages.get(0));
            assertEquals(new Message(1L, "s1", START, "message 1"), messages.get(1));
        }
    }

    @Test
    public void aKeyMadeFromJavaValuesFindsItsRow() {
        Message hit = new Message(70_000L, "s0", START.plusSeconds(70_000 / 3), "message 70000");
        Page page = pages().around(PageKey.of(hit.ts(), hit.id()), 1);
        assertEquals(List.of(69_999L, 70_000L, 70_001L), ids(page));
    }

    @Test
    public void anEmptyResultHasEmptyPages() {
        Pages pages = Pages.of(db, "SELECT * FROM message WHERE false").orderBy("ts", "id");
        Page last = pages.last();
        assertTrue(last.isEmpty());
        assertNull(last.firstKey());
        assertFalse(last.hasBefore());
        assertFalse(pages.first().hasAfter());
        assertEquals(0, pages.count());
    }

    @Test
    public void mistakesAreSaidPlainly() {
        try {
            Pages.of(db, "SELECT text FROM message").orderBy("ts", "id").last();
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("does not return the order column ts"));
        }
        try {
            Pages.of(db, "SELECT * FROM message").last();
            fail();
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("orderBy"));
        }
        try {
            pages().before(PageKey.of(1L));
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("2 order columns"));
        }
        try {
            pages().before(null);
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty page"));
        }
        try {
            PageKey.of(1L, null);
            fail();
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }
}
