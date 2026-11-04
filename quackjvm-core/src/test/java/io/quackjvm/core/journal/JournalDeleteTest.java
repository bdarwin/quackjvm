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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Removing records from a journal, keys of several columns, and numbers within a group - issue #17.
 */
public class JournalDeleteTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Quack quack;
    private Path path;

    /** A record keyed by one number. */
    public record Entry(Long id, Long chat, LocalDateTime time, String text) {
    }

    /** A chat's message: keyed by the chat and its number in it. */
    public record ChatMessage(Long chat, Long seq, String role, LocalDateTime time, String text) {
    }

    /** As above, numbered with an int. */
    public record Small(Long chat, int seq, String text) {
    }

    @Before
    public void open() throws Exception {
        path = folder.getRoot().toPath().resolve("app.duckdb");
        quack = Quack.open(path.toString());
    }

    @After
    public void close() {
        if (quack != null) {
            quack.close();
        }
    }

    private Journal<Entry> entries() {
        return Journal.builder(quack, "entry", ColumnarLayout.ofRecord(Entry.class))
                .index("chat").searchable("text").open();
    }

    private Journal<ChatMessage> chatMessages() {
        return Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
                .keyColumns("chat", "seq").sequence("seq").within("chat").newestBy("time")
                .searchable("text").open();
    }

    private static List<Entry> entries(long from, long to, int chats) {
        List<Entry> all = new ArrayList<>();
        for (long id = from; id < to; id++) {
            all.add(new Entry(id, id % chats, START.plusSeconds(id), "entry " + id + " of chat " + id % chats));
        }
        return all;
    }

    private static ChatMessage said(long chat, String text, long second) {
        return new ChatMessage(chat, null, "user", START.plusSeconds(second), text);
    }

    private static List<Object[]> rows(Quack quack, String sql) {
        try (java.sql.Connection connection = quack.connection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            List<Object[]> rows = new ArrayList<>();
            int width = result.getMetaData().getColumnCount();
            while (result.next()) {
                Object[] row = new Object[width];
                for (int i = 0; i < width; i++) {
                    row[i] = result.getObject(i + 1);
                }
                rows.add(row);
            }
            return rows;
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- deleting ----------

    @Test
    public void deleteOneAndTenThousandOfAHundredThousandAndGetSearchCountAndPagesAgree() {
        Journal<Entry> journal = entries();
        journal.append(entries(0, 100_000, 10));
        Pages before = journal.pages(null).orderBy("id").size(500);
        assertEquals(100_000, before.count());

        assertTrue(journal.delete(5L));
        assertFalse("already gone", journal.delete(5L));
        assertEquals(10_000, journal.deleteWhere("chat = ?", 3L));
        assertEquals(0, journal.deleteWhere("chat = ?", 3L));

        assertEquals(89_999, journal.count());
        assertFalse(journal.get(5L).isPresent());
        assertFalse(journal.get(13L).isPresent());
        assertTrue(journal.get(14L).isPresent());
        assertTrue(journal.search("entry 13 of", 1000).stream().noneMatch(h -> h.record().id() == 13));
        assertTrue(journal.search("of chat 3", 1000).stream().noneMatch(h -> h.record().chat() == 3));
        assertEquals(10, journal.search("chat 4", 10).size());
        assertEquals(List.of(99_994L, 99_984L), journal.search("chat 4", 2).stream().map(h -> h.record().id()).toList());

        assertEquals("a count already made is remembered", 100_000, before.count());
        assertEquals(89_999, before.recount());
        Pages pages = journal.pages(null).orderBy("id").size(500);
        assertEquals(89_999, pages.count());
        Set<Long> seen = new HashSet<>();
        for (Page page = pages.first(); ; page = pages.after(page.lastKey())) {
            for (Entry entry : page.as(journal.layout())) {
                assertTrue(seen.add(entry.id()));
                assertTrue(entry.id() + " was deleted", entry.id() != 5 && entry.chat() != 3);
            }
            if (!page.hasAfter()) {
                break;
            }
        }
        assertEquals(89_999, seen.size());
        assertEquals("a deleted key's position is where it would be: 0, 1, 2 and 4 come before 5",
                4, pages.position(PageKey.of(5L)));
        assertEquals(4, pages.position(PageKey.of(6L)));
        assertEquals("around a deleted key: the row each side of where it was", List.of(4L, 6L),
                pages.around(PageKey.of(5L), 1).as(journal.layout()).stream().map(Entry::id).toList());
    }

    @Test
    public void deleteWhereNeedsAConditionAndEverythingHasToBeAskedFor() {
        Journal<Entry> journal = entries();
        journal.append(entries(0, 100, 4));
        for (String nothing : new String[] {null, "", "  "}) {
            try {
                journal.deleteWhere(nothing);
                fail("deleted with no condition: '" + nothing + "'");
            }
            catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("deleteWhere(\"true\")"));
            }
        }
        assertEquals(100, journal.count());
        assertEquals(25, journal.deleteWhere("chat = ? AND id < ?", 1L, 1000L));
        assertEquals(75, journal.deleteWhere("true"));
        assertEquals(0, journal.count());
    }

    @Test
    public void aFailedDeleteWhereRemovesNothing() {
        Journal<Entry> journal = entries();
        journal.append(entries(0, 1000, 4));
        try {
            journal.deleteWhere("chat = ? AND no_such_column = 1", 1L);
            fail();
        }
        catch (RuntimeException expected) {
            // the condition is wrong
        }
        try {
            journal.deleteWhere("chat = ?");
            fail("a parameter was missing");
        }
        catch (RuntimeException expected) {
            // no value for ?
        }
        assertEquals(1000, journal.count());
    }

    @Test
    public void pagingAChatWhileOtherChatsAreDeletedReadsEveryRemainingRowExactlyOnce() throws Exception {
        Journal<ChatMessage> journal = chatMessages();
        List<ChatMessage> all = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            all.add(said(i % 20, "message " + i, i));
        }
        journal.append(all);
        assertEquals(5_000, journal.pages("chat = ?", 7L).count());

        Pages pages = journal.pages("chat = ?", 7L).orderBy("seq").size(50);
        AtomicBoolean reading = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Long> deleted = new ConcurrentLinkedQueue<>();
        Thread deleter = new Thread(() -> {
            Random random = new Random(17);
            while (reading.get()) {
                long chat = random.nextInt(20);
                if (chat == 7) {
                    continue;
                }
                journal.deleteWhere("chat = ? AND seq <= ?", chat, (long) random.nextInt(5_000));
                journal.delete(PageKey.of(chat, (long) random.nextInt(5_000) + 1));
                deleted.add(chat);
            }
        });
        deleter.start();
        Set<Long> seen = new HashSet<>();
        Page page = pages.last();
        while (true) {
            for (ChatMessage m : page.as(journal.layout())) {
                assertEquals(Long.valueOf(7), m.chat());
                assertTrue("read twice: " + m.seq(), seen.add(m.seq()));
            }
            if (!page.hasBefore()) {
                break;
            }
            page = pages.before(page.firstKey());
        }
        reading.set(false);
        deleter.join();
        assertEquals("every message of chat 7, each once", 5_000, seen.size());
        assertTrue("the other chats were being deleted meanwhile: " + deleted.size(), deleted.size() > 5);
    }

    @Test
    public void deletingTheChatBeingPagedGivesAnEmptyPageOrRowsStillThereAndNoException() throws Exception {
        Journal<ChatMessage> journal = chatMessages();
        List<ChatMessage> all = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            all.add(said(i % 4, "message " + i, i));
        }
        journal.append(all);

        // Deleted all at once, half way through.
        Pages pages = journal.pages("chat = ?", 2L).orderBy("seq").size(100);
        Page page = pages.last();
        for (int i = 0; i < 25; i++) {
            page = pages.before(page.firstKey());
        }
        assertEquals(5_000, journal.deleteWhere("chat = ?", 2L));
        Page next = pages.before(page.firstKey());
        assertEquals(0, next.size());
        assertFalse(next.hasBefore());
        assertEquals(0, pages.after(page.lastKey()).size());
        assertEquals(0, pages.last().size());

        // Deleted a row at a time, from another thread, while it is paged.
        Pages chat1 = journal.pages("chat = ?", 1L).orderBy("seq").size(37);
        CountDownLatch started = new CountDownLatch(1);
        Thread deleter = new Thread(() -> {
            started.countDown();
            for (long seq = 5_000; seq >= 1; seq -= 1) {
                journal.delete(PageKey.of(1L, seq));
            }
        });
        Set<Long> seen = new HashSet<>();
        Page at = chat1.last();
        deleter.start();
        started.await();
        while (true) {
            for (ChatMessage m : at.as(journal.layout())) {
                assertTrue("read twice: " + m.seq(), seen.add(m.seq()));
            }
            if (!at.hasBefore()) {
                break;
            }
            at = chat1.before(at.firstKey());
        }
        deleter.join();
        assertEquals(0, journal.pages("chat = ?", 1L).count());
        assertEquals(0, chat1.last().size());
        System.out.println("paged while deleted one at a time: read " + seen.size() + " of 5000 before they went");
    }

    @Test
    public void killedDuringADeleteWhereOfFiftyThousandEitherAllAreGoneOrNone() throws Exception {
        quack.close();
        quack = null;
        String crashed = new File(folder.getRoot(), "deleting.duckdb").getPath();
        Random random = new Random(5);
        int midDelete = 0;
        long deleteMs = 40;
        for (int run = 0; run < 6; run++) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), JournalDeleteChild.class.getName(), crashed)
                    .redirectErrorStream(true).start();
            BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            int deletes = 1 + random.nextInt(2);
            String line;
            String last = null;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("deleted ") && line.endsWith(" ms")) {
                    String[] words = line.split(" ");
                    deleteMs = Math.max(1, Long.parseLong(words[words.length - 2]));
                }
                if (line.startsWith("deleting ") && --deletes == 0) {
                    last = line;
                    break;
                }
            }
            assertNotNull("the child started deleting", last);
            Thread.sleep(random.nextInt((int) Math.max(2, deleteMs)));
            child.destroyForcibly();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));

            try (Quack reopened = Quack.open(crashed)) {
                Journal<JournalDeleteChild.Said> journal = JournalDeleteChild.open(reopened);
                List<Object[]> chats = rows(reopened, "SELECT chat, count(*) FROM said GROUP BY 1 ORDER BY 1");
                assertFalse("a chat is kept whole each round", chats.isEmpty());
                for (Object[] chat : chats) {
                    long count = ((Number) chat[1]).longValue();
                    assertEquals("chat " + chat[0] + " is whole: all of a delete or none", JournalDeleteChild.MESSAGES,
                            count);
                }
                long killedChat = Long.parseLong(last.substring("deleting ".length()));
                boolean stillThere = chats.stream().anyMatch(c -> ((Number) c[0]).longValue() == killedChat);
                if (stillThere) {
                    midDelete++;
                }
                assertTrue(journal.count() > 0);
            }
        }
        System.out.println("killed during deleteWhere (" + deleteMs + " ms each): the chat was still whole after "
                + midDelete + " of 6 kills, gone after the others");
    }

    @Test
    public void aJournalAddedToAndDeletedFromLevelsOffInsteadOfGrowing() throws Exception {
        Journal<ChatMessage> journal = chatMessages();
        Random random = new Random(11);
        List<Long> live = new ArrayList<>();
        List<Double> sizes = new ArrayList<>();
        long chat = 0;
        for (int round = 0; round < 20; round++) {
            List<ChatMessage> batch = new ArrayList<>();
            for (int c = 0; c < 40; c++, chat++) {
                live.add(chat);
                for (int m = 0; m < 1000; m++) {
                    batch.add(said(chat, "message words words words words words " + m, m));
                }
            }
            journal.append(batch);
            if (round > 0) {
                for (int d = 0; d < 40; d++) {
                    long gone = live.remove(random.nextInt(live.size()));
                    assertEquals(1000, journal.deleteWhere("chat = ?", gone));
                }
            }
            quack.execute("CHECKPOINT");
            sizes.add(Files.size(path) / 1e6);
        }
        assertEquals(40_000, journal.count());
        StringBuilder line = new StringBuilder("40,000 messages live, 40 chats added and 40 deleted a round, MB:");
        for (double size : sizes) {
            line.append(String.format(" %.1f", size));
        }
        System.out.println(line);
        double settled = sizes.get(4);
        double last = sizes.get(sizes.size() - 1);
        assertTrue(line.toString(), last < settled * 1.5);
    }

    @Test
    public void copyingTheDatabaseIntoANewFileShrinksItAndTheJournalOpensThere() throws Exception {
        Journal<Entry> journal = entries();
        journal.append(entries(0, 400_000, 100));
        assertEquals(300_000, journal.deleteWhere("chat % 4 <> 0"));
        quack.execute("CHECKPOINT");
        long before = Files.size(path);

        Path fresh = folder.getRoot().toPath().resolve("fresh.duckdb");
        String name = quack.query("SELECT current_database()").scalar(String.class);
        long started = System.nanoTime();
        quack.execute("ATTACH '" + fresh + "' AS fresh");
        quack.execute("COPY FROM DATABASE " + name + " TO fresh");
        quack.execute("DETACH fresh");
        double tookMs = (System.nanoTime() - started) / 1e6;
        long after = Files.size(fresh);
        System.out.printf("100,000 of 400,000 entries left: %.1f MB; copied into a new file: %.1f MB in %.0f ms%n",
                before / 1e6, after / 1e6, tookMs);
        assertTrue(after + " vs " + before, after < before * 0.6);
        quack.close();

        quack = Quack.open(fresh.toString());
        Journal<Entry> moved = entries();
        assertEquals(100_000, moved.count());
        assertEquals(0, moved.version());
        assertEquals("entry 400 of chat 0", moved.get(400L).orElseThrow().text());
        assertEquals(List.of(399_900L), moved.search("entry 399900", 5).stream().map(h -> h.record().id()).toList());
        try {
            moved.append(entries(0, 1, 100));
            fail("the key came too");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already in entry"));
        }
        assertEquals("the index came too", 1L, (long) quack.query("SELECT count(*) FROM duckdb_indexes()"
                + " WHERE table_name = 'entry'").scalar(Long.class));
        moved.append(entries(400_000, 400_010, 100));
        assertEquals(100_010, moved.count());
    }

    // ---------- keys of several columns ----------

    @Test
    public void aKeyOfTwoColumnsIsPassedAsAPageKeyAndATwiceUsedOneIsRefused() {
        Journal<ChatMessage> journal = Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
                .keyColumns("chat", "seq").searchable("text").open();
        assertEquals(List.of("chat", "seq"), journal.keyColumns());
        journal.append(List.of(new ChatMessage(1L, 1L, "user", START, "hello"),
                new ChatMessage(1L, 2L, "assistant", START.plusSeconds(1), "hi"),
                new ChatMessage(2L, 1L, "user", START.plusSeconds(2), "other chat")));
        try {
            journal.append(new ChatMessage(1L, 2L, "user", START, "same chat and number"));
            fail();
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("A record with that chat, seq is already in chat_message"));
        }
        assertEquals("hi", journal.get(PageKey.of(1L, 2L)).orElseThrow().text());
        assertEquals("other chat", journal.get(PageKey.of(2L, 1)).orElseThrow().text());
        assertTrue(journal.update(PageKey.of(1L, 2L), Map.of("text", "hi there")));
        assertEquals("hi there", journal.get(PageKey.of(1L, 2L)).orElseThrow().text());
        assertFalse(journal.update(PageKey.of(9L, 9L), Map.of("text", "nobody")));
        try {
            journal.update(PageKey.of(1L, 2L), Map.of("seq", 5L));
            fail();
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("The key, chat, seq, cannot be changed"));
        }
        for (Object wrong : new Object[] {1L, PageKey.of(1L)}) {
            try {
                journal.get(wrong);
                fail("found by " + wrong);
            }
            catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("[chat, seq]"));
            }
        }
        assertTrue(journal.delete(PageKey.of(1L, 1L)));
        assertFalse(journal.delete(PageKey.of(1L, 1L)));
        assertEquals(2, journal.count());
        assertEquals("search goes by the key, highest first", List.of(2L, 1L),
                journal.search("h", 5).stream().map(h -> h.record().chat()).toList());
    }

    @Test
    public void aTableKeyedOtherwiseIsRefused() {
        Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class)).open();
        try {
            Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
                    .keyColumns("chat", "seq").open();
            fail();
        }
        catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("chat_message is keyed by [chat], not [chat, seq]"));
        }
        Journal<ChatMessage> keyedByChat = Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
                .keyColumns("CHAT").open();
        assertNotNull(keyedByChat);
        for (Runnable wrong : List.<Runnable>of(
                () -> Journal.builder(quack, "x", ColumnarLayout.ofRecord(ChatMessage.class)).keyColumns("nope").open(),
                () -> Journal.builder(quack, "x", ColumnarLayout.ofRecord(ChatMessage.class)).newestBy("nope").open(),
                () -> Journal.builder(quack, "x", ColumnarLayout.ofRecord(ChatMessage.class)).sequence("text").open(),
                () -> Journal.builder(quack, "x", ColumnarLayout.ofRecord(ChatMessage.class)).sequence("seq")
                        .within("seq").open())) {
            try {
                wrong.run();
                fail();
            }
            catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("nope") || e.getMessage().contains("sequence"));
            }
        }
        try {
            Journal.builder(quack, "x", ColumnarLayout.ofRecord(ChatMessage.class)).within("chat");
            fail();
        }
        catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("sequence(...) first"));
        }
    }

    // ---------- numbers within a group ----------

    @Test
    public void aSequenceNumbersEachChatFromOneAndABatchNumbersOnFromItself() {
        Journal<ChatMessage> journal = chatMessages();
        journal.append(said(1, "a", 0));
        journal.append(List.of(said(1, "b", 1), said(2, "c", 2), said(1, "d", 3), said(2, "e", 4)));
        ChatMessage f = journal.appendAndGet(said(1, "f", 5));
        assertEquals(Long.valueOf(4), f.seq());
        assertEquals("f", f.text());
        assertEquals(List.of("1:1 a", "1:2 b", "1:3 d", "1:4 f", "2:1 c", "2:2 e"),
                rows(quack, "SELECT chat || ':' || seq || ' ' || text FROM chat_message ORDER BY chat, seq")
                        .stream().map(r -> (String) r[0]).toList());

        journal.append(new ChatMessage(3L, 10L, "user", START, "given a number"));
        assertEquals("a number given is kept, and the next goes on from it", Long.valueOf(11),
                journal.appendAndGet(said(3, "next", 6)).seq());
        assertEquals(Long.valueOf(1), journal.appendAndGet(new ChatMessage(4L, 0L, "user", START, "0 means next")).seq());
        try {
            journal.append(new ChatMessage(3L, 11L, "user", START, "taken"));
            fail();
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("already in"));
        }
        assertEquals(1, journal.deleteWhere("chat = ? AND seq = ?", 1L, 4L));
        assertEquals("a deleted last number is given again", Long.valueOf(4),
                journal.appendAndGet(said(1, "g", 7)).seq());

        Journal<Small> small = Journal.builder(quack, "small", ColumnarLayout.ofRecord(Small.class))
                .keyColumns("chat", "seq").sequence("seq").within("chat").open();
        assertEquals(1, small.appendAndGet(new Small(1L, 0, "int")).seq());
        assertEquals(2, small.appendAndGet(new Small(1L, 0, "int")).seq());
    }

    @Test
    public void eightThreadsAppendingToFourChatsGetEveryNumberOnce() throws Exception {
        Journal<ChatMessage> journal = chatMessages();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            done.add(pool.submit(() -> {
                for (int i = 0; i < 250; i++) {
                    long chat = (thread + i) % 4;
                    if (i % 10 == 0) {
                        journal.append(List.of(said(chat, "batch " + thread, i), said(chat, "batch " + thread, i)));
                    }
                    else {
                        journal.appendAndGet(said(chat, "one " + thread, i));
                    }
                }
            }));
        }
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();
        List<Object[]> chats = rows(quack, "SELECT chat, count(*), count(DISTINCT seq), min(seq), max(seq)"
                + " FROM chat_message GROUP BY 1 ORDER BY 1");
        assertEquals(4, chats.size());
        long total = 0;
        for (Object[] chat : chats) {
            long n = ((Number) chat[1]).longValue();
            assertEquals("no number twice", n, ((Number) chat[2]).longValue());
            assertEquals(1L, ((Number) chat[3]).longValue());
            assertEquals("no gaps", n, ((Number) chat[4]).longValue());
            total += n;
        }
        assertEquals(8 * (225 + 25 * 2), total);
    }

    @Test
    public void twoJournalsWritingTheSameTableNeverStoreANumberTwice() throws Exception {
        Journal<ChatMessage> one = chatMessages();
        Journal<ChatMessage> two = chatMessages();
        List<Throwable> refused = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        Thread[] writers = new Thread[2];
        for (int w = 0; w < 2; w++) {
            Journal<ChatMessage> journal = w == 0 ? one : two;
            writers[w] = new Thread(() -> {
                for (int i = 0; i < 300; i++) {
                    try {
                        journal.append(said(1, "x", i));
                    }
                    catch (IllegalArgumentException e) {
                        refused.add(e);
                    }
                    catch (RuntimeException e) {
                        unexpected.add(e);
                    }
                }
            });
        }
        for (Thread writer : writers) {
            writer.start();
        }
        for (Thread writer : writers) {
            writer.join();
        }
        Object[] counts = rows(quack, "SELECT count(*), count(DISTINCT seq) FROM chat_message").get(0);
        assertEquals(counts[0], counts[1]);
        assertEquals(600, ((Number) counts[0]).longValue() + refused.size() + unexpected.size());
        assertEquals("a clash found at commit is numbered again", 600, ((Number) counts[0]).longValue());
        System.out.println("two journals on one table: " + counts[0] + " stored, " + refused.size()
                + " refused as a number already taken, " + unexpected.size() + " failed otherwise"
                + (unexpected.isEmpty() ? "" : ": " + unexpected.get(0).getMessage().lines().findFirst().orElse("")));
        assertTrue("only a number already taken is refused: " + unexpected, unexpected.isEmpty());
    }

    @Test
    public void aWriterOutsideTheJournalTakingTheNextNumberMakesItNumberAgain() throws Exception {
        Journal<ChatMessage> journal = chatMessages();
        AtomicBoolean appending = new AtomicBoolean(true);
        int[] outside = new int[2];
        Thread raw = new Thread(() -> {
            while (appending.get()) {
                try {
                    quack.execute("INSERT INTO chat_message SELECT 1, coalesce(max(seq), 0) + 1, 'outside',"
                            + " TIMESTAMP '2026-01-01', 'raw' FROM chat_message WHERE chat = 1");
                    outside[0]++;
                    Thread.sleep(20);
                }
                catch (InterruptedException e) {
                    return;
                }
                catch (RuntimeException e) {
                    outside[1]++;   // its own clash: not the journal's to mend
                }
            }
        });
        raw.start();
        int stored = 0;
        for (int i = 0; i < 300; i++) {
            ChatMessage m = journal.appendAndGet(said(1, "journal", i));
            assertEquals("journal", m.text());
            stored++;
        }
        appending.set(false);
        raw.join();
        System.out.println("with raw SQL taking numbers meanwhile: the journal stored " + stored + " of 300; raw SQL "
                + outside[0] + " stored, " + outside[1] + " clashed");
        assertEquals(300, (long) quack.query("SELECT count(*) FROM chat_message WHERE text = 'journal'").scalar(Long.class));
        Object[] counts = rows(quack, "SELECT count(*), count(DISTINCT seq), max(seq) FROM chat_message").get(0);
        assertEquals(counts[0], counts[1]);
        assertEquals(counts[0], counts[2]);
    }

    @Test
    public void numberingInAMillionMessageJournalTakesAboutAMillisecond() {
        Journal<ChatMessage> journal = chatMessages();
        List<ChatMessage> all = new ArrayList<>(1_000_000);
        for (int i = 0; i < 1_000_000; i++) {
            all.add(new ChatMessage((long) i / 1000, (long) i % 1000 + 1, "user", START.plusSeconds(i), "m"));
        }
        journal.append(all);
        List<Double> took = new ArrayList<>();
        Random random = new Random(3);
        for (int i = 0; i < 300; i++) {
            long chat = random.nextInt(1000);
            long started = System.nanoTime();
            journal.appendAndGet(said(chat, "new", i));
            took.add((System.nanoTime() - started) / 1e6);
        }
        Collections.sort(took);
        double median = took.get(took.size() / 2);
        System.out.printf("appendAndGet into one of 1,000 chats of a million messages: median %.2f ms, 99th %.2f ms%n",
                median, took.get((int) (took.size() * 0.99)));
        assertTrue("median " + median, median < 20);
    }

    @Test
    public void searchGoesNewestFirstByTheColumnsNamed() {
        Journal<ChatMessage> journal = chatMessages();
        journal.append(List.of(
                new ChatMessage(9L, 1L, "user", START.plusSeconds(1), "word oldest"),
                new ChatMessage(1L, 1L, "user", START.plusSeconds(3), "word newest"),
                new ChatMessage(5L, 1L, "user", START.plusSeconds(2), "word middle")));
        assertEquals(List.of("word newest", "word middle", "word oldest"),
                journal.search("word", 10).stream().map(h -> h.record().text()).toList());
        Journal<ChatMessage> byKey = Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
                .keyColumns("chat", "seq").searchable("text").open();
        assertEquals(List.of("word oldest", "word middle", "word newest"),
                byKey.search("word", 10).stream().map(h -> h.record().text()).toList());
    }
}
