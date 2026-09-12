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

Where they do help is a highly selective equality lookup into a large index table: on a million-row
index over 50,000 distinct values, fetching the ~20 matching objects took 1.19 ms by scan and
0.87 ms with an ART index. Worth it for a hot lookup path; not worth it by default.

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
- **Writes** are serialised against each other by default, because two DuckDB transactions writing
  the same table at once cause one to fail with a conflict error. Turn this off with
  `.serializeWrites(false)` if your application coordinates its own writes.
- **One JVM process, one database file.** DuckDB does not support several processes writing the
  same file. All connections are duplicated from a single open database.
- `maxPooledConnections` sets how many connections are kept open for reuse between requests. Raise
  it if you have many concurrent readers; each one costs a little memory. Pooled connections also
  keep their prepared statements between requests, which matters more than it sounds: DuckDB
  spends about 200 µs preparing a statement, so reusing one takes a single-object `add` from
  1.25 ms to 0.92 ms. Nothing to configure — it happens when the pool hands the same connection
  back out. Statements that produced a result set are not pooled, so a result set is never cut
  short by a later request.

## DuckDB's own settings

Anything DuckDB accepts can be passed through:

```java
.property("threads", "4")               // parallelism per query
.property("temp_directory", "/fast/ssd")
.property("preserve_insertion_order", "false")   // lower memory on large loads
```

`threads` is worth knowing about: DuckDB defaults to one thread per core, which is right for
analytics but can be wasteful if your application is running many small queries concurrently and
already has its own parallelism.

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
