# Changelog

## 1.6.1 - 2026-11-05

### Changed
- **`db.query` runs a `SELECT` with no parameters unprepared, and a large ASOF result reads 2x
  faster without an `ORDER BY`.** DuckDB runs some queries far slower prepared; a `SELECT` (or
  `FROM ...`) needs no preparing to know it returns rows, so `Rows` and `SqlQuery` now run it on a
  plain statement. An ASOF join of 100,000 probes against 1M readings, every row read: 119 ms
  through `forEachRow` against 261 ms in 1.5.0, and 41 ms through `forEachRecord` (Arrow) against
  187 ms. A query with parameters, and anything that does not start with `SELECT` or `FROM`, is
  prepared and checked as before. Nothing is planned twice, so a Java function of constants is
  still called once.
  At full size (1M probes, 10M readings, 1,000 devices): 2,574 ms to 1,116 through `forEachRow`,
  1,776 to 332 through `forEachRecord`; "latest per device" at 100,000 devices 220 to 160 ms; and
  `db.query("SELECT 1")` 147 to 76 µs.

### Added
- **`benchmarks/questdb/`**, the comparison with QuestDB, CQEngine on the heap and Java arrays
  behind `docs/questdb.md`, outside the build. `benchmarks/questdb/run.sh` builds this checkout (or
  fetches a release from Maven Central with `-v`), starts and stops QuestDB, waits for a quiet
  machine, and writes the results and the tables. Rerun before each release; see `RELEASING.md`.

### Docs
- **Compared with QuestDB** (`docs/questdb.md`), measured again with 1.5.0 and this change: where
  the time goes returning rows to Java (the JDBC driver's strings, and DuckDB's prepared path), and
  forms of "latest per device", with a table sorted by device 7x faster at 100,000 devices.
- **Tuning: a large result, read it as records.**

## 1.6.0 - 2026-11-05

### Added
- **A `FileTable` parser can leave rows out**: `RowParser.isRow(raw, rowNo)`, true unless
  overridden. A CSV's header, blank lines and the lines inside a quoted value that spans lines are
  then not stored, so `count(*)` counts the real rows. Rows kept keep their source row numbers;
  `rowsLoaded()` still counts every source row read, so growing and resuming work as before, and
  the new `rowsStored()` counts the rows held. `isRow` must decide from the raw row and its number
  alone: a lazy column whose pass keeps other rows is refused with that reason.
- **Columns the application computes**, added to an open table with lazy columns:
  `FileTable.column(ColumnDef, RowFunction)`, where `RowFunction` works out a value from the whole
  raw row - a total over 1,500 columns, say. Stored by the first query that reads it, with one pass
  over the file that calls `isRow` but not `parse`, and filled by `update` from then on. Planned
  by the table and by a `QuerySession` it is registered in. The cache file's name doesn't change.
  Adding one of the same name again replaces it and drops its stored values; reopening the cache
  drops them too, until the column is added again. `computedColumns()` lists them.
- **Computed columns kept from one start to the next**, with a version: `FileTable.column(def,
  function, version)`. Added again with the same version and type, the values stored before are used
  as they are; another version, rows stored first, or a replaced file drops them. Without a version
  they're dropped at reopening, as before.
- `FileTable.unstore(columns)` lets stored lazy columns go; they're stored again when read.
- **Lazy columns stored ahead, with progress:** `FileTable.store(columns, signal, progress)` stores
  the ones not stored yet in one pass, before a query reads them, telling the share read and stopping
  on the signal (then none is stored). For an application that shows a bar while a big file is read.

