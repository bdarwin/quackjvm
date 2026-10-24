# Migrating

Two starting points, and they are very different conversations. Moving from **on-heap CQEngine** is
a real trade you should make deliberately. Moving from CQEngine's **SQLite persistence** is nearly
free.

## From on-heap CQEngine

One line changes:

```java
// before
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();

// after
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
```

Everything after that line is unchanged — `add`, `addAll`, `remove`, `update`, `retrieve`,
`orderBy`, `and`/`or`/`not`, `ResultSet.size()`, iteration, all of it.

Swap your index types too:

```java
cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));    // before
cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));       // after
```

### What you are actually trading

Be clear-eyed about this. On a million objects:

| | CQEngine on-heap | DuckDB (columnar file) |
|---|---|---|
| process memory | 1,303 MB | **401 MB** |
| Java heap | 862 MB | **4 MB** |
| on disk | – | **49 MB** |
| loading 1M objects | **2.5 s** | 3.4 s |
| point lookup by key | **7.3 µs** | 379 µs |
| narrow range | **37 µs** | 3.0 ms |
| count matches | **2.4 µs** | 869 µs |
| query returning 2% | **1.2 ms** | 16.4 ms |
| iterate everything | **212 ms** | 332 ms |
| single `add()` | **4.7 µs** | 1.1 ms |
| bulk write per object | 2.5 µs | **2.4 µs** |
| join 200k to 50k | 0.229 s | **0.015 s** |

