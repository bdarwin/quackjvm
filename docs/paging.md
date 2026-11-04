# Paging and journals

Two pieces for an application that keeps its own records and scrolls through them:
- **`Pages`** reads any query a page at a time, for infinite scrolling.
- **`Journal`** is an append-only table for an application's own records: written once, possibly
  changed while still being written, then paged and searched.

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
```

Totals, such as usage per model per day, are plain SQL over the table.

### Writing

- **`append`** adds a record. A key that is already there is refused: a journal adds records, and
  `update` changes one. `append(records)` writes a batch in one transaction: all of it, or none if
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
