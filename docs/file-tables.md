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

## Hardening

The cache database is opened with no file, network or extension access, and its settings are
locked. Queries go through [`GuardedQuery`](guarding-queries.md).

Measured on DuckDB 2.0: SQL can then read none of the cache folder's other files, so not the other
caches. It can read only this database's own file, its write-ahead log and its temp folder, which
hold nothing the table doesn't.

## What it doesn't do yet

A file table is queried through its own `query`, as `t` (or the name given to `name(...)`). It
can't yet be registered in a [`QuerySession`](query-sessions.md) to be joined with the session's
other tables. The two would have to share a DuckDB database, and the session's hardening forbids
attaching the cache file.