**On-heap CQEngine wins every small single-collection query, and it is not close** — between 50x
and 360x. No amount of tuning changes that, and the reason is structural: DuckDB sequentially scans
a table even for an equality on its primary key, so no index you add will help. See
[Querying](querying.md#why-a-point-lookup-costs-what-it-does).

What you get back is 862 MB of Java heap the garbage collector no longer walks, a 49 MB file and
401 MB of process memory instead of 1.3 GB, joins across collections, and the whole of SQL.

**And the table above asks only the heap's questions.** Ask a database's and it inverts, on the
same million objects:

| | on-heap | quackjvm | |
|---|---|---|---|
| sum a column over 200k matches | 6.8 ms | **2.2 ms** | **3x faster** |
| group by make: count and average price | 67.5 ms | **3.6 ms** | **19x faster** |
| pivot, median, approximate distinct counts | *not possible* | under 15 ms | – |

The dividing line is whether a question needs the objects rebuilt. Fetching one object: stay on the
heap. Asking something *about* many objects: this is 10–60x faster and expresses things the heap
cannot. See [Aggregates and projections](aggregates.md).

**Take this when memory is your constraint, not when latency is.** If your p99 is dominated by many
small lookups, stay on the heap.

Two rows go the other way and are worth noticing: bulk writing is faster than the heap, and a join
across collections is faster than the heap. Those are the workloads a database is built for.

### Two requirements, both from CQEngine rather than from this plugin

1. **A primary key attribute.** Persistence needs a `SimpleAttribute` that uniquely identifies each
   object. Any non-heap CQEngine persistence requires this.
2. **Close your `ResultSet`s.** A result set holds a database connection until closed. Use
   try-with-resources, as you would with CQEngine's own disk or off-heap persistence. On the heap
   you get away with forgetting; here it eventually shows up as
   [a checkpoint failure](troubleshooting.md#duckdb-cannot-checkpoint-while-a-transaction-is-open).

### A migration order that works

1. Swap the persistence and the index types. Run your tests. Everything should pass.
2. Set `memoryLimit`. Nothing else affects memory as much — see
   [Tuning](tuning.md#set-a-memory-limit).
3. Fix every unclosed `ResultSet`. Wrap them in try-with-resources.
4. Add a `ColumnarLayout` if your fields are types DuckDB understands. Smaller on disk, faster to
   iterate, and it is what makes SQL and joins possible: [Storing objects](storing-objects.md).
5. Build collections through `DuckDBDatabase` rather than directly, so compound queries push down
   as one statement and collections can be joined: [Joins](joins.md).
6. Call `optimize()` after your bulk load.
7. Replace the hot "retrieve everything then aggregate in Java" paths with
   [`database.query(...)`](aggregates.md). This is where the large wins are — 44x on a sum.

Steps 1–3 are the migration. Steps 4–7 are where it starts paying for itself.

## From CQEngine's SQLite persistence

This one is close to free: you are already off the heap, already have a primary key, already close
your result sets. The mapping is one-to-one:

| CQEngine | quackjvm |
|---|---|
| `DiskPersistence.onPrimaryKey(pk)` | `DuckDBPersistence.onPrimaryKeyInTempFile(pk)` |
| `DiskPersistence.onPrimaryKeyInFile(pk, file)` | `DuckDBPersistence.onPrimaryKeyInFile(pk, file)` |
| `OffHeapPersistence.onPrimaryKey(pk)` | `DuckDBPersistence.onPrimaryKey(pk)` (the default) |
| `DiskIndex.onAttribute(attr)` | `DuckDBIndex.onAttribute(attr)` |
| `SQLiteIndexFlags.BULK_IMPORT` | `DuckDBFlags.BULK_IMPORT` (the same flag value) |

### What changes

| | CQEngine SQLite (file) | DuckDB (columnar file) |
|---|---|---|
| process memory | **97 MB** | 401 MB |
| on disk | 225 MB | **49 MB** |
| loading 1M objects | 11.7 s | **3.4 s** |
| point lookup by key | 552 µs | **379 µs** |
| narrow range | **1.2 ms** | 3.0 ms |
| count matches | 7.0 ms | **869 µs** |
| query returning 2% | 96.1 ms | **16.4 ms** |
| iterate everything | 750 ms | **332 ms** |
| single `add()` | **980 µs** | 1.1 ms |
| joins across collections | not possible | **yes** |

DuckDB is ahead on most columns: point lookups (1.5x), counts (8.1x), large result sets (5.9x),
iteration (2.3x), disk footprint (4.6x) and loading (3.4x). SQLite keeps three — a narrow range
scan, where a B-tree descends while a columnar index table is scanned; single writes, narrowly; and
resident memory, by a wide margin (97 MB against 401 MB).

**Unless narrow range scans dominate your workload, there is little reason left to stay on
CQEngine's SQLite persistence** — and it cannot join.

### Two CQEngine bugs you stop having

- CQEngine 3.6.0 pins `sqlite-jdbc 3.27.2.1`, which ships **no native library for macOS on
  aarch64** — its disk and off-heap persistence cannot start at all on Apple Silicon. quackjvm
  bumps the dependency so both work.
- CQEngine's SQLite indexes **cannot store an enum attribute** (`Type class ... not supported`).
  quackjvm stores enums as their ordinal, so they index normally.

## Not using CQEngine at all

You do not need it. `quackjvm-core` on its own gives you Java objects as DuckDB columns
(`ColumnarLayout`, `TableWriter`), typed SQL results (`Rows`), and Arrow columnar reads — against
any DuckDB connection:

```xml
<dependency>
    <groupId>io.github.bdarwin</groupId>
    <artifactId>quackjvm-core</artifactId>
    <version>1.1.0</version>
</dependency>
```

Start at [Storing objects](storing-objects.md) and [Aggregates](aggregates.md), and see
[`examples/CoreColumnarRecords.java`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CoreColumnarRecords.java).

## Reproducing these numbers

Every table on this page comes from one harness, forking a JVM per configuration because resident
memory is not comparable within one process:

```
java -cp <classpath> io.quackjvm.cqengine.bench.Comparison 1000000 6g 256MB
```

Memory is reported as **process RSS**, not JVM heap: DuckDB and SQLite both keep data in native
memory that `Runtime.totalMemory()` cannot see, so heap alone would flatter them enormously.
Measured on JDK 25, Apple Silicon, 32 GB, DuckDB 2.0.

Most of DuckDB's 401 MB is not data. After the load DuckDB accounts for about 45 MB itself
(`duckdb_memory()`), and the live heap is 4 MB; the rest is memory the native allocator freed but
kept, which on macOS shows as a malloc zone that is 70% fragmentation. These pages showed 145 MB before; that was measured on DuckDB 1.4.1 on macOS, which kept
far less after an appender load. On Linux, 1.4.1 kept as much (a 1M-object load grew the process by
about 400 MB, against 180 MB on 2.0), so plan for the larger figure.
