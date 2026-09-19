# Metrics and diagnosis

When quackjvm is slow, the first question is whether it is **waiting** or **working**. From the
outside the two look the same. They have opposite fixes:

- A write queueing for its collection's write lock gets faster when you batch writes.
- A panel query slowed because DuckDB is short of CPU gets faster when each query asks for fewer threads.

Every operation that can queue records its wait apart from its work. `Diagnosis` reads an interval
of those numbers and says which cause it is, with what it saw and what to change.

## Reading them

A `DuckDBDatabase` records its metrics from the start. Take two snapshots and subtract them to get
an interval:

```java
MetricsSnapshot before = database.metrics().snapshot();
Thread.sleep(10_000);
MetricsSnapshot interval = database.metrics().snapshot().minus(before);

for (Diagnosis.Finding finding : Diagnosis.of(interval)) {
    System.out.println(finding);
}
```

```text
WRITE_LOCK: writes to orders are queueing for its write lock
    seen: 88% of write time spent waiting for the lock; 5,563 writes, wait p50 2.49 ms, p99 3.54 ms;
          each held it 0.35 ms on average
    do:   write fewer, larger batches - addAll(...) or a DuckDBBulkWriter take the lock once for many
          objects. If writers never touch the same objects, serializeWrites(false) lets them run
          together; if they do, it turns this wait into conflicts.
```

Nothing found means nothing is choking. Any single metric is also available directly:

```java
interval.timer(QuackMetrics.WRITE_LOCK_WAIT, "orders").percentileMillis(99);
interval.statementsByTotalTime();       // where DuckDB's time went, by statement
interval.cpuUtilisation();              // 0..1, DuckDB's native threads included
```

## What each cause looks like

The load test `MetricsDiagnosisTest` produces each of these on purpose. It checks that the cause
is named first, and that a light load produces no finding at all.

| cause | what shows it | what to change |
|---|---|---|
| `WRITE_LOCK` | Writes spend over a quarter of their time waiting for the collection's lock | Batch writes (`addAll`, `DuckDBBulkWriter`), or split the collection |
| `CPU` | Cores over 70% busy, in this process or across the whole machine, with more than 1.5 heavy (≥ 1 ms) statements running at once | `SET threads` to half the cores, cap concurrent heavy queries, materialize the hottest one |
| `CONFLICTS` | Statements failing with a conflict | Keep `serializeWrites` on where writers overlap |
| `MEMORY` | Temporary files in use, or memory at 90% of `memory_limit` | Raise `memory_limit`, or pre-aggregate the large sorts and joins |
| `PREPARES` | Over 20% of prepares miss the statement cache | Bind values with `?` instead of writing them into the SQL |
| `CONNECTION_CHURN` | Connections opened for over 10% of requests | Raise `maxPooledConnections` to the number of concurrent threads |
| `ERRORS` | Other failed statements | Check the application's logs |

Some causes produce symptoms that look like other causes. The rules account for four of them:

- **A new connection starts with an empty statement cache.** Pool churn therefore shows up as
  prepare misses, and those misses are attributed to the churn.
- **DuckDB's JDBC driver closes a prepared statement when it fails.** Each conflict therefore
  costs a re-prepare, and those misses aren't blamed on how the SQL was written.
- **Another process can take the cores.** DuckDB is short of CPU either way, so the rule uses
  whichever is busier, this process or the whole machine. It also says when the difference is
  other processes. Measured with two applications on one machine: this process 31%, the machine
  100%.
- **Sub-millisecond writes can keep the machine busy** on one thread each. Lowering `threads`
  wouldn't help them, so they don't count towards `CPU`.

## What is recorded

| name | kind | scope |
|---|---|---|
| `request.read`, `request.write` | timer, borrow to close of a request's connection | collection, or `sql` for `database.sql/query/join` |
| `write_lock.wait` | timer | collection |
| `statement` | timer; a query runs until its statement is closed, since streamed results are computed as they are read | the statement's shape: literals and `?` lists collapsed |
| `statement.errors` | counter | `conflict`, `constraint`, `memory`, `other` |
| `prepare.cache_hits`, `prepare.cache_misses` | counter | |
| `connections.opened`, `connections.discarded` | counter | |
| `connections.in_use` | gauge | |
| `cpu.process_time_ns` | cumulative; `cpuUtilisation()` divides it by the interval | |
| `cpu.machine` | gauge, 0 to 1: the whole machine, other processes included | |
| `duckdb.threads`, `duckdb.memory_bytes`, `duckdb.memory_limit_bytes`, `duckdb.temp_file_bytes` | gauge, read from DuckDB at each snapshot | |

Timers are log-linear histograms (eight buckets per power of two), so a percentile read from one
is within 6% of the true value. Subtracting two snapshots gives the percentiles of just the
interval between them.

## Profiles

Once a minute, each heavy statement has one real execution profiled by DuckDB. "Heavy" means it
averages 1 ms or more a call. You get what `EXPLAIN ANALYZE` would show, without running the query
again:

```java
QueryProfile profile = database.metrics().profile(QuackMetrics.shapeOf(sql));
profile.getRowsScanned();     // after DuckDB skipped the blocks its filters ruled out
profile.getParallelism();     // CPU time over wall time: how many cores it kept busy
profile.getOperators();       // the plan, each step's time and the rows it produced
```

Three things measured while building it:
- **DuckDB's own "latency" figure in a profile is wrong** when the profiler is switched on for one
  statement at a time. It measures from when the profiler was last switched, not from when the
  statement began: a 2 ms statement sampled 300 ms after the last sample reported 300 ms. So the
  time is quackjvm's own measurement of that call.
- **The profile of a query whose results weren't read to the end is empty.** So quackjvm's one-row
  reads finish their results.
- **The profile contains data values**, including values bound to `?`. They are removed.

Turn profiling off with `profileStatements(false)`, or `metrics.setProfiling(false)`.

## What it costs

Recording a request or a statement costs a few atomic increments. Measured on single adds, primary
key lookups and small SQL queries, with metrics on and off in alternating runs, the difference was
inside run-to-run noise: 338 to 350 µs per add either way. So it is on by default. To turn it off:

```java
DuckDBDatabase.builder().metrics(false)
```

## Without a DuckDBDatabase

Code that uses the core module with its own connections can pass them through `meter`, which
times their statements the same way:

```java
QuackMetrics metrics = new QuackMetrics().watch(rootConnection);   // DuckDB memory and threads
try (Connection connection = metrics.meter(rootConnection.duplicate())) {
    ...
}
```

This path has no request or write-lock timings, because it has no pool and no locks. CPU,
conflicts, memory and the statement timings all work.