### Changed
- **Query examples, closer to what people ask** (found moving Logsift's own examples onto quackjvm):
  - Rows per hour (and per minute for a family) leave out rows without a time, which made a group of
    their own, and show the measure's mean beside the count.
  - Lines per level leave out lines with no level, such as a stack trace's.
  - The range is of a number that measures something; a column that counts rows (`line`, `id`...)
    no longer gets one.
  - A family of numbered columns gets the rows with its highest value, rather than only the value,
    and each column's mean and highest, highest mean first, rather than the raw `UNPIVOT` rows.
    Text families keep the raw rows.
  - Two tables that each number their rows (`line`, `row`, `index`...) aren't offered a join on that
    number: a row's place in its own table says nothing about another's. They still join on what
    they share, an id say.
- Loading threads ask the cancel signal at most every millisecond within a block, not only between
  blocks, so a slow parser or computed column stops 3-5 ms after a cancel.
- `QuerySession.describe` of a file table gives the rows stored, not the source rows read.

### Fixed
- A guarded query with no cap on its rows (`maxRows(Long.MAX_VALUE)`) failed: the cap plus one went
  past the largest number, and DuckDB refused `LIMIT -9223372036854775808`.
- A `FileTable` with lazy columns listed its stored columns in `SELECT *` in the order they had been
  stored, not the table's own, so the same query gave its columns in another order depending on
  what had been read before. They are listed in the table's order now (computed ones last).
- A lazy column first read after the table was opened again, before any `update`, failed with
  "Rows 0 to ... are not all there yet": the source had not looked at its file yet.

## 1.5.0 - 2026-11-04

### Added
- **More query examples** (#19). `QueryExamples` adds:
  - `SUMMARIZE`, percentiles and the highest rows of a number, and the highest value per group.
  - For a family of numbered columns: per group, per minute, a total per row, the highest value,
    and `UNPIVOT`.
  - Finding a column by name in a wide table, and the first rows of a table it joins to.
  - Examples for logs (a `level` and a `message`): lines per level, errors per minute, the most
    frequent errors with numbers blanked, errors and warnings by logger, and durations in messages.
- `Catalog.examples(table, distinctValues)` and `QuerySession.examples(name, distinctValues)` choose
  a group column with few values; `Profile.distinctValues()` gives the counts, and
  `QuerySession.profile(name)` profiles a registered table.

- **`Journal` deletes** (#17): `delete(key)` and `deleteWhere(where, params)`, under the writer's
  turn and in one commit. `deleteWhere` refuses an empty condition; `deleteWhere("true")` removes
  everything. A delete killed with `kill -9` is all there or not at all.
- **`Journal` keys of several columns**: `keyColumns("chat", "seq")`, with `get`, `update` and
  `delete` taking a `PageKey`. **Numbers within a group**: `sequence("seq").within("chat")` fills
  1, 2, 3... under the writer's turn, and `appendAndGet` returns the record as stored.
  **`newestBy("time")`** sets the order `search` gives.
- **Java functions in `FileTable` queries** (#18): `Builder.function(...)` and `FileTable.function(...)`
  for one to three arguments. They are registered on the cache database, its copy or memory, and the
  lazy-column planner; replacing one leaves a running query with the old one. A cancelled query
  stops within a few ms even inside a slow function (2.7 s with DuckDB alone); a query stopped
  beside it is run again.
- **File tables in query sessions** (#16): `QuerySession.register(name, fileTable)` and
  `register(fileTable)`. The cache is attached to the session's database, letting in exactly its
  database, WAL and checkpoint files, so it joins the other tables, and `window` reads it. Lazy
  columns a query reads are stored first; the table's Java functions work, and a cancel stops
  them. Unregistered, or when the session closes, the table goes back to a database of its own. A
  table closed while registered makes a query naming it say so.
- `Hardening.Options.allowedFiles(...)`: single files SQL may use (DuckDB's `allowed_paths`).
- `QueryColumns.register(...)` registers a function on the planning database, so queries calling it
  can be planned.
- The UDF and file-table docs show that DuckDB 2.0 asks a filter in `SELECT DISTINCT ...` once per
  row unless the subquery has `OFFSET 0`.
- The paging docs say how deleted rows' space is reused, and how to shrink a file by copying the
  database into a new one.

### Changed
- `FileTable.update` takes the table's lock a batch at a time, so a query adding lazy columns goes
  between two batches instead of waiting for the whole update.
- Journals over the same table of the same `Quack` share one writer's turn, instead of one per
  journal object.
- The group-by example now goes by a column's name (`level`, `host`, `status`...) and leaves out free
  text such as `message`. Percentiles and means skip numbers that count rows (`line`, `id`, `..._id`).

### Fixed
- A quote in the name of a family of numbered columns broke its `COLUMNS('...')` examples.

## 1.4.0 - 2026-11-04

### Added
- **`QuerySession`** (#14): one hardened, in-memory database where an application registers
  tables under names and drops them at runtime. A table can be a file (with the reader chosen by
  extension), any SQL, or live Java objects.
  - `alias("t", name)` gives the table on screen a fixed name.
  - A file from a folder SQL can't read yet reopens the session with that folder allowed, keeping
    every table and alias (32–37 ms with 50 files). If a table can't be remade, the session stays as
    it was.
  - `run` is safe from several threads, and changes wait for the queries running.
  - `QuerySession.tableName` turns `Readings 2026-Q4.tsv` into `readings_2026_q4`, and never gives out
    `t`, `log` or a name that needs quotes.
- **`FileTable`** (#13): a big file plus the application's parser, turned into a SQL table that is
  kept on disk and up to date as the file grows.
  - `update` appends what was written since. It reloads the file when it was replaced, judged by
    `identity()`, a shrunk row count, or a fingerprint of the first 64 KB.
  - Loading runs on parallel Java table functions into staging tables. A 276 MB, 3-million-line log
    loads in 2.9–3.1 s, and after a restart it opens and answers a query in 24–26 ms with no rows
    read.
  - Each batch is stored in one transaction with the row count, so queries see whole batches, and a
    stopped update resumes from the last one stored.
  - Lazy columns for wide files store only the columns a query reads, planned with `QueryColumns`:
    138–148 ms for the first query over 2 of 6,000 columns, then 5 ms.
  - Cache copies keep a second process or table from getting `DatabaseInUse`. There is a room limit
    that never removes a cache in use, plus `FileTables.size`/`clear`/`trim`.
  - `LineSource` reads a text file as rows.
- **`CancelSignal.poll()`**: asks a signal's supplier now, for work of one's own that checks between
  steps.
- **`Pages`** (#15): reads any query a page at a time, for infinite scrolling: `first`, `last`,
  `before(key)`, `after(key)`, `around(key, n)`, `at(position)`, `count`, `position`.
  - Each page is found from the row next to it, so a deep page costs what the first does.
  - Rows appended while someone scrolls are never repeated or skipped (tested).
  - A search bounded by the last page's reach brings a page back from about 6 ms to about 1 ms on
    100,000 rows. If the bound leaves too few rows, the page is read again without it.
- **`QuerySession.window(sql, signal)`** and **`ResultWindow`** (#15): a query's whole result, kept
  in an attached in-memory database. Reading 100 rows at row 1,500,000 of 2 million takes 0.7 ms,
  and a re-sort takes 98 ms; the query never reruns. `GuardedQuery.store` checks a query and stores
  its result in a table.
- **`Journal`** (#15): an append-only table for an application's own records.
  - `append`, `update(key, changes)` for a record still streaming in, `get`, `pages`, and an `ILIKE`
    `search` with snippets.
  - Schema versions with `migrate(version, ddl)`: each step runs in a transaction, and a new table
    starts at the latest version.
  - A test kills the writing process with `kill -9`: every record is whole when the file is reopened.
- **`CancelSignal.from(BooleanSupplier)`** and `CancelSignal.polling(stop, interval)` (#14): stop a
  guarded query from a flag the application already has.
  - A flag that is already set stops the run before anything is parsed.
  - While the query runs, a daemon thread polls the flag every 50 ms. Measured: the query stopped
    0–59 ms after the flag turned.
- **`Catalog.examples(table)`** and `QuerySession.examples(name)` (#14): ready-made queries written
  from a table's columns. Families of numbered columns are queried with `COLUMNS('^cpu_[0-9]+$')`,
  and there are joins to tables that share a column. Every example runs as written.

### Fixed
- **`Hardening.Options.properties()` lets in folders whose path holds a backslash** (#12). DuckDB
  turns the `allowed_directories` property into a list with its string cast, which reads a backslash
  as an escape, so on Windows `D:\a\logs` became `D:alogs` and nothing in it could be read.
  `properties()` now escapes each backslash and quote for that cast. `Hardening.apply` was not
  affected: its `SET` is a SQL literal.

## 1.3.0 - 2026-11-03

### Added
- **`QueryFailed`** (#10): when the guard lets SQL through and DuckDB cannot run it, `GuardedQuery.run`
  throws `QueryFailed`, a subtype of `QueryRejected`, so `catch (QueryRejected e)` still catches it.
  It used to throw a plain `QueryRejected` with the first line of DuckDB's message and no cause, which
  dropped the `Candidate bindings` hint and the `LINE n:` pointer, and could not be told from the
  guard's own refusal. Its message is now DuckDB's whole message. `LINE n:` counts the caller's lines,
  not the wrapper's, and the `^^^` stays under the same word. `getErrorType()` gives DuckDB's error
  kind, such as `"Binder"` or `"Catalog"`, and the cause is the `SQLException`.

### Fixed
- **`execute` runs a statement that answers with a row.** `db.execute("CHECKPOINT")` threw, because
  the driver's `executeUpdate` refuses any statement that returns a result, and `CHECKPOINT` returns
  one. It now runs and returns 0.
- **SQL can read and write in `Hardening.Options`' temp folder** (#11). The docs said the temp
  folder was out of SQL's reach because it was not listed. Measured on 2.0.0.0, DuckDB lets
  `read_csv`, `glob`, `COPY ... TO` and `ATTACH` reach `temp_directory` and the folders below it, as
  if it were an allowed folder. The docs now say so, and `properties()` and `Hardening.apply` refuse
  a temp folder that holds anything but DuckDB's own `duckdb_temp` files, so it cannot be pointed at
  a shared folder or one of data. A test pins DuckDB's behaviour, so a release that changes it shows.
- **`GuardedQuery` runs queries unprepared, and a large ASOF join reads 6x faster.** DuckDB runs some
  queries far slower prepared when the result is large and has no `ORDER BY`. DuckDB's own
  `PREPARE`/`EXECUTE` is as slow, so the cause is in DuckDB. `GuardedQuery` checks SQL with the
  parser, so it no longer prepares: 1M rows of an ASOF join took 283 ms through it, against 1,710 ms
  in 1.2.0. `db.query(...)` still prepares, because preparing is its check that SQL only reads, and
  checking then running unprepared would plan each query twice and call a Java function of
  constants twice. For a large ASOF result through it, add an `ORDER BY`: 276 ms against 1,696 ms.
  See `docs/tuning.md`.

## 1.2.0 - 2026-11-03

Nothing from 1.1.1 was removed or changed; everything here is added.

### Added
- **`DatabaseInUse`** (#8): `Quack.open` throws it when another process holds the file, with
  `holderPid()` when DuckDB says who, so a caller can open another file instead of matching message
  text - or deleting a file it took for damaged. Every other failure to open stays a plain
  `IllegalStateException`. Measured on 2.0.0.0, macOS, with the file held from a second JVM: while a
  process writes, a second is refused whether it asks to write or to read; while a process reads,
  another reader is let in and a writer refused. The Windows message is matched but not measured.
  **`Quack.Builder.readOnly(true)`** opens a file for reading alongside other readers.
- **The first rows of a guarded query** (#6): `QueryPolicy.whenOver(Over.TRUNCATE)` hands back the
  rows that fit the row or byte cap, and `GuardedResult.isTruncated()` says there were more -
  `range(1000)` capped at 50 gives 50 rows, truncated; `range(50)` gives 50, not. Refusing stays the
  default.
- **Stopping a guarded query from outside** (#6): `guard.run(sql, cancelSignal)`, and
  `cancelSignal.cancel()` from any thread stops it with `QueryCancelled` - not a `QueryTimedOut` - and
  leaves the connection usable. Cancelled beforehand, nothing runs.
- **`Hardening.options()`** (#9): locked down - no network, no other folder, no extension, no `SET` -
  except for `allowedFolders(...)`, with `tempFolder`, `memoryLimit` and `threads`. `.properties()`
  opens a database that way; `Hardening.apply(quack, options)` applies it to one already open, in the
  order DuckDB needs, and leaves an already locked one as it is. Measured on 1.5.5.1 and 2.0.0.0: an
  allowed folder's files and those below it are read; another folder, a neighbour whose name starts
  the same, `..`, a URL, `INSTALL` and `SET` are refused. **An allowed folder can be written too** -
  `COPY ... TO` and `ATTACH` work there; DuckDB has no read-only form. The temp folder is not allowed,
  because spilling works without it: 375 MB spilled under a 200 MB limit either way.
- **`QueryColumns`** (#7): which columns of which tables a query reads, without running it, for
  filling a lazy column cache. Planned in a database of its own with empty copies of the source's
  tables and views. It says when it cannot tell - several statements, a file, a table outside `main`,
  and any `PIVOT`, whose plan on 2.0.0 leaves out exactly the columns it pivots on and sums. 6,000
  columns: 82 ms to make the planner, 3 ms for a three-column query, 158 ms for `SELECT *`.

## 1.1.1 - 2026-11-03

### Fixed
- **A query no longer runs what it then reports as failed** (#1). `query(...)`, `Rows` and
  `SqlQuery.stream` passed any SQL to `executeQuery()`, which runs a statement that returns no rows
  and only then throws - so `query("DROP TABLE car")` dropped the table and said "Failed to run SQL".
  The same went for `COPY ... TO`, `ATTACH`, `SET`, `UPDATE`, `EXPORT DATABASE` and
  `SELECT 1; DROP TABLE car`. They are now refused **before** they run, with an
  `IllegalStateException` that starts "Not run:" and says to use `execute`. Measured on 1.5.5.1 and
  2.0.0.0: preparing one statement does not run it and says whether it returns rows, so that is
  checked after preparing; preparing several statements runs all but the last, and preparing
  `EXPORT DATABASE` writes the export, so those are refused before preparing. Anything that returns
  rows runs as before - `PIVOT`, `DESCRIBE`, `EXPLAIN`, `CALL`, and `INSERT ... RETURNING` too. For SQL
  you did not write, `GuardedQuery` is still the answer.
- **`TableWriter` no longer keeps its staging table** (#4). It emptied the table after a write and kept
  it, and DuckDB does not give back the memory of deleted rows, so the data was held twice: 20,000
  384-float vectors took 64 MB through `TableWriter` against 32 MB appended directly. The table is
  now dropped when the write ends. Writes cost the same, 1.8-1.9 ms for 32 rows either way.
- The README and docs still said the driver's appender writes scalars only (#3).

### Added
- **`Rows.timeout(Duration)`** (#2): `db.query(sql).timeout(Duration.ofSeconds(5)).list(...)` cancels
  the query if it is still running or being read after that long, and throws the new
  `io.quackjvm.core.sql.QueryTimedOut`. The connection answers normally afterwards. Without it, a
  runaway query held the caller until it finished. `io.quackjvm.agent.guard.QueryTimedOut` now extends
  it, so one catch covers both; `GuardedQuery` and `Rows` share one canceller.

### Changed
- **More than one statement in a query is refused** (#1), where before every statement but the last
  ran while being prepared. Run setup statements with `execute`, one at a time. A single statement
  with a trailing semicolon is still one statement.

### Tests
- `ConcurrencyStressTest.oneSnapshotNeverSeesAnAddHalfDone` (#5) counts the object table and an index
  in one statement while adds run: 1,395 snapshots during 300 adds, none torn. The failure reported
  in #5 was the older test's own race - two counts in two statements - fixed in 1.1.0's tooling.

## 1.1.0 - 2026-10-30

### Upgrading from 1.0.0

- **Built with DuckDB 2.0**: `duckdb_jdbc` 2.0.0.0, up from 1.4.1.0. **It needs 1.5.5 or later.**
  `DuckDBConnection.duplicate()` changed its return type in DuckDB 1.5 - source-compatible, but
  **binary**-incompatible - so with a 1.4 driver on the classpath this release fails at run time with
  `NoSuchMethodError`. The whole suite passes on 1.5.5.1 and on 2.0.0.0.
- **A database file created by DuckDB 2.0 cannot be opened by DuckDB 1.x**: "Trying to read a
  database file with version number 999". Files made by 1.4 and 1.5 open, are written and read back
  under 2.0, and the older drivers still open them afterwards - measured with all three. Only a
  file 2.0 creates is one-way.
- **Nothing from 1.0.0 was removed or changed**: every public signature of its 27 public classes is
  still there. Everything below is new on top of it.
- Two new artifacts: **`quackjvm-agent`** (guarded queries, the catalog, live tables, Java functions,
  vector search) and **`quackjvm-dashboard`**. `quackjvm-core` still depends on nothing but DuckDB.

### Added
- **`quackjvm-agent`, a module of its own** for SQL and data the application does not write itself - an
  AI agent, a user, another service. It holds what was added for that: guarded queries and hardening
  (`io.quackjvm.agent.guard`), the catalog (`.catalog`), live tables (`.live`), Java functions in SQL
  (`.udf`) and vector search (`.vector`). They were `io.quackjvm.core.*`; `quackjvm-core` no longer
  contains them. Each takes a `Quack` as well as a connection: `GuardedQuery.on(quack, policy)`,
  `Catalog.of(quack)`, `VectorSearch.on(quack, table, column)`, `Hardening.apply(quack)` and
  `Hardening.openSandbox()`, and `quack.register(root -> LiveTables.register(root, ...))` for a live
  table or a function. In place of `Quack.guarded`, `catalog`, `vectors`, `live`, `snapshot`,
  `function`, `sandbox` and `Builder.hardening`, which are gone, core has three generic hooks:
  `register`, `sharedConnection` and `Builder.settings`. The examples are `Agent*`;
  `AgentEntryPoint` shows them through `Quack`.
- `io.quackjvm.core.Quack`: one entry point that owns the database, so the code using it never holds a
  connection. `Quack.open(path)`, `inMemory()`, `on(existingConnection)`, or
  `builder(path).settings(...).history(...).metrics(...).open()`; then `query`, `execute`,
  `createTable` and `write` for objects, `create` and `commit` for measures, `ship`, `export` and
  `asOf` for their history, and `register(root -> ...)` for what lives on the database itself.
  Thread-safe: every call borrows a pooled connection and gives it back on failure as well as success.
  Helpers that keep a connection for life get `sharedConnection()`, which borrows per statement, because a DuckDB connection runs one
  query at a time - a quick query behind a slow one on a shared connection waited 1,136 ms, against
  0 ms on its own. `inTransaction(tx -> ...)` runs work on one connection, committed when it returns
  and rolled back when it throws; `Tx` leaves out `ship`, `export`, `compact` and `prune`, which run
  their own transactions against two databases. `connection()` is the way down. Nothing underneath
  changed: every connection-taking method remains. `CoreEntryPoint` shows it; `CoreConnectionFacts`
  measures what it rests on - what a database shares between connections, and what each keeps.
- `io.quackjvm.agent.vector.VectorSearch`: the rows whose vector is nearest a given one.
  `VectorSearch.on(connection, table, column).identifiedBy("id").topK(query, 10, "topic = ?", "news")`,
  by cosine similarity, Euclidean distance or inner product, with `sql(...)` for joining your own
  tables to it. It uses DuckDB's built-in array functions, so **no extension, no network and no file
  access** - it works on a database hardened by the guard. A `FLOAT[]` column is refused with a message
  pointing at `vectorColumn`, and a query of the wrong length names both lengths. Measured, top-10:
  19.0 ms over 10,000 vectors of 768 dimensions, 101.8 ms over 100,000, 35.3 ms over 1,000,000 of 128.
  `createIndex()` builds an HNSW index when the `vss` extension is already installed and says why it
  cannot when it is not - which is the case on the machine these numbers come from, so **the index path
  is written and detected but not measured**, and the docs say so.
- Nested columns, written as well as read: `LIST`, `STRUCT`, `MAP` and fixed-size `ARRAY`. A record's
  own types are enough - `List<String>` becomes `VARCHAR[]`, `Map<String,Integer>` becomes
  `MAP(VARCHAR, INTEGER)`, a nested record becomes a `STRUCT`, `float[]` becomes `FLOAT[]` - and
  `Rows.records` reads them back as what they were, nulls and empties included. `ColumnarLayout.Builder`
  gained `listColumn`, `mapColumn`, `structColumn` and `vectorColumn(name, size, ...)`, the last being
  how to declare `FLOAT[768]`, which is what DuckDB's array functions need. `TableWriter` writes a table
  with nested columns through the appender however few rows there are, because a prepared statement
  cannot bind a list. **This needed no Arrow**: the roadmap said the write path would require it, and in
  1.5.5 the appender takes a `Collection`, a `Map`, `beginStruct`/`endStruct` and a `float[]`, which
  `CoreNestedTypes` measures. One level of nesting is supported and two is refused when the layout is
  built, naming what to do instead. `FLOAT[768]` writes at 20,000 rows a second, 59 MB/s on DuckDB 2.0 (29,000 and 85 MB/s on
  1.5.5).
- `io.quackjvm.agent.udf`: a Java method called from SQL. `Udfs.register(connection, name, returnType,
  argumentTypes..., method)` for one, two or three arguments - three being more than
  `java.util.function` offers, so there is an `Fn3`. `register` never calls the method with a null and
  answers a null argument with a null result; `registerNullable` passes nulls through both ways; either
  may return null. Types are the ones `DuckDBTypes` maps, with `UUID`, `byte[]` and `LocalTime` refused
  at registration because DuckDB's Java vectors have no accessor for them in 1.5.5. `registerDouble`,
  `registerInt` and `registerLong` take primitive lambdas and do not box. Measured on 2,000,000 rows of
  `price * 1.2`: 0.7 ns a row in SQL, 8.8 ns unboxed through a UDF, 8.4 ns boxed - the same as writing
  the callback by hand, so the wrapper is free, and a reminder that a UDF is for what SQL cannot say.
- `io.quackjvm.agent.live`: Java objects queried as a table. `LiveTables.register(connection, "car",
  cars, layout)` puts a `ColumnarLayout` and a collection behind a DuckDB table function with a view
  over it, so SQL says `car`, joins work, and nothing is copied or kept in step - add an object and the
  next query sees it. Projection pushdown is on, so a query about one column never touches the others'
  accessors; the scan is single-threaded because a Java `Iterable` cannot be split safely;
  `LiveTables.snapshot` copies once for a query that must see one state, and `unregister()` takes the
  name away. Types: everything `DuckDBTypes` maps except `UUID`, `byte[]` and `LocalTime`, which
  DuckDB's Java chunk writer cannot write in 1.5.5 or 2.0 and which are refused at registration with the
  column named. Measured on 1,000,000 objects: a group-by 469.2 ms live against 1.5 ms stored, a join
  to 200,000 stored rows 44.6 ms live against 3.0 ms stored, and 783.9 ms to load them - so live wins
  for a single query and for data that changes, loading wins by the second time the same rows are
  asked about.
- `Catalog.tables()` now lists views as well as tables, since a view is a table to whatever is reading
  - a live table is one.
- `io.quackjvm.agent.catalog`: what is in the database and what it means. `Catalog.of(connection)`
  gives `tables()`, `describe(table)` - columns with their SQL type, the Java type quackjvm maps them
  to, nullability, a description and a unit - and `profile(table)`, which is `SUMMARIZE` plus an exact
  row count and a few rows, as typed records with a `toText()` for whatever reads text. Descriptions
  and units are stored in the database as DuckDB comments, so they survive in a file database and can
  be read with plain SQL; a unit is kept by writing the pair as a small JSON object, and a comment
  written by anything else is read as a plain description. `ColumnarLayout.Builder` gained
  `describing(...)`, `unit(...)` and `describingTable(...)`, `ColumnDef` gained `describedAs(...)` and
  `measuredIn(...)`, and `TableWriter.createTable` writes them onto the table it creates. Measured:
  `describe` 2 ms whatever the table holds, `profile` 36 ms at a million rows and 279 ms at ten
  million - which is why the cached variant that was considered was left out rather than built.
- `io.quackjvm.agent.guard`: running SQL that came from somewhere you do not control. `GuardedQuery.on(
  connection, QueryPolicy.readOnly()).run(sql)` checks the statement with DuckDB's own parser -
  `json_serialize_sql`, which serializes only queries - so everything that writes, attaches, loads,
  copies or configures is refused, a CTE with a write inside it included, and more than one statement
  is caught by counting them rather than by looking for semicolons. It never prepares the statement to
  find out what it is, because with this driver preparing a multi-statement string executes all but the
  last: `prepareStatement("DROP TABLE secrets; SELECT 1")` drops the table. The row cap becomes a
  LIMIT around the statement rather than rows read and discarded, a query that would exceed it is
  refused rather than cut short, and the timeout cancels through `Statement.cancel()`, after which the
  connection still works. `PIVOT` cannot be checked - DuckDB will not serialize it - so it is refused
  unless allowed deliberately, and the javadoc says what that gives up. `Hardening.DATABASE` takes away
  file, extension and network access, which is off by default because in DuckDB it applies to the whole
  database and cannot be undone while it runs - `Hardening.sandbox()` is the way to have both.
  Measured on 200,000 rows: the check alone 0.61 ms, a guarded aggregate 3.58 ms against 2.06 unguarded.
- `MeasureRefresh`: publishing a measure over and over as refreshes rather than overwrites. A refresh
  is a point on a publisher's timeline - a timestamp it chooses and an id that makes it itself - and
  covers any number of measures, each of them either everything the publisher has or only what moved.
  A record that appears replaces itself entirely; a full set also removes records it does not name;
  `remove(...)` cancels a record. Nothing is overwritten: each refresh writes contributions saying
  how much every value moved, so what was published can still be seen after it has gone and the state
  is always their sum. Committing an id twice applies it once. Measured on 100 records of 25 values
  published 200 times: 7.29 ms a refresh against 2.72 ms for a plain replace that keeps no history,
  with the state staying 2,500 values and the contributions reaching 51,999 rows.
- `MeasureHistory`: a second database holding every contribution ever made, so the one being written
  to holds only current state and stays the same size however long it runs. A refresh writes the state
  and its contributions into the live database in one transaction - DuckDB allows no more than one
  attached database per transaction - and `ship(live, measures...)` moves them in bulk, then clears
  the outbox. A refresh's entry in the history's ledger is what says its contributions are there,
  written in the same transaction, so an interrupted shipment can simply be run again and shipping one
  measure never strands another's rows. Contributions arrive with their key fields beside them rather
  than a dictionary id, so several services can ship into one history. `forgetShippedRefreshes(live,
  keep)` trims the live ledger, which is the one thing that would otherwise grow for ever, while the
  history keeps every refresh. Measured on 1,000 records of 50 values: 22 ms to ship the first 50,000
  contributions, a median of 7 ms for an increment's 5,000, 0.7 ms with nothing waiting.
- Reading a measure as of a point in time: `query().asOf(at, history)` sums the contributions from the
  last full set at or before that point, across the history and whatever is still in the outbox, so a
  recent point answers the same whether the shipper has run or not. A full set now contributes the
  values themselves rather than differences - it is a baseline, which is what makes reading from it
  alone correct and everything older safe to drop. Keys that cancel out are left out unless
  `showZeros()`. A refresh dated before the last one a publisher gave that measure is refused, because
  a contribution is measured against the state at the time it was written; corrections are published
  at a later point.
- `MeasureHistory` now exports, compacts and prunes, and rebuilds the live database. `export` writes
  contributions not written before as one Parquet file per day of refresh, each row carrying its
  refresh and each file the measure's definition; `compact` folds a finished day into one file;
  `prune` drops exported contributions from before the last full set and checkpoints. Exported files
  answer as of a point exactly as the history does - `asOf(at, history).from(files...)` - and a point
  the history has pruned is refused with a message saying where it is rather than answered wrong.
  `rebuild` puts the live state back from the history, as it stands or as it stood, and refuses while
  anything is still waiting in the outbox.
- As-of reads filter by record in each source rather than after adding them up. Measured on 1,050,000
  contributions: a whole panel as of a point is 68-96 ms against 22-31 ms from the live state, one
  record 35-47 ms against 29-34 - so read the state for now and the history for history. Carrying each
  refresh's sequence number on every contribution, to skip row groups by range, was built and measured
  at 63 ms against 67 for an older point, no better for the newest, 8 bytes a row and 13% more history
  on disk: declined.
- Dictionary ids are now remembered per instance, since they never change: a publisher writing the
  same keys over and over does no SQL to resolve them after the first time, which was 2.1 ms of a
  6.5 ms refresh of 250 keys. Writing through a definition the tables disagree with is now refused
  outright, pointing at `migrate`, rather than quietly matching keys on the fields it does have.
- `MeasureTable`: values that each belong to a record and are identified by several fields - stored
  sparsely, a row per value that exists, with each distinct key held once in a dictionary - and read
  back grouped or pivoted by any of those fields through `query().rows(...).columns(...)`. A field
  can be declared the unit, and then totals refuse to add values in different units unless the query
  narrows to one, puts the unit in the rows or columns, or converts them through a table of factors
  of your own. A field's values can be given an order, since "10y" sorts before "1d" as text.
  Queries total per key before joining the dictionary: 20 ms against 227 on ten million values.
- A measure now keeps parts and provenance, archives itself and restores: every write carries a part
  label (usually a day), who wrote it and when. `archive(connection, location, part)` writes that
  part as one self-contained Parquet file - a row per value with its fields beside it, and the
  measure's definition in the file's metadata - removes those rows and checkpoints so the space is
  released. `query().from(files...)` asks archived files the same questions where they lie, one day
  or many, locally or over `s3://`; `MeasureTable.restore(connection, files...)` rebuilds the tables
  from the files alone. `archiveWhenLargerThan(bytes)` with `onArchive(handler)` lets a measure
  archive its oldest parts by itself and hand each file to a store of your own. `appendChanges`
  writes only values that differ: 95% fewer rows on a re-publish where 5% changed. A field added
  later declares what older values read as, `migrate` brings the tables up to it, and dropping a
  field is refused rather than silently adding values together. `latestPerKey()` and
  `writtenBy(...)` let a reader decide what two writers on one record mean.
  Measured on two million values: 131 ms to archive, 13 ms to answer from the file, 93 ms to read
  back. See `docs/measures.md`.
- `quackjvm-dashboard`, a new optional module: `QuackDashboard.start(database.metrics(), 8090)` serves
  a live page with the diagnosis of the last ten seconds, headline numbers with five minutes of
  history, wait against work per collection, and where DuckDB's time went. Runs on the JDK's own
  HTTP server with no other dependency; listens on loopback only, refuses requests addressed to
  any other host (DNS rebinding), and is read-only with no endpoint that runs SQL. While it runs it records
  what it samples to `quackjvm-metrics/` as JSON Lines that DuckDB queries directly: headline
  numbers every second, and statements, collections and findings every ten; kept for seven days.
- Profiles: once a minute, each heavy statement (1 ms or more a call) has one real execution
  profiled by DuckDB - what `EXPLAIN ANALYZE` would show, without running anything twice - in
  `metrics().profiles()`, on the dashboard when the statement is opened, and in
  `profiles-DATE.jsonl`. About 3% of the one call profiled; values removed, bound ones included.
  `profileStatements(false)` turns it off.
- quackjvm's one-row reads (`Sql.queryLong`, `Rows.scalar` and `count`, key lookups) read on until
  the result ends: DuckDB leaves the profile of a query closed after its only row empty.
- The dashboard shows each statement in full: click one, or tick "Show full SQL", to see it laid
  out a clause per line with its timings and a copy button; heavy statements (1 ms or more a
  call) are tagged. Statement shapes are now kept up to 4,000 characters rather than 160, which
  cut real report queries off before their FROM.
- The dashboard names the other programs on the machine when they hold a quarter of its cores or
  more - name, pid and share, never arguments - on the page and in `processes-DATE.jsonl`. Through
  `ProcessHandle` on Linux and Windows; on macOS, where Java sees no other process's CPU, through
  `/bin/ps`, at most every five seconds. `watchOtherProcesses(false)` turns it off.
- `cpu.machine`: whole-machine CPU. The CPU diagnosis uses the busier of this process and the
  machine, and names other processes when they are what is taking the cores.
- Metrics, on by default: `database.metrics()` records request, write-lock-wait and per-statement
  timings, conflicts, statement-cache hits, connection churn, CPU, and DuckDB's memory, spill and
  threads. `Diagnosis.of(interval)` names where quackjvm is choking - write lock, CPU, conflicts,
  memory, prepares, connection churn - and what to change; each cause is produced on purpose in
  `MetricsDiagnosisTest` and must be named first. `QuackMetrics.meter(connection)` does the same
  for plain connections. Overhead measured within noise; `metrics(false)` turns it off.
- `SparseTable`, `SparseBatch`, `SparseRecord`: records carrying a few of thousands of possible
  numeric data points, stored long (a name dictionary plus one row per present value) and shown
  wide on demand through `viewSql`, `view` or `materializeWide`.
- `Transactions`: transactions begun and ended with SQL, which avoid a DuckDB JDBC bug that leaves
  a connection committing statements one at a time after a failed `commit()`.
- Examples `CoreConcurrency` and `CqEngineConcurrency`: dashboard users and the `threads`
  setting, your own transactions under conflict, readers during writes, per-collection write
  locks, `serializeWrites`, and the shared-`QueryOptions` guard.
- **Materializations.** A query precomputed into a DuckDB table, so requests are served from the
  answer rather than from the data. On ten million rows a panel ranking top makes per region costs
  25.0 ms against the base table and 0.8 ms against its materialization. `refresh()` rebuilds it
  without readers ever seeing it missing: the obvious `DROP` then `CREATE TABLE AS` produced 1,280
  reader failures across fifteen refreshes with four threads reading, and the transactional swap
  produces none. `appendDelta(...)` folds new rows in for additive measures - 3.5 ms against
  13.8 ms for a rebuild, with an identical result.
- `DuckDBDatabase.getStatementCacheStats()`, so the prepared-statement cache can be asserted on
  rather than inferred from a stopwatch.
- `SqlTrace.setEnabled(...)`, `countsByStatement()` and `executionCount(...)` - statement counts are
  exact and machine-independent, which makes them the one part of performance a test can assert.

### Changed
- **Built against DuckDB 2.0.0** (`duckdb_jdbc` 2.0.0.0), up from 1.5.5.1. No code change was needed
  beyond the two 2.0 fixes below, and the whole suite passes on both 1.5.5.1 and 2.0.0.0, so pinning
  1.5.5.1 yourself still works. **Database files:** 2.0 opens, reads and writes a file made by 1.5
  without converting it, and 1.5 can still open that file afterwards - but a file *created* by 2.0
  cannot be opened by 1.5 ("version number 999"). Keep 1.5 on any machine that must open files a 2.0
  process creates. Every example was re-run on 2.0 and its recorded output, and the numbers quoted
  from it here and in the docs, re-measured. The driver limits the docs describe - no UUID, BLOB or
  TIME in Java functions and live tables, no unregistering a function, the appender losing a failed
  flush on close, a failed `commit()` leaving the driver out of step with the database, and
  preparing a multi-statement string running all but its last statement - were re-checked on 2.0 and
  are all still there. With external access off, 2.0 removes `getenv` rather than refusing it. Lambdas
  written with `->` are refused by 2.0 ("Deprecated lambda arrow"); quackjvm's own SQL uses none, but
  a query of yours might.
- **What 2.0 made slower**, each measured on more than one run against the same code on 1.5.5.1:
  - at `memory_limit` 256 MB with ten threads, a query combining conditions with `or` and `not`
    spills to disk - 112 ms against 9.5 ms on 1.5, and 8.3 ms on 2.0 at 512 MB or with four threads.
    256 MB is what most of the docs use; see `docs/tuning.md`;
  - the appender: a million objects load in 772-784 ms against 479 ms, `FLOAT[768]` writes at 59 MB/s
    against 85;
  - the guard's check: 0.6 ms against 0.23 ms, which is 2.0's parser;
  - a call into Java on one thread: 16 ns a row against 6 - though on ten threads the primitive forms
    now scale, which they did not on 1.5.

- **Every benchmark table re-run on 2.0**, and two of the README's headline figures corrected:
  - **Process memory was overstated in DuckDB's favour since 1.1.0.** The tables still showed 145 MB
    for a million objects in a columnar file, measured on DuckDB 1.4.1. Since the move to 1.5 the same
    code holds 470-550 MB on 1.5.5 and 401 MB on 2.0. It is not data - DuckDB accounts for about 45 MB and
    the live heap is 4 MB - but memory the native allocator freed and kept after the appender load
    (on macOS a malloc zone 70% fragmentation; on Linux 1.4.1 kept as much too). The comparison with
    on-heap CQEngine is now 1,303 MB against 401 MB, 3x rather than 9x; the heap is still 862 MB
    against 4 MB.
  - **The aggregate gains are smaller:** a sum over 200,000 matches is 3x faster than the heap
    (6.8 ms against 2.2 ms) rather than 10x, a group-by 19x (67.5 ms against 3.6 ms) rather than 62x.
    The heap side got faster, not DuckDB slower. These now come from a new harness,
    `AnalyticsBenchmark`, as do the point-lookup costs, which had no harness before.
  - **The join gains are larger:** `JoinBenchmark` timed each step once, cold, so the DuckDB rows
    were mostly Arrow's start-up. It now reports the best of three warm runs: 0.229 s on the heap
    against 0.015 s pushed into SQL at 200,000 x 50,000, and 32.6 s against 6 ms for the object-by-
    object form at 80,000 x 20,000 (was 129 s against 0.27 s).
  - `memory_limit` no longer changes resident memory for the benchmark (371 MB at the default, 401 MB
    at 256 MB); on 1.4 it was 939 MB against 222 MB. `docs/tuning.md` now calls it a ceiling for large
    queries rather than the most effective setting.
  - The dashboard and concurrency guidance was re-measured: `threads=2`, not 4, is now the best
    all-round setting for many concurrent users, and capping concurrent queries costs some
    throughput (41.2 to 34.9 panels/s) while still cutting p99 by a third.
- `DEFAULT_APPENDER_THRESHOLD` is 16, not 1024 - the measured crossover between prepared statements
  and the Appender is about 12. A batch of 100 objects goes from 371 µs per object to 44; a batch of
  1,000 from 345 to 6.9.
- Small writes delete-then-insert rather than using a conflict clause. DuckDB charges about 600 µs
  per row for `INSERT OR IGNORE` / `OR REPLACE` whatever the batch size; a delete and a plain insert
  do the same job for 305 µs.
- Single-object `add()` is 716 µs, from 3.49 ms.

### Fixed
- **A measure export could mark a refresh as exported when it was not in the file**, and pruning then
  deleted it. The file, the count and the export ledger each read the pending refreshes separately;
  a shipment landing between them was recorded but never written. They now read one snapshot.
- **Archiving by size ran inside the caller's transaction**, handing a part to `onArchive` before
  anything had committed, and, with its checkpoint unable to run, could archive part after part. It
  now waits for the next write outside a transaction, and stops when archiving frees no space.
- **`SparseTable.optimize` inside the caller's transaction left a full copy of the table** in memory
  until the connection closed.
- **`optimize()` on a CQEngine collection could fail a concurrent query** with "table does not exist":
  each index table was dropped and its sorted copy renamed as two separate statements. Now one
  transaction.
- **A `null` query parameter threw `NullPointerException`** in `Quack.query`, `Rows.of`, `SqlQuery`,
  `Materialization.append` and `VectorSearch.topK`.
- `Join` shared one `QueryOptions` between calls, which the persistence refuses when two threads use
  it at once; `MeasureQuery.sql` failed on a value holding `$` or `\`.
- **The guard let a write through on DuckDB 2.0.** 2.0's parser serializes a SELECT whose WITH clause
  deletes, inserts, updates or copies to a file as a SELECT, where 1.5 refused it. On 2.0 a read-only
  guard deleted every row of a table when such a statement ended in a semicolon or was run through
  `EXPLAIN ANALYZE`, and wrote a file through `COPY TO`. Every query node in the parsed statement is
  now checked against the kinds that only read, so a write - or a kind a later DuckDB adds - is
  refused wherever it sits. No released version is affected: the guard is not in 1.0.0.
- **A trailing semicolon no longer defeats the guard's row cap** (review M5); it is taken off before
  the statement is wrapped.
- **Query profiles were never captured on DuckDB 2.0**, whose profiler output is grouped differently.
  Both 1.5's and 2.0's shapes are read now.
- **Live tables could be read from another database (review C2).** What a live table reads was keyed by
  its function's name across the whole JVM, so a sandbox could read the application's table by passing
  its name, and registering a name in one database replaced what another's read. Each registration is
  now keyed by a random token only its own view passes, and closing a handle drops the view only while
  the view still reads through it.
- **A caller's rollback left the measure key cache holding ids the dictionary no longer had (review
  C7)**, so later values pointed at a key that did not exist and silently disappeared from queries.
  Ids are now remembered only after a commit the write owns. Keys a write adds are now remembered as
  well, where before only keys that already existed were.
- `ConnectionPool`'s javadoc said opening a connection costs tens of microseconds; it costs 6.6.
- The statement cache stopped working for good once full. A burst of one-off statements (SQL with
  values written into it) filled the 64 slots, nothing was ever evicted, and every statement after
  that was prepared from scratch for the life of the connection. Found on the dashboard: 100%
  misses on a write path that repeats two statements. It now evicts the least recently used
  statement that is not in use.
- `SparseTable.optimize()` retries when a write through another instance conflicts with it, and
  its Javadoc no longer claims that writes through other instances are unaffected. Under steady
  replaces through a second instance it can still fail after ten attempts, rolled back with
  nothing lost; run it through the writers' own instance.
- **A deadlock when several collections were created at once.** Collections were kept in a
  `ConcurrentHashMap` keyed by the collection itself, and an `IndexedCollection` is a `Set`, so its
  `equals` calls `size()` - a database query, run while the map held a bin lock. Keyed by identity
  now, which also stops every registration issuing a query.
- **A hang when one `QueryOptions` was shared between threads**, which gave them all one connection
  and deadlocked inside DuckDB's JDBC driver. Now reported with a message saying what to do instead.
- **The write lock is per collection, not per database.** Two collections sharing a database no
  longer serialise against each other: 2,969 objects a second where it was 1,700.
- `ConnectionPool` no longer retains a statement cache for every connection closed behind its back,
  and no longer re-pools a connection released after `close()`.

## 1.0.0

First release.
