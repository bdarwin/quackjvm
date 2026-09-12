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
| process memory | 1,361 MB | **132 MB** |
| Java heap | 861 MB | **3 MB** |
| on disk | – | **36 MB** |
| loading 1M objects | **2.7 s** | 5.3 s |
| point lookup by key | **12 µs** | 668 µs |
| narrow range | **40 µs** | 4.3 ms |
| count matches | **3.4 µs** | 1.3 ms |
| query returning 2% | **1.2 ms** | 12.6 ms |
| iterate everything | **186 ms** | 333 ms |
| single `add()` | **4.1 µs** | 5.8 ms |
| bulk write per object | 3.1 µs | **2.4 µs** |
| join 200k to 50k | 0.373 s | **0.270 s** |

**On-heap CQEngine wins every single-collection query, and it is not close** — between 100x and
3,000x on the small ones. No amount of tuning changes that; a pointer dereference beats a database
query. What you get back is 861 MB of Java heap that the garbage collector no longer walks, a 36 MB
file instead of 1.4 GB of process memory, joins across collections, and the whole of SQL.

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
| process memory | **87 MB** | 132 MB |
| on disk | 225 MB | **36 MB** |
| loading 1M objects | 12.2 s | **5.3 s** |
| point lookup by key | **564 µs** | 668 µs |
| narrow range | **1.9 ms** | 4.3 ms |
| count matches | 7.7 ms | **1.3 ms** |
| query returning 2% | 98.2 ms | **12.6 ms** |
| iterate everything | 757 ms | **333 ms** |
| single `add()` | **1.1 ms** | 5.8 ms |
| joins across collections | not possible | **yes** |

The split is B-tree versus columnar. SQLite wins point lookups, narrow ranges and single writes;
DuckDB wins counts (7.7x), large result sets (2.2x), disk footprint (6.3x) and loading (2.4x).

**If your workload is mostly point lookups and you do not need joins, CQEngine's SQLite persistence
is the better tool.** It just cannot join, and its file is six times larger.

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
    <version>1.0.0</version>
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
Measured on JDK 25, Apple Silicon, 32 GB.
