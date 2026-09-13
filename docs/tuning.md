# Tuning

Everything here is optional. The defaults work. But two of these settings — `memoryLimit` and
`optimize()` — change the numbers by more than everything else in this document combined, so read
at least those two.

## All the knobs in one place

```java
DuckDBPersistence.builder(Car.CAR_ID)
        .inMemory()                     // the default; .file(f) to persist instead
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .memoryLimit("512MB")           // cap DuckDB's buffer pool - read this one
        .objectCacheSize(50_000)        // bounded heap cache of hot objects; off by default
        .appenderThreshold(16)          // batch size above which the Appender is used
        .stagingChunkRows(131_072)      // rows staged at a time during a bulk load
        .maxPooledConnections(32)       // connections kept open for reuse between requests
        .serializeWrites(true)          // serialise writers; readers are never blocked
        .property("threads", "4")       // any DuckDB setting
        .build();
```

`DuckDBDatabase.builder()` takes the shared subset — `file`, `memoryLimit`, `property`,
`properties`, `serializeWrites`, `maxPooledConnections` — and each collection built from it takes
the per-collection ones through `database.collection(pk)`:

```java
IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
        .name("car")
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .objectCacheSize(50_000)
        .appenderThreshold(16)
        .stagingChunkRows(131_072)
        .build();
```

## Set a memory limit

This is the single most effective setting, and the one most likely to surprise you.

DuckDB's default `memory_limit` is **80% of system RAM**, and it will use a large share of that for
its buffer pool regardless of how small your data is. In the benchmark, the same million objects
sat at **939 MB** resident with the default limit and **222 MB** with `memoryLimit("256MB")` — same
data, same queries, same file.

```java
DuckDBPersistence.builder(Car.CAR_ID).memoryLimit("512MB").build();
```

If you took this library on to reduce memory and did not set this, you have not yet measured what
it can do.

A file-backed database honours the limit by evicting pages it can re-read from disk. An in-memory
one spills to temporary files when it has to, so a low limit costs latency rather than correctness.
Bulk loads stage `stagingChunkRows` rows at a time, so a tight limit does not break large `addAll`
calls.

## `optimize()` after a bulk load

Index tables are written in the order objects arrive, so the values in any one block span most of
the range and DuckDB cannot skip a single block: an equality or range query scans the whole index,
and that cost grows with the collection. `optimize()` rewrites each index table ordered by value,
which makes the per-block minima and maxima meaningful.

```java
persistence.optimize();   // or database.optimize() for every collection
```

Measured on a file-backed columnar collection:

| operation | 500k | 1M | 2M | 4M |
|---|---|---|---|---|
| _count matches, CQEngine on-heap_ | _4.3 µs_ | _4.1 µs_ | _3.8 µs_ | _4.2 µs_ |
| count matches | 933 µs | 1.1 ms | 1.4 ms | 2.3 ms |
| count matches, after `optimize()` | 715 µs | 776 µs | 721 µs | **802 µs** |
| _narrow range, CQEngine on-heap_ | _35 µs_ | _48 µs_ | _153 µs_ | _110 µs_ |
| narrow range | 2.2 ms | 3.6 ms | 6.4 ms | 10.8 ms |
| narrow range, after `optimize()` | 2.0 ms | 3.1 ms | 4.7 ms | 7.1 ms |

Counting stops growing with the collection altogether. A range query improves by about 1.5x but
still grows, because most of its time goes on fetching the matched objects rather than on scanning
the index — and queries which are *entirely* materialisation (`equal` returning 2% of the
collection, iterating everything) gain nothing at all from it.

It rewrites every index table, so it is a maintenance operation rather than something to call
routinely: it needs room for a second copy of the largest index while it runs, and objects added
afterwards land unsorted at the end, so the benefit decays as the collection is modified.

## ART indexes are opt-in, on purpose

`DuckDBIndex.onAttribute(...)` creates a two-column table and nothing else. DuckDB will scan those
compressed columns, which for a million rows takes a couple of milliseconds. You can additionally
ask for DuckDB ART indexes:

```java
cars.addIndex(DuckDBIndex.onAttributeWithArtIndex(Car.MANUFACTURER));
```

