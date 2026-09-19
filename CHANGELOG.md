# Changelog

## Unreleased

### Added
- `quackjvm-dashboard`, a new optional module: `QuackDashboard.start(database.metrics(), 8090)` serves
  a live page with the diagnosis of the last ten seconds, headline numbers with five minutes of
  history, wait against work per collection, and where DuckDB's time went. Runs on the JDK's own
  HTTP server with no other dependency; listens on loopback only, refuses requests addressed to
  any other host (DNS rebinding), and is read-only with no endpoint that runs SQL. While it runs it records
  what it samples to `quackjvm-metrics/` as JSON Lines that DuckDB queries directly: headline
  numbers every second, and statements, collections and findings every ten; kept for seven days.
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
