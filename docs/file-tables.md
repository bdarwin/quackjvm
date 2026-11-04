# File tables

A `FileTable` turns a big file plus the application's own parser into a SQL table. The table is
kept on disk between runs and stays up to date as the file grows. Two kinds of file need this:

- **Logs:** a few columns, millions of lines, growing while you watch, and rotated now and then.
- **Wide CSV or TSV files:** thousands of columns, of which a query reads a handful.

```java
FileTable log = FileTables.builder(path, columns, LineSource.of(path), parser)
        .cacheDir(cacheFolder)
        .roomLimit(5L << 30)        // the most the cache folder may hold
        .version("viewer-3")        // change it when the parser changes
        .open();

log.update(signal, done -> bar.set(done));    // the lines written since, or all of them
GuardedResult r = log.query("SELECT level, count(*) FROM t GROUP BY 1", QueryPolicy.readOnly(), signal);
```

The application provides two things:
- **A `RowSource`**, which counts rows, reads them raw, and says when the file was replaced.
  `LineSource` is one for text files: a line per row, with an optional header to skip.
- **A `RowParser`**, which splits a raw row into typed cells, one per `ColumnDef`.

Both are called from several threads at once.

## Measured

On a 10-core Mac, while another program used 4–5 cores:

| | |
|---|---|
| First load of a 276 MB log, 3 million lines, 5 columns, 8 threads | 2.9–3.1 s |
| The same opened again after a restart, plus the first query | 24–26 ms, with no rows read |
| Its cache file | 43 MB |
| A 6,000-column, 5,000-row CSV: the first query over 2 columns (stores them) | 138–148 ms |
| The same query again | 5 ms |

The rows reach DuckDB through a Java table function, one per loading thread, into in-memory
staging tables. One `INSERT … ORDER BY` then moves each batch into the cache. DuckDB's appender
took 8.6 s for the same 3 million rows, against 1.2 s for the staging, because it costs more per
value and gains little from more threads.

## Growing, and being replaced

`update` appends the rows from the last one stored up to `rowCount()`. Each batch of blocks is
stored in one transaction, together with the count of rows stored. As a result:

- **A query running during an update sees whole batches only.** A test queries throughout a
  200,000-row load and only ever sees multiples of the batch size.
- **A stopped update keeps its batches.** Stopped through its `CancelSignal`, an update throws
  `QueryCancelled` within 100 ms (tested), and the next update carries on from the last stored row.
- **A process that dies mid-update leaves whole batches.** A test kills a loading JVM with
  `kill -9` after three batches. The reopened table holds exactly the batches committed, and the
  next update loads the rest without repeating any row.

The file counts as replaced, and the table is emptied and loaded again, when:
- `identity()` changed since the last update;
- the file has fewer rows than are stored; or
- its first bytes (up to 64 KB) no longer match the fingerprint taken when they were stored.

Across a restart only the last two can be checked. A test replaces the file with a bigger one
while the table is closed, and the fingerprint catches it.

## Wide files: lazy columns

```java
FileTable wide = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser)
        .cacheDir(cacheFolder).lazyColumns(true).maxNewColumnsPerQuery(64).open();
wide.update();                                                     // stores the rows, no columns yet
wide.query("SELECT host, avg(cpu_0001) FROM t GROUP BY 1", policy, signal);   // stores host and cpu_0001
wide.whyNot("SELECT avg(COLUMNS('^cpu_[0-9]+$')) FROM t");         // "reads 1500 columns ... more than the 64"
```