**Measure before you do.** In the benchmark, ART indexes on three attributes grew the database from
28 MB to 128 MB — more than four times the size of the data itself — and made no measurable
difference to any of those queries, because DuckDB was already scanning the compressed columns
faster than it could traverse an index.

Where they do help is a highly selective equality lookup into a large **attribute index table**: on
a million-row index over 50,000 distinct values, fetching the ~20 matching objects took 1.19 ms by
scan and 0.87 ms with an ART index. Worth it for a hot lookup path; not worth it by default.

Where they do **not** help at all is the object table itself. DuckDB sequentially scans it for an
equality on the primary key regardless: a one-row lookup on a million rows costs 224 µs with a
primary key, 233 µs with none, and 254 µs with an ART index — adding one made it slower. There is
no index you can add to make a point lookup fast; see
[Querying](querying.md#why-a-point-lookup-costs-what-it-does).

## The object cache

```java
.objectCacheSize(50_000)
```

A bounded heap cache of recently materialised objects, **off by default**. It helps when the same
objects are fetched repeatedly — a hot subset of a large collection — and costs heap, which is
usually the thing you came here to save. Off is the right default; turn it on if you measure
repeated materialisation of the same keys.

## Arrow

With `org.apache.arrow:arrow-vector`, `arrow-c-data` and `arrow-memory-unsafe` on the classpath,
results are read as Arrow columnar batches instead of one boxed JDBC value at a time — about **10x
faster** for wide results (1M rows x 4 columns: 602 ms to 60 ms).

Arrow also needs a JVM flag on Java 17 and later:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
```

Everything falls back to JDBC rows automatically when Arrow is missing or the flag is absent, so
nothing breaks — it is just slower. To find out which path you are on:

```java
if (!ArrowSupport.isAvailable()) {
    System.out.println("Arrow off: " + ArrowSupport.getUnavailableReason());
    System.out.println("Add: " + ArrowSupport.getRequiredJvmFlag());
}
```

Arrow is deliberately **not** used for point lookups by primary key: setting up a columnar export
costs more than reading a single row, and using Arrow there made them measurably worse (682 µs to
941 µs). This is decided per call; you do not configure it.

## Concurrency

- **Reads** never block and never lock: DuckDB's MVCC gives each request a consistent snapshot.
  Verified rather than asserted: with four readers and the write load going from 0 to 8 concurrent
  writers, reader throughput moves 9 708 → 8 459 ops/s and reader p99 612 → 808 µs. That is CPU
  sharing, not blocking — if it were contention the cost would grow with writer count, and it does
  not.
- **One `QueryOptions` per operation.** Do not hoist one out of a loop and share it between
  threads: every thread would then share one database connection, which deadlocks inside DuckDB's
  JDBC driver. quackjvm detects this and throws rather than hanging, but the fix is to build a
  fresh `QueryOptions` each time — the flags on it are cheap to set again.
- **Writes** are serialised against each other by default, because two DuckDB transactions writing
  the same table at once cause one to fail with a conflict error. That costs **3.8x of write
  throughput** at 16 threads (1 328 ops/s serialised against 5 055 not), and nothing at all when
  only one thread writes (1 305 against 1 357).

    Turn it off with `.serializeWrites(false)` **if writes from different threads never touch the
    same primary key**. That condition is exact: with 50 shared keys and 8 threads, 11–25% of writes
    abort with `TransactionContext Error: Conflict on tuple deletion!`. The failure is always a
    clean exception with the whole write rolled back — the collection is never left inconsistent —
    but a quarter of your writes disappearing is not a trade most applications want.

    **Note that the lock is per database, not per collection**, so two collections sharing a
    `DuckDBDatabase` serialise against each other even when writing different tables: 1 700 ops/s
    shared against 3 400 with a database each. If you share a database only for joins and write the
    collections from different threads, `serializeWrites(false)` recovers the whole 2x with no
    conflicts, because two different tables cannot conflict.
- **One JVM process, one database file.** DuckDB does not support several processes writing the
  same file. All connections are duplicated from a single open database.
- `maxPooledConnections` sets how many connections are kept open for reuse between requests.
  **Pooling is worth 2.1x and the size is worth nothing past 4** — measured with 16 concurrent
  readers: 7 930 ops/s unpooled, 15 676 at a pool of 1, 16 741 at 4, and flat from there to 64.
  The reason is in DuckDB's driver: `duplicate()` takes the root connection's lock, so every
  unpooled borrow serialises against every other. Do not tune this dial; the default of 32 is fine.
  Pooled connections also
  keep their prepared statements between requests, which matters more than it sounds: DuckDB
  spends about 200 µs preparing a statement, so reusing one takes a single-object `add` from
  1.25 ms to 0.92 ms. Nothing to configure — it happens when the pool hands the same connection
  back out. Statements that produced a result set are not pooled, so a result set is never cut
  short by a later request.

## DuckDB's own settings

Anything DuckDB accepts can be passed through:

```java
.property("threads", "2")               // parallelism per query - see below
.property("temp_directory", "/fast/ssd")
.property("preserve_insertion_order", "false")
```

### `threads` is global, not per connection

Worth knowing before you plan around it: **setting `threads` on one connection sets it for every
connection of that database.** There is no per-query parallelism budget in DuckDB 1.4.1, so you
cannot give an analytical query ten threads and a dashboard panel two. Verified by setting it on
one connection and reading `current_setting('threads')` back from the others — they all report the
new value.

That constraint is why [admission control](aggregates.md#at-multi-million-scale-pre-aggregation-is-the-only-lever-that-matters)
— limiting how many queries run at once, on your side of the boundary — is the only remaining lever
on tail latency once `threads` is set.

### `threads` is the only one that matters under concurrency

DuckDB defaults to one thread per core and parallelises *within* a query. When your application is
already supplying the concurrency, that is mostly overhead. Measured with 8 concurrent readers
doing point lookups on a 10-core machine:

| `threads` | throughput | p99 |
|---|---|---|
| default (10) | 14 961 ops/s | 1 115 µs |
| **2** | 20 093 ops/s | 805 µs |
| **1** | **24 803 ops/s** | **687 µs** |

**But the same setting is what makes analytical queries fast**, and there it goes the other way —
one application thread running a `GROUP BY` over 4,000,000 rows:

| `threads` | `avg(price)` | `GROUP BY manufacturer` |
|---|---|---|
| default | **857 µs** | **9.7 ms** |
| 2 | 2.4 ms | 32.5 ms |
| 1 | 4.6 ms | **61.8 ms — 6.4x slower** |

So there is no right default, only a right answer for your workload. **`threads=2` is the good
compromise** for a request-serving application: it keeps most of the concurrent throughput and most
of the single-query speed. Leave it alone if you run large analytical queries from few threads.

`preserve_insertion_order` and `memory_limit` make no measurable difference under concurrency
(+3% each, inside noise) — they matter for loading and for memory, not for parallelism.

## Storage management

```java
persistence.getBytesUsed();   // size of the database file, including its write-ahead log
persistence.optimize();       // sort index tables so DuckDB can skip blocks - see above
persistence.compact();        // CHECKPOINT: flush and reclaim space from deleted rows
persistence.analyze();        // refresh DuckDB's planner statistics
persistence.close();          // close the database; call this when you are done
```

DuckDB cannot checkpoint while any transaction is open, and **every unclosed `ResultSet` holds
one**. `compact()` reports that as an error; `optimize()` and `getBytesUsed()` treat the checkpoint
as housekeeping and carry on without it. See
[Troubleshooting](troubleshooting.md#duckdb-cannot-checkpoint-while-a-transaction-is-open).

`expand(long)` exists for source compatibility with CQEngine's SQLite persistence and does nothing:
DuckDB grows its own file and offers no way to pre-allocate it.

## A tuning checklist

1. Set `memoryLimit`. Nothing else comes close.
2. Use a columnar layout if your fields are types DuckDB understands — smaller and queryable.
3. Index what you filter on; do not index what you do not.
4. Load in batches or with a bulk writer, never one `add` at a time.
5. Call `optimize()` after the load.
6. Ask DuckDB for answers rather than objects wherever you can — see
   [Aggregates](aggregates.md).
7. Put Arrow on the classpath with the `--add-opens` flag.
8. Only now, consider ART indexes and the object cache, and measure each one.
