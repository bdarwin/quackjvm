# Changelog

## Unreleased

### Added
- `io.quackjvm.core.catalog`: what is in the database and what it means. `Catalog.of(connection)`
  gives `tables()`, `describe(table)` - columns with their SQL type, the Java type quackjvm maps them
  to, nullability, a description and a unit - and `profile(table)`, which is `SUMMARIZE` plus an exact
  row count and a few rows, as typed records with a `toText()` for whatever reads text. Descriptions
  and units are stored in the database as DuckDB comments, so they survive in a file database and can
  be read with plain SQL; a unit is kept by writing the pair as a small JSON object, and a comment
  written by anything else is read as a plain description. `ColumnarLayout.Builder` gained
  `describing(...)`, `unit(...)` and `describingTable(...)`, `ColumnDef` gained `describedAs(...)` and
  `measuredIn(...)`, and `TableWriter.createTable` writes them onto the table it creates. Measured:
  `describe` 1 ms whatever the table holds, `profile` 34 ms at a million rows and 307 ms at ten
  million - which is why the cached variant that was considered was left out rather than built.
- `io.quackjvm.core.guard`: running SQL that came from somewhere you do not control. `GuardedQuery.on(
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
  Measured on 200,000 rows: the check alone 0.20 ms, a guarded aggregate 2.57 ms against 2.06 unguarded.
- `MeasureRefresh`: publishing a measure over and over as refreshes rather than overwrites. A refresh
  is a point on a publisher's timeline - a timestamp it chooses and an id that makes it itself - and
  covers any number of measures, each of them either everything the publisher has or only what moved.
  A record that appears replaces itself entirely; a full set also removes records it does not name;
  `remove(...)` cancels a record. Nothing is overwritten: each refresh writes contributions saying
  how much every value moved, so what was published can still be seen after it has gone and the state
  is always their sum. Committing an id twice applies it once. Measured on 100 records of 25 values
  published 200 times: 4.05 ms a refresh against 2.04 ms for a plain replace that keeps no history,
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
  history keeps every refresh. Measured on 1,000 records of 50 values: 18 ms to ship the first 50,000
  contributions, single digits for an increment's 5,000, 0.7 ms with nothing waiting.
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

### Added
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
  Measured on two million values: 33 ms to archive, 8 ms to answer from the files, 30 ms to read
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

### Fixed
- The statement cache stopped working for good once full. A burst of one-off statements (SQL with
  values written into it) filled the 64 slots, nothing was ever evicted, and every statement after
  that was prepared from scratch for the life of the connection. Found on the dashboard: 100%
  misses on a write path that repeats two statements. It now evicts the least recently used
  statement that is not in use.
- `SparseTable.optimize()` retries when a write through another instance conflicts with it, and
  its Javadoc no longer claims that writes through other instances are unaffected. Under steady
  replaces through a second instance it can still fail after ten attempts, rolled back with
  nothing lost; run it through the writers' own instance.

## 1.1.0

**Requires `duckdb_jdbc` 1.5.5 or later.** This is the reason for the minor bump rather than a
patch: `DuckDBConnection.duplicate()` changed its return type in DuckDB 1.5, which is
source-compatible but **binary**-incompatible. quackjvm 1.0.0 was compiled against 1.4.1, so
anyone who brought 1.5.x got a `NoSuchMethodError` out of the published jar. Database files remain
readable in both directions between 1.4 and 1.5; only the driver pin matters.

### Added

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

### Fixed

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

### Changed

- `DEFAULT_APPENDER_THRESHOLD` is 16, not 1024 - the measured crossover between prepared statements
  and the Appender is about 12. A batch of 100 objects goes from 371 µs per object to 44; a batch of
  1,000 from 345 to 6.9.
- Small writes delete-then-insert rather than using a conflict clause. DuckDB charges about 600 µs
  per row for `INSERT OR IGNORE` / `OR REPLACE` whatever the batch size; a delete and a plain insert
  do the same job for 305 µs.
- Single-object `add()` is 716 µs, from 3.49 ms.

## 1.0.0

First release.
