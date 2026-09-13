# quackjvm

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bdarwin/quackjvm-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.bdarwin/quackjvm-core)
[![Docs](https://img.shields.io/badge/docs-bdarwin.github.io%2Fquackjvm-blue.svg)](https://bdarwin.github.io/quackjvm/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Tests](https://img.shields.io/badge/tests-98%20passing-brightgreen.svg)](#building-and-benchmarking)

**An embedded analytical engine for the JVM, built on [DuckDB](https://duckdb.org/).**

Your Java objects become typed columns. You ask questions of them in SQL - aggregates, groupings,
pivots, window functions, joins - and get answers back as Java values, without rebuilding a single
object. A million objects cost three megabytes of heap instead of eight hundred.

DuckDB is an analytical engine, and the JVM has never really had one. Its JDBC driver makes you
treat it as a remote database: one boxed value per call, no way to map an object to columns, no
write path for its own nested types. quackjvm is the layer that makes it what it actually is - an
in-process columnar engine you can put Java objects into and ask real questions of.

```java
// Objects in.
writer.addAll(millionCars);

// Questions out. None of these rebuild an object.
double total   = db.query("SELECT sum(price) FROM car WHERE make = ?", "Ford").scalar(Double.class);
List<Stats> by = db.query("SELECT make, count(*), avg(price) FROM car GROUP BY 1").records(Stats.class);
db.query("PIVOT car ON colour USING count(*) GROUP BY make").forEachRow(System.out::println);
```

On a million objects, against an on-heap Java collection holding the same data: summing a column
over 200,000 matches is **10x faster**, grouping them **62x faster**, and a pivot has no on-heap
equivalent at all.

## Documentation

**[bdarwin.github.io/quackjvm](https://bdarwin.github.io/quackjvm/)** - searchable, with runnable
examples throughout. This page is the overview; the site is the manual.

| | |
|---|---|
| [Getting started](https://bdarwin.github.io/quackjvm/getting-started/) | Install it, store your first objects, run your first query. |
| [Storing objects](https://bdarwin.github.io/quackjvm/storing-objects/) | BLOB or columnar, what each costs, which types are supported. |
| [Aggregates and projections](https://bdarwin.github.io/quackjvm/aggregates/) | Answering questions without rebuilding objects. The main event. |
| [Writing data](https://bdarwin.github.io/quackjvm/writing/) | Batching, bulk loading, and what each costs. |
| [Tuning](https://bdarwin.github.io/quackjvm/tuning/) | Memory limits, `optimize()`, Arrow, concurrency. |
| [Querying](https://bdarwin.github.io/quackjvm/querying/) · [Joins](https://bdarwin.github.io/quackjvm/joins/) · [Migrating](https://bdarwin.github.io/quackjvm/migrating/) | The CQEngine plugin. |
| [Troubleshooting](https://bdarwin.github.io/quackjvm/troubleshooting/) · [API reference](https://bdarwin.github.io/quackjvm/api-overview/) | |

## Modules

| module | what it is |
|---|---|
| **`quackjvm-core`** | DuckDB for the JVM. Java-object-to-column mapping, an Arrow columnar read path that is 10x faster than reading rows through JDBC, bulk loading, connection pooling, typed SQL access. Depends on nothing but DuckDB. |
| **`quackjvm-cqengine`** | One plugin built on the core: a `Persistence` implementation for [CQEngine](https://github.com/npgall/cqengine). Drop it in where you would use CQEngine's on-heap, off-heap or SQLite persistence. |

```xml
<dependency>
    <groupId>io.github.bdarwin</groupId>
    <artifactId>quackjvm-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add `quackjvm-cqengine` as well if you use CQEngine. Arrow is optional: with
`org.apache.arrow:arrow-vector`, `arrow-c-data` and `arrow-memory-unsafe` on the classpath, reads go
through the columnar path; without them everything falls back to JDBC rows automatically. Arrow also
needs `--add-opens=java.base/java.nio=ALL-UNNAMED` on Java 17 and later.

## What the JDBC driver does not give you

Measured against `duckdb_jdbc` 1.4.1, and the reason this project exists:

| capability | JDBC driver | quackjvm |
|---|---|---|
| reading results | one boxed value per call; `DuckDBVector` is package-private so the columnar chunk already in your JVM is unreachable | Arrow columnar batches - **1M rows x 4 columns: 602 ms to 60 ms** |
| storing Java objects | write your own row mapping | `ColumnarLayout` maps records, beans or explicit accessors to typed columns |
| bulk loading | `Appender`, scalars only | `TableWriter` with chunked staging, **2.4 µs per object** |
| LIST / STRUCT / MAP / ARRAY | readable, **no write path at all** | planned, via Arrow |
| Java UDFs | absent | planned |
| Java collections as tables | only via raw `registerArrowStream` | planned |

## Objects in, answers out

No CQEngine, no framework - a `ColumnarLayout` maps a record to typed columns, `TableWriter` writes
them, and `Rows` reads answers back as Java values.

```java
record Reading(int sensorId, String site, double celsius, LocalDate day) {}

ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);   // columns from the record

try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
    TableWriter writer = new TableWriter("reading", layout.toColumnDefs(), true,
            TableWriter.DEFAULT_APPENDER_THRESHOLD);
    writer.createTable(connection, true);
    writer.write(connection, readings.stream().map(layout::toRow).iterator(), true);

    // An answer, not rows. Nothing is rebuilt as an object.
    double average = Rows.of(connection.duplicate(),
            "SELECT avg(celsius) FROM reading WHERE site = ?", "kitchen").scalar(Double.class);

    // Or a record per row, components matched to the selected columns by position.
    record SiteStats(String site, long readings, double avgCelsius) {}
    List<SiteStats> stats = Rows.of(connection.duplicate(),
            "SELECT site, count(*), avg(celsius) FROM reading GROUP BY 1").records(SiteStats.class);
}
```

`Rows` gives you `scalar`, `scalarOptional`, `list`, `records`, `count`, `forEachRow` and `stream`,
and picks the read path for you - plain JDBC for a single value, Arrow columnar batches for
anything wide. It needs only a `Connection`, so it works over Parquet and CSV files too:

```java
long rows = Rows.of(connection.duplicate(), "SELECT count(*) FROM 'data/*.parquet'").scalar(Long.class);
```

Runnable: [`examples/CoreColumnarRecords.java`](examples/src/main/java/CoreColumnarRecords.java).

## Answering questions without rebuilding objects

The dominant cost of a columnar store is turning columns back into objects, and most questions do
not need them. Asking DuckDB for the answer instead is the single biggest performance lever here:

```java
// 200,000 matching cars, summed. 99.8 ms if you materialise them; 2.3 ms if you do not.
double total = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
                       .scalar(Double.class);

List<String> makes = database.query("SELECT DISTINCT make FROM car").list(String.class);

// a record whose components line up with the selected columns, by position
record MakeStats(String make, long cars, double averagePrice) {}
List<MakeStats> stats = database.query(
        "SELECT make, count(*), avg(price) FROM car GROUP BY 1").records(MakeStats.class);

// DuckDB's own PIVOT - no equivalent exists in an object query engine
database.query("PIVOT car ON colour USING count(*) GROUP BY make").forEachRow(System.out::println);
```

The same questions, 1,000,000 cars, 200,000 of them matching, against an on-heap CQEngine
collection holding the same objects:

| | on-heap | quackjvm | |
|---|---|---|---|
| sum a column over 200k matches | 10.5 ms | **1.0 ms** | **10x faster** |
| group by make: count and average price | 117.5 ms | **1.9 ms** | **62x faster** |
| pivot: makes across years | _not possible_ | 0.6 ms | – |
| median price | _not possible_* | 14.6 ms | – |
| approximate distinct models | _not possible_ | 0.6 ms | – |

<sub>* possible, but only by materialising all 1,000,000 objects and sorting them in Java.</sub>

The rule underneath it: **rebuilding objects is what costs.** Materialising those 200,000 cars and
summing them in Java takes quackjvm 58 ms; asking DuckDB for the sum takes 1.0 ms.

`scalar` deliberately reads through JDBC rather than Arrow: setting up a columnar export costs more
than reading a single value. `list` and `records` use Arrow. Both are handled for you.

`Rows` is in `quackjvm-core`, so it works against any DuckDB connection, with or without CQEngine.

## The CQEngine plugin

`quackjvm-cqengine` is one consumer of the core, and the part that is finished. If you use
[CQEngine](https://github.com/npgall/cqengine), it replaces your persistence with one line:

```java
// before
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();

// after - same queries, 9x smaller process, 300x smaller heap
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
```

Your query code does not change. You get two things CQEngine cannot do itself - a collection that
costs almost no heap, and **joins across collections** - and you can drop into SQL over the same
data whenever the object API runs out.

It is an alternative to CQEngine's own `OffHeapPersistence` and `DiskPersistence`, both SQLite, and
the trade is real in both directions; the tables below give every number, including where SQLite
and the plain heap win. Full detail:
[Querying](https://bdarwin.github.io/quackjvm/querying/),
[Joins](https://bdarwin.github.io/quackjvm/joins/),
[Migrating](https://bdarwin.github.io/quackjvm/migrating/).

## The CQEngine plugin, measured

Everything in this section is about the plugin - if you are using `quackjvm-core` on its own, skip
to [where this is going](#where-this-is-going).

1,000,000 objects of eight fields, indexes on three attributes, JDK 25 / Apple Silicon / 32 GB.
Each configuration runs in its own forked JVM (`-Xmx6g`, DuckDB `memory_limit=256MB`), because
resident memory is not comparable within one process. Reproduce with:

```
java -cp <classpath> io.quackjvm.cqengine.bench.Comparison 1000000 6g 256MB
```

Memory is reported as **process RSS**, not JVM heap: DuckDB and SQLite both keep their data in
native memory that `Runtime.totalMemory()` cannot see, so heap alone would flatter them enormously.

### What each row is

CQEngine has three storage modes of its own, and all three appear below. "On-heap" is the default -
if you use CQEngine without configuring persistence, that is what you have, and it is what this
plugin replaces.

| row | what it is | how you build it |
|---|---|---|
| **CQEngine on-heap** | stock CQEngine. Objects and indexes are ordinary Java objects, in the JVM heap - the memory `-Xmx` sizes and the garbage collector walks | `new ConcurrentIndexedCollection<>()` |
| CQEngine SQLite memory | CQEngine's `OffHeapPersistence`: a SQLite database held in native memory | `OffHeapPersistence.onPrimaryKey(pk)` |
| CQEngine SQLite file | CQEngine's `DiskPersistence`: a SQLite database in a file | `DiskPersistence.onPrimaryKeyInFile(pk, file)` |
| DuckDB memory / file | this plugin, storing objects as BLOBs or as typed columns | `DuckDBPersistence.onPrimaryKey(pk)` |

### Table 1 — in memory: stock CQEngine vs DuckDB

| storage | process RSS | JVM heap | load | pk lookup | narrow range | count only | equal ~2% | iterate all | `add()` | bulkWriter |
|---|---|---|---|---|---|---|---|---|---|---|
| CQEngine on-heap | 1,387 MB | 862 MB | **2.7 s** | **7.2 µs** | **39 µs** | **3.6 µs** | **1.1 ms** | 210 ms | **4.6 µs** | – |
| CQEngine SQLite memory | 378 MB | 3 MB | 8.4 s | 94 µs | 796 µs | 6.2 ms | 83.6 ms | 800 ms | 162 µs | – |
| DuckDB memory, BLOB | **216 MB** | 3 MB | 4.2 s | 474 µs | 2.9 ms | 1.7 ms | 17.8 ms | 616 ms | 1.2 ms | 3.07 µs |
| DuckDB memory, columnar | 229 MB | 3 MB | 3.6 s | 613 µs | 4.9 ms | 1.7 ms | 18.1 ms | **351 ms** | 1.4 ms | 3.00 µs |

### Table 2 — on disk: CQEngine's SQLite persistence vs DuckDB file persistence

| storage | process RSS | on disk | load | pk lookup | narrow range | count only | equal ~2% | iterate all | `add()` | bulkWriter |
|---|---|---|---|---|---|---|---|---|---|---|
| _CQEngine on-heap (baseline)_ | _1,387 MB_ | _–_ | _2.7 s_ | _7.2 µs_ | _39 µs_ | _3.6 µs_ | _1.1 ms_ | _210 ms_ | _4.6 µs_ | _–_ |
| CQEngine SQLite file | **88 MB** | 225 MB | 11.6 s | 546 µs | **1.4 ms** | 7.1 ms | 98.6 ms | 751 ms | 1.1 ms | – |
| DuckDB file, BLOB | 137 MB | 51 MB | 4.7 s | **500 µs** | 6.2 ms | **942 µs** | 20.9 ms | 648 ms | **749 µs** | 2.56 µs |
| DuckDB file, columnar | 145 MB | **36 MB** | **4.1 s** | 556 µs | 3.2 ms | 906 µs | **13.2 ms** | **339 ms** | 937 µs | **2.34 µs** |
| DuckDB file, col + ART | **126 MB** | 133 MB | 5.4 s | 566 µs | 3.7 ms | 1.1 ms | 14.3 ms | 348 ms | 1.1 ms | 3.43 µs |

On-heap CQEngine is repeated in both tables in italics. It is the thing being replaced, so it is
the row every other row should be read against - the SQLite comparison only says which *database*
is the better one, not whether moving off the heap is worth it at all.

### Reading them

**Against on-heap CQEngine, this is a memory trade, not a speed one - for these queries.** 1,387 MB
of process memory becomes 145 MB, and 862 MB of Java heap becomes 3 MB, and the garbage collector
stops having a million objects to walk. Every query in that table gets slower, most by two orders
of magnitude in relative terms and a fraction of a millisecond in absolute ones.

The reason is structural rather than fixable: **DuckDB sequentially scans a table even for an
equality on its primary key.** It does not use the index. Measured on a million rows, a one-row
lookup costs 224 us with a primary key, 233 us with no key at all, and 254 us with an explicit ART
index - slower. Even `SELECT 1`, touching no table, costs 48 us. It is an analytical engine.

**But that table asks the heap's questions.** Ask a database's questions and it inverts - see
*Answering questions without rebuilding objects* below, where a grouped aggregate over the same
million objects is 62x faster than the heap and a pivot has no on-heap equivalent at all.

**All three side by side.** The same million objects, the same three indexes, the same queries -
stock CQEngine in the heap, CQEngine's own SQLite disk persistence, and this plugin. All three are
CQEngine; only the storage differs:

| | CQEngine on-heap | CQEngine SQLite | DuckDB (columnar) |
|---|---|---|---|
| process memory | 1,387 MB | **88 MB** | 145 MB |
| Java heap | 862 MB | **3 MB** | **3 MB** |
| on disk | – | 225 MB | **36 MB** |
| loading 1M objects | **2.7 s** | 11.6 s | 4.1 s |
| point lookup by key | **7.2 µs** | 546 µs | **556 µs** |
| narrow range | **39 µs** | **1.4 ms** | 3.2 ms |
| count matches | **3.6 µs** | 7.1 ms | 906 µs |
| query returning 2% | **1.1 ms** | 98.6 ms | 13.2 ms |
| iterate everything | **210 ms** | 751 ms | 339 ms |
| single `add()` | **4.6 µs** | 1.1 ms | **937 µs** |
| bulk write per object | 3.1 µs | – | **2.3 µs** |
| join 200k to 50k | 0.373 s | – | **0.270 s** |

Three things that table says:

1. **On-heap CQEngine wins every single-collection query, and it is not close.** Between 80x and
   250x on the small ones. Nothing here changes that, and no amount of tuning will: a pointer
   dereference beats a database query. You are buying memory and joins, and paying in latency.
2. **Two of the rows go the other way**, and they are the reason this plugin exists: bulk writing
   is faster than the heap, and a join across collections is faster than the heap - the only query
   shape where a database beats pointer-chasing, because it is what a database is built for.
3. **Between the two databases, DuckDB now wins almost everything.** It is level on point lookups
   (556 us against 546), and ahead on counts (7.8x), large result sets (7.5x), iteration (2.2x),
   disk footprint (6.3x), loading (2.8x) and single writes (1.2x). SQLite keeps one column: a
   narrow range scan, where a B-tree descends and a columnar index table is scanned. It also uses
   less resident memory. Everywhere else the reason to choose it has gone.

In memory the picture is different, because SQLite's off-heap persistence keeps its B-tree in
memory too: DuckDB uses **half the resident memory** (216 MB vs 378 MB) and is 4.7x faster at
materialising a result set, but SQLite answers a point lookup in 94 µs against DuckDB's 474 µs.

**Two CQEngine limitations worth knowing**, both hit while producing these tables:

- CQEngine 3.6.0 pins `sqlite-jdbc 3.27.2.1`, which ships **no native library for macOS on
  aarch64** - its disk and off-heap persistence cannot start at all on Apple Silicon. This project
  bumps the dependency so that both work.
- CQEngine's SQLite indexes **cannot store an enum attribute** (`Type class ... not supported`).
  This plugin stores enums as their ordinal, so they index normally. The enum attribute is left
  unindexed in every configuration above, to keep the comparison like for like.

For finer-grained latency numbers with proper error bars there is a JMH suite; see
*Building and benchmarking*.

## Joining across collections (the plugin)

CQEngine cannot usefully join two `IndexedCollection`s. It offers `existsIn()` but evaluates it one
object at a time - fine on the heap, ruinous over a database. When both collections live in one
`DuckDBDatabase`, the same query becomes a single SQL semi-join: **129 s becomes 0.27 s** on 80,000
vehicles against 20,000 people.

`database.join(left, right)` additionally gives you the matched pairs, which `existsIn` cannot
express, and `database.query(...)` gives you arbitrary SQL over both. See
[Joins across collections](https://bdarwin.github.io/quackjvm/joins/).

## Where this is going

DuckDB's JDBC driver is the bottleneck, and specifically:

| capability | state in `duckdb_jdbc` 1.4.1 |
|---|---|
| columnar / vectorised reads | the chunk is already in the JVM, but `DuckDBVector` is package-private, so you read it one boxed value at a time - about 250 ns per value |
| Arrow export and import | exposed, both directions, and 17x faster than row-by-row JDBC (measured: 1M rows x 4 columns, 602 ms -> 36 ms) |
| LIST / STRUCT / MAP / ARRAY reads | work |
| LIST / STRUCT / MAP / ARRAY writes | **absent** - the appender's native entry points are scalars only |
| Java UDFs | **absent** |
| table functions / replacement scans | only through `registerArrowStream` |

So the roadmap for `quackjvm-core`, roughly in order of leverage:

1. **Arrow-backed reads.** Replaces per-value `getObject` calls with columnar batches. Fixes the
   largest remaining cost in the CQEngine plugin - rebuilding objects from columns - and is the
   foundation for everything else.
2. **Java collections as DuckDB tables**, via `registerArrowStream`: query and join a `List` against
   stored data with no load step.
3. **Nested types**, read and write. The write path needs Arrow regardless, since the appender
   cannot express them.

## Java version, and why not native

**quackjvm targets Java 17**, because 17 and 21 are the LTS releases enterprises actually run.
Everything works on 17 with no JVM flags beyond the ones DuckDB's own driver needs; Arrow adds
`--add-opens=java.base/java.nio=ALL-UNNAMED` and is optional.

Going below JDBC was measured rather than assumed. DuckDB's C API is genuinely reachable - the
library bundled inside `duckdb_jdbc` exports 409 `duckdb_*` symbols, including `duckdb_fetch_chunk`
and `duckdb_create_scalar_function` - and reading its vectors directly is the fastest path there is.
Reading 1,000,000 rows of three numeric columns, doing identical work:

| path | time | Java | native binaries to ship |
|---|---|---|---|
| JDBC, row by row | ~450 ms | 8+ | no |
| Arrow, boxed values | 40 ms | 17+ | no |
| **Arrow, primitive accessors** | **21 ms** | **17+** | **no - this is what ships** |
| JNA, zero-copy buffers | 20 ms | 8+ | no |
| JNI, hand written | 9-13 ms | 8+ | yes, one per platform |
| Foreign Function and Memory API | 7 ms | 22+ | no |

Three conclusions came out of that, and two of them were surprises:

1. **JDBC is not the latency bottleneck.** A prepared point lookup is 211 microseconds through JDBC
   and 201 through the C API directly. That ~200 microseconds is DuckDB planning and executing the
   query. Going native makes bulk reads faster; it does not make small queries faster.
2. **JNI and FFM are equivalent in speed**, and both are two to three times faster than Arrow for
   bulk primitive reads. JNA is slower than either, because ~4,000 native calls are needed to read a
   million rows and libffi dispatch costs microseconds where JNI costs nanoseconds - the memory
   access is identical in both, a direct `ByteBuffer`.
3. **The cost of native is distribution, not engineering.** JNI would mean building, signing and
   shipping a binary for five platforms, and a jar carrying native objects is harder to get through
   enterprise review than a pure-Java one. That works against the reason Java 17 is the floor.

So the shipped path is pure Java. FFM is worth adding as an optional module for Java 22+ when
adoption of a newer LTS makes it worthwhile, since it costs nothing to distribute. JNI is worth
revisiting only for what no pure-Java path can reach at any Java version: **Java UDFs and writing
nested types**, both of which need the C API.

## How the plugin works, and why it is not just a port

CQEngine already ships SQLite-backed persistence, so the obvious implementation is to point
`SQLiteIndex` at DuckDB. That produces a correct and unusably slow plugin.

CQEngine's SQLite indexes retrieve in two steps: ask the index table for the matching primary
keys, then look up each object by key. SQLite answers a point query in a few microseconds, so this
is fine. DuckDB spends a few hundred microseconds on *any* query, because it is built for scanning
columns rather than for point lookups. Fetching 1000 objects one key at a time takes ~210 ms.

This plugin instead pushes the key set into the object lookup, so a retrieval is a single
statement:

```sql
SELECT o.* FROM cq_car o
WHERE o.objectKey IN (SELECT objectKey FROM cqidx_car_manufacturer WHERE value = ?)
```

Measured on the same data, that is 2.8 ms instead of 213 ms - a 75x difference, and the reason this
plugin is usable at all. Where a query genuinely cannot be expressed in SQL (CQEngine's
`FilterQuery`, whose predicate is arbitrary Java), keys are streamed out, filtered on the heap, and
the matching objects fetched 1024 at a time rather than one at a time.

Results are streamed rather than materialised, so iterating a million-row result set uses a
constant, small amount of heap.
## Examples

Runnable, self-contained examples live in [`examples/`](examples/) - each is a single file with the
command to run it in its header and its real output at the bottom.

| example | what it shows |
|---|---|
| `CoreColumnarRecords` | core only: Java records to typed DuckDB columns and back, queried with SQL |
| `CoreBulkLoad` | core only: a million rows loaded in 0.62 s into a 9.8 MB file |
| `CqEngineSwap` | the one-line swap from an on-heap collection, and the memory difference |
| `CqEngineIndexes` | indexes, equality, ranges and compound queries |
| `CqEngineJoins` | two collections in one database: `existsIn`, joined pairs, SQL aggregation |
| `CqEngineBulkWriter` | streaming half a million objects in |

```bash
cd examples
mvn compile
./run-all.sh
```

## Building and benchmarking

```
mvn test                        # 88 tests, across both modules
mvn -pl quackjvm-cqengine test  # just the CQEngine plugin
```

Requires Java 17+. There are three benchmark harnesses, all under `io.quackjvm.cqengine.bench`:

```bash
mvn test-compile
cd quackjvm-cqengine
CP="target/classes:target/test-classes:$(mvn -q dependency:build-classpath \
      -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=test)"

# 1. The whole comparison in one table: memory, storage, query latency and write speed,
#    one forked JVM per configuration. Arguments: rows, -Xmx, DuckDB memory_limit.
java -cp "$CP" io.quackjvm.cqengine.bench.Comparison 1000000 4g 256MB

# 1b. Memory only, in more detail.
java -cp "$CP" io.quackjvm.cqengine.bench.MemoryBenchmark 1000000 4g 256MB

# 1c. How latency scales with collection size, and what optimize() changes.
java -cp "$CP" io.quackjvm.cqengine.bench.ScalingBenchmark 12g 1GB 125000,500000,1000000,4000000
java -cp "$CP" io.quackjvm.cqengine.bench.ScalingBenchmark --optimized 12g 1GB

# 2. Latency and throughput, via JMH.
java -cp "$CP" org.openjdk.jmh.Main                       # everything (~25 minutes)
java -cp "$CP" org.openjdk.jmh.Main QueryBenchmark        # query latency only
java -cp "$CP" org.openjdk.jmh.Main pointLookup -p storage=ON_HEAP,DUCKDB_COLUMNAR_MEMORY
java -cp "$CP" org.openjdk.jmh.Main -rf json -rff out.json

# 3. A quick single-process overview of storage size and query latency together.
java -cp "$CP" io.quackjvm.cqengine.bench.StorageBenchmark 1000000

# 4. What a cross-collection join costs, four ways.
java -cp "$CP" io.quackjvm.cqengine.bench.JoinBenchmark 200000 50000
```

The JMH benchmarks are:

| benchmark | what it measures |
|---|---|
| `QueryBenchmark.pointLookup` | fetch one object by primary key |
| `QueryBenchmark.selectiveEqual` | equality on an indexed attribute, ~2% of the collection |
| `QueryBenchmark.narrowRange` | range matching a few dozen objects |
| `QueryBenchmark.compoundQuery` | two indexed attributes intersected |
| `QueryBenchmark.countOnly` | count matching objects without materialising them |
| `QueryBenchmark.collectionSize` | size of the whole collection |
| `ScanBenchmark.materialiseIndexedSubset` | materialise ~20% of the collection |
| `ScanBenchmark.iterateEverything` | iterate every object |
| `ScanBenchmark.unindexedFilter` | full scan filtered on an unindexed attribute |
| `WriteBenchmark.SingleAdd.addOne` | one `add()` per request |
| `WriteBenchmark.BulkLoad.load` | a batch load, with and without `BULK_IMPORT` |
| `WriteBenchmark.StreamingWrite.stream` | the same load through `DuckDBBulkWriter` |

Each is parameterised by `storage`: `ON_HEAP`, `DUCKDB_BLOB_MEMORY`, `DUCKDB_COLUMNAR_MEMORY`,
`DUCKDB_BLOB_FILE`, `DUCKDB_COLUMNAR_FILE`.

## Licence

Apache License 2.0 - see [LICENSE](LICENSE). Use it, fork it, ship it, sell it; no attribution
required beyond keeping the notice. The same licence CQEngine itself uses, so there is no
compatibility question if you embed both.

Depends on [CQEngine](https://github.com/npgall/cqengine) (Apache 2.0) and
[DuckDB](https://duckdb.org/) (MIT), both permissive.

Not affiliated with the DuckDB project.