With lazy columns, `update` stores only the columns already asked for. `query` first finds which
columns the query reads, using [`QueryColumns`](catalog.md#which-columns-a-query-reads) to plan it
over an empty table with the same columns. It then stores any of those it lacks, by reading the file
again for just those columns, in one transaction. Only then does it run the query.

- **Stored columns stay stored**, across restarts too, and grow with the file.
- **A parser can skip the cells it isn't asked for**, through
  `parse(raw, rowNo, cells, wanted)`.
- **Refusals:** a query whose columns can't be told (a `PIVOT`, say) is refused with the reason, and
  so is one that would add more than `maxNewColumnsPerQuery` columns. `whyNot` gives that reason
  without running anything.
- **Stored ahead, with progress:** `store(columns, signal, progress)` stores the ones not stored yet,
  in one pass, before any query reads them. A query would store them too, but a big file's read
  then shows nothing for minutes; this one reports the share read and stops on the signal, storing
  none of them. It returns how many it added, 0 when all were there.

```java
int added = wide.store(List.of("host", "cpu_0001"), signal, share -> bar.setValue((int) (share * 100)));
```

## Rows that aren't rows

Some lines of a file are not rows: a CSV's header, a blank line, or a line that goes on with a
quoted value begun on the line before. The parser leaves them out with `isRow`:

```java
RowParser parser = new RowParser() {
    @Override
    public boolean isRow(String raw, long rowNo) {
        return rowNo > 0 && !raw.isBlank() && startsARecord(raw);   // the header, blank lines and value lines: no
    }

    @Override
    public void parse(String raw, long rowNo, Object[] cells) {
        cells[0] = rowNo + 1;                                         // the line number, to show
        ...
    }
};
```

- **A row left out is not stored.** `count(*)` counts the real rows, and no query has to filter out
  rows of nulls. `parse` is not called for it.
- **Row numbers stay the source's.** `rowNo`, and the table's own `quackjvm_row`, are the row's
  number in the file, gaps and all. A column filled from `rowNo` gives the line numbers to show.
- **It still counts as read.** `rowsLoaded()` and `Updated.rows()` count every source row, so
  growing and resuming work as before. `rowsStored()` counts the rows the table holds.
- **Decide from `raw` and `rowNo` alone.** Never from `wanted`, and never from rows seen before:
  each lazy column is stored by reading the file again, and every pass must leave out the same rows.
  When a pass keeps a different number of rows than were stored, the column is not stored and the
  query fails with that reason.

Tested on a CSV with a header, blank lines and values on two lines: cached, in memory, reopened,
grown, replaced, and in a query session; and with lazy columns added later, which line up row by
row.

## Columns the application computes

A column can be worked out from the whole raw row, by the application, and added to the open table
when it is needed. Say a person picks `cpu_*`, 1,500 of 6,000 columns, and wants their total per
row. Storing the 1,500 columns would take about 300 times the room of storing the total.

```java
wide.column(new ColumnDef("cpu_sum", Double.class), (raw, rowNo) -> sumOfCpuCells(raw));
wide.query("SELECT host, avg(cpu_sum) FROM t GROUP BY 1", policy, signal);   // stores cpu_sum, then runs
wide.update();                                                               // fills cpu_sum for new rows
```

- **Only with lazy columns.** On a table without `lazyColumns(true)`, `column` throws
  `IllegalStateException`.
- **Stored when a query first reads it**, as a lazy column is: by reading the file once more, for
  just that column. A pass for computed columns alone calls `isRow` but not `parse`. From then on
  `update` fills it for new rows.
- **Planned like the others.** `whyNot` and `query` know it, and so does a query session it is
  registered in, even when the column is added after registering.
- **Not part of the cache's name.** The cache file is the same with or without computed columns.
- **Dropped on reopen.** When the cache is opened again, the stored values of computed columns are
  dropped: the function that made them may have changed, and the table can't tell. Add the column
  again, and the next query reading it computes it again. Until then a query naming it is refused,
  as for any unknown column. The parser's lazy columns stay stored as before.
- **Kept with a version.** Added with a version, `column(def, function, "pool-3")`, its values are
  kept when the cache is opened again, out of sight. Added again with the same version and type,
  they are used as they are, with no read of the file: a column worked out from a whole big file
  once is not worked out again at every start. They are dropped, and worked out again when a query
  reads the column, if it is added with another version or type, if rows are stored before it is
  added again (they would lack its values), or if the file was replaced. Change the version
  whenever the function changes what it gives.

```java
table.column(new ColumnDef("cpu_total", Double.class), CPU_TOTAL, "cpu-total-1");  // after a restart: as kept
```
- **Adding one of the same name again replaces it.** Its stored values are dropped, and the next
  query that reads it stores it again. The new one may have another type.
- **Its name can't be one of the parser's columns**, nor `quackjvm_row`.
- **Called on the loading threads**, several at once, like the parser, so it must keep no state
  between rows. A test computes on 8 threads, in blocks of 37 rows, and on 1 thread, and gets the
  same values as plain Java. It is called only for rows the parser keeps.
- **Cancelling.** The loading threads ask the signal at each block, and at most every millisecond
  within one. Storing a column that takes 1 ms a row stopped 3–5 ms after its cancel, and nothing
  was stored. This holds for a slow parser too.

## The cache folder

Each source file gets one DuckDB file, named from:
- the file's path;
- its columns;
- the application's `version`;
- whether its columns are lazy.

Each cache also has a small `.used` file, which records when it was last used.

- **Another table already has it open,** in this JVM or another process: the next copy is used
  (`.2`, `.3`, `.4`), so nobody gets `DatabaseInUse`. Past those, the table is kept in memory.
- **`inMemoryBelow(bytes)`** keeps small files out of the cache folder altogether.
- **`roomLimit`:** after opening and after each update, the caches used longest ago are removed
  until the folder fits.
- **`FileTables.size(folder)`** says how much room the caches take.
- **`FileTables.clear(folder, keep)`** removes all of them except the caches of the source paths
  in `keep`.

Only this library's own files (named `qft-…`) are counted or removed, and never one in use. A cache
is in use if a table in this JVM has it open, or another process holds DuckDB's lock on it. The
tests for this include one that holds a cache open in a second JVM.

On macOS and Linux, DuckDB reads a backslash in a database's path as a separator, so a cache
folder whose path contains one is refused with that reason. The source file itself may be anywhere.

## Java functions

The application can give a file table's queries Java functions of its own: a time put in a group
and labelled in the person's time zone, say, or a filter made from what they typed.

```java
FileTable table = FileTables.builder(csv, columns, LineSource.of(csv).skip(1), parser)
        .cacheDir(cache).lazyColumns(true)
        .function("label_time", String.class, Long.class, Integer.class, (ms, group) -> labels.of(ms, group, zone))
        .function("keep_value", Boolean.class, Long.class, String.class, (id, v) -> tests.get(id).test(v))
        .open();

table.query("SELECT label_time(time, 1) AS hour, count(*) FROM t GROUP BY 1", policy, signal);
table.function("label_time", String.class, Long.class, Integer.class, newFunction);   // the zone changed
```

They take one, two or three arguments, as [`Udfs.register`](udfs.md) does, and a row with a null
argument is null without calling them.

- **Only the application adds them.** SQL can't: `CREATE MACRO` and `CREATE FUNCTION` are refused,
  and a query calling one still reads no file, URL or other cache. A test checks this before and
  after the file is rotated and the table rebuilt.
- **Lazy columns plan with them.** The functions are registered on the empty database lazy columns
  are planned in, too (`QueryColumns.register`). So over a 6,000-column table,
  `SELECT label_time(time, 1), count(*) FROM t GROUP BY 1` stores only `time`, and a later query
  that also reads `v_0042` stores that one more. The test checks the labels against Java's, over a
  change to summer time.
- **Copies and memory.** They are registered on whichever database the table opens, so a copy
  (`.2`...), a table in memory, and a cache opened again all have them.
- **Replacing one** while a query runs: that query keeps the one it started with, and the next
  queries get the new one. Measured with a query mid-way through 5,000 rows.
- **Speed:** registering took a median of 0.10 ms on a 6,000-column table (0.4 ms the slowest of
  50). The first registration in a JVM took about 32 ms, loading classes. A call costs what a
  [`Udfs.register`](udfs.md#what-it-costs) function costs: the check the wrapper adds (below)
  measured 17.5–17.6 ns a row against 17.5–19.0 without it, over 2 million rows on one thread.
- **Cancelling.** DuckDB checks for a cancel between the chunks of 2,048 rows it hands a function.
  So a query in a function taking 1 ms a row went on for 2.7 s after its cancel. Each function is
  therefore wrapped: before each call it reads a flag that a watcher sets within a millisecond of a
  running query's signal being cancelled. The same query stopped 1–3 ms after its cancel, and with
  a signal polled every 20 ms, 10–19 ms after the flag was set.
  - DuckDB can't say which query a call belongs to, so another query running the functions at that
    moment stops too. `query` runs it again once the cancelled one has gone. A test runs one beside
    a cancelled one and gets its full answer.

**Calling a test once per distinct value.** To filter on a Java test that is expensive, ask it once
per distinct value, and write `OFFSET 0`:

```sql
SELECT * FROM t WHERE host IN (SELECT v FROM (SELECT DISTINCT host AS v FROM t OFFSET 0) WHERE keep_value(7, v))
```

Without `OFFSET 0`, DuckDB 2.0 moves the test below the `DISTINCT` and asks it once per row. Over a
million rows with seven distinct values, that was 1,000,000 calls and 96 ms, against 7 calls and
7.5 ms. Marking the function volatile doesn't stop it. A `SEMI JOIN` over a `GROUP BY` subquery, or
`list_filter` over `list(DISTINCT host)`, also gave 7 calls.

## Hardening

The cache database is opened with no file, network or extension access, and its settings are
locked. Queries go through [`GuardedQuery`](guarding-queries.md).

Measured on DuckDB 2.0: SQL can then read none of the cache folder's other files, so not the other
caches. It can read only this database's own file, its write-ahead log and its temp folder, which
hold nothing the table doesn't.

## In a query session

A file table can be registered in a [`QuerySession`](query-sessions.md#file-tables), to be joined
with the session's other tables and read through result windows. Its cache is then attached to the
session's database, and the table is queried there instead of through its own `query`.
