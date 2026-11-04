# Paging and journals

Two pieces for an application that keeps its own records and scrolls through them:
- **`Pages`** reads any query a page at a time, for infinite scrolling.
- **`Journal`** is a table for an application's own records: written once, possibly changed while
  still being written, then paged, searched, and deleted when the person asks.

For scrolling through the result of a query a user typed, see
[result windows](query-sessions.md#result-windows).

## Pages

```java
Pages pages = Pages.of(quack, "SELECT * FROM message WHERE session = ?", sessionId)
        .orderBy("ts", "id")          // together unique, never null
        .size(50);

Page page = pages.last();                         // the newest 50
Page older = pages.before(page.firstKey());       // scrolling up
Page newer = pages.after(page.lastKey());         // scrolling down
Page hit = pages.around(PageKey.of(ts, id), 25);  // a search hit, with 25 rows each side
long rows = pages.count();                        // for the scrollbar; remembered, recount() to refresh
long at = pages.position(key);                    // where a row is, for the scrollbar's thumb
List<Message> messages = page.as(layout);         // the rows as objects
```

Each page is found from the row next to it (a keyset), not by counting rows from the start.

- **A deep page costs the same as the first.** There is no `OFFSET` to scan through.
- **Rows added while someone scrolls are never repeated or skipped.** A test pages backwards
  through 100,000 rows while another thread appends. Every row is read exactly once, and a forward
  walk reaches the new rows in order.
- **The order columns** must, taken together, tell every row apart: a time alone isn't enough when
  two rows can share one, so add an id. None of them may be null, and the query must return them.
  The query's own `ORDER BY` is overridden.
- **Jumping to a scrollbar position:** `at(position)` reads the page that starts at a row number.
  That one does count rows from the start, because a position is a count.

### How fast

A page is one query: the rows on the far side of the key, in order, with a `LIMIT`. But DuckDB has
to rank every row on that side to find the nearest ones. Half way down 100,000 rows that is 54,000
rows, and it took about 6 ms.

So `Pages` remembers how far the last page reached in the first order column. It then searches only
within four pages' reach of the key, so DuckDB ranks a few hundred rows. If that range holds fewer
rows than the page needs, the search runs again without the bound, so the answer is always the
same. A randomised test checks this against the full sorted result: rows in clusters with ties and
gaps a thousand times wider, every kind of column the bound handles (and one it doesn't), and four
page sizes, in both directions.

Measured on 100,000 rows, with three rows to every timestamp, while another program was using 4–5
cores:

| | Median | 99th percentile |
|---|---|---|
| `before()`, 2,000 pages in a row | 0.98–1.20 ms | 1.32–1.75 ms |
| A journal's pages of one session, 33,333 of 100,000 rows | 1.68–1.79 ms | 2.26–2.41 ms |
| The same `before()` without the bound | about 6 ms | |

On a 2-core GitHub runner, `before()` gave a median of 1.74 ms and a 99th percentile of 2.09 ms.
But while another thread appended to the same database file, a few journal pages took over 100 ms
(median 2.6 ms), most likely waiting on those commits to disk.

## Journal

```java
Journal<Message> messages = Journal.builder(quack, "message", ColumnarLayout.ofRecord(Message.class))
        .index("session", "ts")
        .searchable("text", "toolCalls")
        .migrate(2, "ALTER TABLE message ADD COLUMN cost DOUBLE")   // added by a later app version
        .open();

messages.append(message);                                 // keyed by the layout's first column
messages.update(id, Map.of("text", soFar, "tokensOut", n));   // a message growing as it streams in
messages.get(id);
List<Journal.Hit<Message>> hits = messages.search("connection refused", 20);   // newest first
Pages page = messages.pages("session = ?", sessionId).orderBy("ts", "id").size(50);
messages.delete(id);                                      // one the person removed
messages.deleteWhere("session = ?", sessionId);           // a whole session
```

Totals, such as usage per model per day, are plain SQL over the table.

### Writing

- **`append`** adds a record. A key that is already there is refused: `update` changes a record. `append(records)` writes a batch in one transaction: all of it, or none if
  one record is refused.
- **`update(key, changes)`** changes some columns of one record, but never its key. It returns
  false if there is no record with that key.
- **Speed:** each append or update commits on its own, in about 0.4 ms (measured on a busy
  machine).
- **One writer, many readers.** Writes take turns. Readers need no turn, because DuckDB gives each
  query the rows committed when it began. In a test, a reader polling a message while 300 updates
  grew it always saw the text and its count from the same update.
- **A process killed mid-write leaves whole records.** A test kills a writing process with
  `kill -9` three times and reopens the file each time. The file opens, and every record has its
  text and its count from the same update. To check that the test can fail, the writer was changed
  to set the two in separate updates: the test then found a broken record every time.

### Keys and numbers

The key is the layout's first column, unless `keyColumns` names others. A key of several columns is
passed to `get`, `update` and `delete` as a `PageKey`:

```java
Journal<ChatMessage> messages = Journal.builder(quack, "chat_message", ColumnarLayout.ofRecord(ChatMessage.class))
        .keyColumns("chat", "seq")
        .sequence("seq").within("chat")   // 1, 2, 3... in each chat
        .newestBy("time")                 // search gives the newest first by this
        .searchable("text")
        .open();

ChatMessage stored = messages.appendAndGet(new ChatMessage(chatId, null, "user", now, text));
stored.seq();                                             // its number in the chat
messages.update(PageKey.of(chatId, stored.seq()), Map.of("text", more));
```

- **A sequence** is filled when a record is appended with it null or 0: one more than the highest
  in its group. Numbers given are kept, and the next one goes on from them. A batch numbers on from
  itself. Deleting a chat's last message gives its number out again.
- **Writers take turns over the table**, not just over one journal object: two journals over the
  same table of the same `Quack` share the turn. With eight threads appending to four chats, every
  chat was numbered 1 to N with no gap and no number twice. With two journal objects appending to
  one chat, all 600 were stored.
- **A writer outside the journal** (plain SQL taking the next number) can still take a number
  first. The append then finds the clash, numbers again and retries, up to 10 times. In a test, the
  journal stored all 300 of its messages while plain SQL inserted the next number every 20 ms.
  Without the retry, the same test fails.
- **Speed:** numbering costs one query for the highest number in the group. `appendAndGet` into one
  of 1,000 chats of a million messages took a median of 1.3 ms (99th percentile 1.5–2.4 ms).
- **Search order:** `search` gives the newest first by the `newestBy` columns, else by the key,
  highest first. Without `newestBy`, a key such as (chat, seq) sorts by chat first.
- **An existing table keyed otherwise** is refused on `open`, naming both keys. A migration can
  change the key with `ALTER TABLE`.

### Deleting

`delete(key)` removes one record and says whether it was there. `deleteWhere(where, params)` takes
a condition as `pages` does and returns how many it removed. It refuses an empty condition: to
remove everything, write `deleteWhere("true")`.

- **All or none.** Each call takes the writer's turn and commits once. A test appends a chat of
  50,000 messages, deletes it (21–22 ms) and repeats, and kills the process with `kill -9` six
  times while a delete runs. After every kill, the reopened file held each chat whole: four times
  the delete had not committed, twice it had.
- **Paging stays right.** A test pages back through one chat of 5,000 messages while another thread
  deletes parts of the other 19 chats: every message was read exactly once. Deleting the chat
  being paged gives empty pages, with no exception, whether it goes all at once or a row at a time.
- **Counting and positions.** `Journal.count()` is always current. `Pages.count()` is remembered,
  so call `recount()` after deleting. `position(key)` of a deleted key gives where it would be: the
  number of rows before it. `around(key, n)` of a deleted key gives the rows each side of where it
  was.
- **Search** no longer finds deleted records.

**Disk space.** DuckDB doesn't give a deleted row's space back to the file: deleting half of
400,000 rows made a file grow from 17 to 24 MB at the next checkpoint. It does reuse the space for
rows written later. In a test with 40,000 messages live, adding 40 chats and deleting 40 others
each round for 20 rounds, the file stayed between 1.8 and 3.7 MB.

To shrink a file after deleting most of it, copy the database into a new file, then open the new one:

```java
String name = quack.query("SELECT current_database()").scalar(String.class);
quack.execute("ATTACH 'app-new.duckdb' AS fresh");
quack.execute("COPY FROM DATABASE " + name + " TO fresh");
quack.execute("DETACH fresh");
// close, swap the files, reopen
```

With 100,000 of 400,000 entries left, that went from 10.5 to 5.8 MB in 65–88 ms. The copy keeps
the keys, indexes, comments and the journal's version: a test reopens the journal on it, finds,
searches and appends, and a duplicate key is still refused. Rewriting the table in place did not
work as well: where the copy lands depends on where the free blocks are, and a rewrite of an
already compact file doubled it. A hardened database refuses `ATTACH` to a file, so do this
before hardening, or on the application's own connection.

### Versions

The schema version is kept in a small table, `quackjvm_journal`.

- **A new table** is made with every column the layout has, at the latest version the migrations
  name, without running any of them.
- **An existing table** is moved up one migration at a time. Each migration runs in a transaction
  together with the version it reaches, so a failed one leaves the table and its version as they
  were, including any earlier statements of that migration.
- **A table with no version recorded** (made before it had migrations) is at version 0.
- **A table from a later version** of the application is refused, saying so.
- **Column order:** after the migrations, the table must have the layout's columns in the layout's
  order. A column added by `ALTER TABLE ... ADD COLUMN` goes last, so put it last in the layout too.
  Rows written before it existed read it as null.

### Searching

`search` finds records where every word appears, in any case, in one of the searchable columns.
`%` and `_` count as plain characters. Each hit says which column matched and gives the text around
the first word.

It uses `ILIKE`, not DuckDB's full-text extension:
- The extension isn't in the JDBC driver, and hardening refuses to download it.
- Its index is built once and doesn't follow new rows, which suits a journal badly.

A search is a scan: 12 ms over 100,000 records and 17 ms over a million, measured on a busy machine,
whether or not anything matched.
