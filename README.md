# quackjvm

[![Maven Central](https://img.shields.io/maven-central/v/io.github.bdarwin/quackjvm-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.bdarwin/quackjvm-core)
[![Docs](https://img.shields.io/badge/docs-bdarwin.github.io%2Fquackjvm-blue.svg)](https://bdarwin.github.io/quackjvm/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Tests](https://img.shields.io/badge/tests-88%20passing-brightgreen.svg)](#building-and-benchmarking)

**Making [DuckDB](https://duckdb.org/) usable from the JVM as an embedded columnar engine, rather
than as a remote database behind JDBC.**

DuckDB is an in-process analytical engine, but its JDBC driver makes you talk to it as though it
were across a network: results arrive one boxed value at a time, its columnar chunks are
package-private, half its type system has no write path, and its extension points are not reachable
from Java at all. quackjvm closes that gap.

```java
// Java objects in, columns out - and back again
DuckDBDatabase database = DuckDBDatabase.inMemory();
IndexedCollection<Vehicle> vehicles = database.collection(Vehicle.ID)
        .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class))
        .build();

vehicles.addAll(millionsOfVehicles);

// query them as objects, or as SQL, or join them to another collection
database.sql("SELECT make, count(*), avg(price) FROM vehicle GROUP BY 1");
```

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

## The CQEngine plugin

`quackjvm-cqengine` is the first consumer of the core and the part that is finished. If you use
CQEngine, it replaces your persistence with one line and gives you two things CQEngine cannot do
itself: a collection that costs almost no heap, and **joins across collections**.

```java
// before
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();

// after - same queries, 9x smaller process, 300x smaller heap
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
```

It is an alternative to CQEngine's own `OffHeapPersistence` and `DiskPersistence` (both SQLite),
and the trade is a real one in both directions - the tables below give every number, including
where SQLite and the plain heap win.

## What it costs, measured

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

## Joining across collections

CQEngine cannot usefully join two `IndexedCollection`s. It offers `existsIn()`, but evaluates it by
asking the foreign collection about **one object at a time** - fine on the heap, ruinous over a
database, where each of those lookups is a query.

Collections which share a `DuckDBDatabase` live in one DuckDB instance as separate tables, so this
plugin translates the whole `existsIn` into a single SQL semi-join instead:

```java
DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("512MB").build();

IndexedCollection<Vehicle> vehicles = database.collection(Vehicle.VEHICLE_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class))
        .build();
IndexedCollection<Person> people = database.collection(Person.PERSON_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Person.class))
        .build();

vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
people.addIndex(DuckDBIndex.onAttribute(Person.COUNTRY));

// Every vehicle whose owner lives in France - one SQL statement.
try (ResultSet<Vehicle> results = vehicles.retrieve(
        existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID, equal(Person.COUNTRY, "FR")))) {
    results.forEach(...);
}
```

**This is stock CQEngine syntax.** `QueryFactory.existsIn(...)` is CQEngine's own API; code which
already uses it needs no change at all.

200,000 vehicles joined to 50,000 people, 20% of whom match the restriction:

| | time | matched |
|---|---|---|
| CQEngine on-heap | 0.371 s | 40,000 |
| **DuckDB, join pushed into SQL** | **0.265 s** | 40,000 |
| DuckDB, without the push-down | 128.5 s | 4 |

The join is **faster than on-heap CQEngine**, which never happens for a single-collection query -
because a join is exactly the shape of work a database is built for.

The last row is what CQEngine's own evaluation costs over DuckDB-backed collections: 200,000
objects, one foreign lookup each, at DuckDB's ~640 µs per query. Note it is *slower* despite its
restriction matching only 4 vehicles rather than 40,000 - the cost is in the number of lookups, not
the number of results.

### Getting the matched pairs

`existsIn` answers "which of these objects have a match". For the join people usually mean - "give
me the matches" - there is a typed join, which CQEngine has no way to express at all:

```java
try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
        .on(Vehicle.OWNER_ID, Person.PERSON_ID)
        .whereRight(equal(Person.COUNTRY, "FR"))
        .whereLeft(equal(Vehicle.MAKE, "Tesla"))
        .stream()) {
    pairs.forEach(pair -> System.out.println(pair.left() + " owned by " + pair.right()));
}

long matches = database.join(vehicles, people)
        .on(Vehicle.OWNER_ID, Person.PERSON_ID).count();   // no objects materialised
```

The join attribute on each side must be either that collection's primary key or an indexed
attribute; anything else is rejected with a message saying so. Restrictions are pushed into SQL
when they translate and applied to the returned objects when they do not. An object whose join
attribute holds several values appears once per match, as in SQL. **The stream holds a database
connection and must be closed.**

### Arbitrary SQL across collections

For aggregates, `GROUP BY`, window functions, or joining three or more collections at once -
anything CQEngine has no vocabulary for:

```java
try (Stream<SqlRow> rows = database.sql("""
        SELECT p.country, count(*) AS vehicles, avg(v.price) AS avgPrice
        FROM vehicle v
        JOIN person p ON v.ownerId = p.personId
        JOIN garage g ON g.vehicleId = v.vehicleId
        GROUP BY 1 ORDER BY 2 DESC""")) {
    rows.forEach(row -> System.out.println(row.getString("country") + ": " + row.getLong("vehicles")));
}
```

**Where do the table names come from?** Each collection is a SQL view named after itself. A
collection of `Vehicle` is `vehicle`; one created with `.name("archivedCars")` is `archivedCars`.
Columns are the field names of its `ColumnarLayout`, plus `objectKey` for the primary key. So in
most cases you write the names directly, as above.

Three things exist for when you would rather not hard-code them:

```java
database.describe();                          // everything queryable, with columns and types
database.table(vehicles);                     // "vehicle"
database.column(vehicles, Vehicle.OWNER_ID);  // "ownerId", checked against the real columns
database.columns(vehicles);                   // [objectKey, vehicleId, make, ownerId, price]
```

`describe()` prints what you can query:

```
DuckDB database (in memory), queryable with sql():
  vehicle  [Vehicle, columnar]
      columns: objectKey INTEGER, vehicleId INTEGER, make VARCHAR, ownerId INTEGER, price DOUBLE
  person   [Person, columnar]
      columns: objectKey INTEGER, personId INTEGER, country VARCHAR, name VARCHAR
```

and the same listing is appended to the exception when a query names something that does not exist,
so a typo tells you what the alternatives were rather than just failing.

`column(collection, attribute)` matches a CQEngine attribute to its column by name and throws with
the available columns if there is no match - which turns a silent SQL error into an explicit one.
Note there is no compile-time link between the two: the column names come from your
`ColumnarLayout`, and for `ColumnarLayout.ofRecord` they are the record's component names, which is
why they usually coincide with your attribute names.

**A collection stored as BLOBs has no queryable fields** - just a key and an opaque blob. It shows
up in `describe()` saying so. Use a `ColumnarLayout` for any collection you intend to query in SQL.

**Pass values as parameters**, not by concatenating them into the string:

```java
database.sql("SELECT count(*) FROM vehicle WHERE ownerId = ?", ownerId);
```

### What it costs

200,000 vehicles, 50,000 people, 20% matching:

| | time | |
|---|---|---|
| `existsIn`, on-heap CQEngine | 0.373 s | 40,000 matched |
| **`existsIn`, pushed into SQL** | **0.270 s** | 40,000 matched |
| `existsIn`, without the push-down | 129.2 s | 4 matched |
| hand-written map join, on-heap | 0.038 s | 40,000 pairs |
| `database.join(...)` returning pairs | 0.434 s | 40,000 pairs |
| `database.sql(...)` grouping by country | 0.209 s | 5 groups |

Two honest readings of that table:

- **The push-down is what makes joins possible at all.** Without it CQEngine asks the foreign
  collection about one object at a time, at DuckDB's ~640 µs per query: 129 seconds, and that is
  for the *cheaper* restriction, matching 4 vehicles rather than 40,000.
- **If your data fits comfortably on the heap, a hand-written map join is still faster** - 0.038 s
  against 0.434 s, because `join()` rebuilds 80,000 objects out of columns while the hand-written
  version just follows references that are already there. Use `join()` when the collections are too
  large to hold on the heap, or when you want the query expressed in one line rather than fifteen.
  Use `sql()` when you want the answer rather than the objects: aggregating the same join takes
  0.209 s and materialises nothing.

### What can be pushed down

The restriction on the foreign collection is translated to SQL when every part of it can be:

| foreign restriction | pushed down |
|---|---|
| none | yes |
| `equal`, `in`, `lessThan`, `greaterThan`, `between`, `startsWith`, `has` on an **indexed** attribute | yes |
| `and`, `or`, `not` of the above | yes - as `INTERSECT`, `UNION`, `EXCEPT` |
| anything on an **unindexed** attribute | no |
| a foreign collection in a different database, or on the heap | no |

When it cannot be pushed down the query still returns the right answer - CQEngine evaluates it the
way it always has - so a join never breaks, it only gets slow. The rule of thumb is simple:
**index the attributes you join on and restrict by**, in both collections.

Joins are verified against a pair of equivalent on-heap collections in `JoinTest`, case by case.

## Compound queries run as one statement

CQEngine evaluates `and(a, b)` by retrieving both sides and intersecting them in Java. On the heap
that is cheap - the objects are already there. With the data in a database it is not: the cheaper
branch has to be fully materialised, object by object, before most of those objects are thrown away.

A collection built with `database.collection(...)` translates the whole expression into one
statement instead, so DuckDB intersects two columns of keys and returns only the objects which
actually match. 200,000 cars:

| query | matches | CQEngine on-heap | DuckDB, CQEngine's planner | DuckDB, whole query in SQL |
|---|---|---|---|---|
| `and(COLOR, PRICE)` | 4,041 | 3.0 ms | 48.7 ms | **15.0 ms** |
| `and(COLOR, PRICE, MANUFACTURER)` | 4,071 | 7.9 ms | 90.6 ms | **17.2 ms** |

3.2x and 5.3x. The more interesting number is the second row against the first: adding a third
condition took CQEngine's planner from 49 ms to 91 ms, because it had another result set to
materialise and intersect - while in SQL it went from 15 ms to 17 ms, because a narrower query
returns *fewer* objects to rebuild. **Conditions stop making a query more expensive.**

It applies to `and`, `or` and `not`, nested to any depth, and to `existsIn` joins nested inside
them - so `and(equal(MAKE, "Tesla"), existsIn(people, ...))` is also a single statement. Every part
must be indexed; if any part is not, or the query is ordered with `orderBy`, the whole thing is
handed back to CQEngine unchanged. Simple single-attribute queries are left alone, since one index
already answers those in one statement.

This needs no change to your queries, but it does need the collection to come from
`database.collection(...)` rather than `new ConcurrentIndexedCollection<>(persistence)` - that is
where the push-down lives.

It does not close the gap to on-heap CQEngine: 15 ms against 3 ms. Materialising 4,000 objects out
of columns is what remains, and it is irreducible.

## Does it scale?

Latency was measured from 125,000 to 4,000,000 objects - 32x more data - to check whether the cost
grows with the collection. It does not compound. Every operation is either constant or linear:

| operation | 125k | 4M | growth over 32x data |
|---|---|---|---|
| point lookup by primary key | 538 µs | 671 µs | **1.25x** |
| count matches | 835 µs | 2.2 ms | 2.6x |
| narrow range | 1.7 ms | 25.2 ms | 15x |
| equality → 2% of the collection | 10.3 ms | 89.5 ms | 8.7x |
| iterate everything | 84.7 ms | 2.71 s | 32x |

The cost of a query is **a fixed ~0.5 ms, plus a term linear in the rows scanned, plus a term
linear in the objects materialised.** Nothing multiplies. The *relative* penalty against an on-heap
collection actually shrinks as queries get larger, because the fixed cost amortises: iterating the
whole collection is 4.4x slower than on-heap at 125,000 objects and 3.5x slower at 4,000,000.

One operation does scale worse than CQEngine does, and it is worth knowing about: a **narrow range
query** is O(log n) on an on-heap `NavigableIndex`, which descends a tree, but O(n) here, because
DuckDB scans the index table. That gap widens with the collection - 68x slower at 125,000 objects,
229x at 4,000,000. DuckDB ART indexes do not close it; they are not used for range scans.
`optimize()` narrows it, and flattens counting entirely - see below.

## Migrating existing code

Only the construction of the collection changes.

```java
// Before: everything on the heap
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();
cars.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));

// After: everything in DuckDB, in memory but off the Java heap
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
```

**In memory is the default.** The collection lives in DuckDB's own memory, compressed, and
disappears when the persistence is closed - the same lifecycle as an on-heap collection, which is
what makes it a drop-in. Pass a file when you want it to survive a restart:

```java
DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, new File("cars.duckdb"));   // durable
DuckDBPersistence.onPrimaryKeyInTempFile(Car.CAR_ID);                        // spills to a temp file
```

A file-backed database is also the *smaller* option in resident memory, because DuckDB can evict
pages it can re-read from disk. In-memory has to keep everything.

Everything after that line is unchanged - `add`, `addAll`, `remove`, `update`, `retrieve`,
`orderBy`, `and`/`or`/`not`, `ResultSet.size()`, iteration, all of it.

Two requirements come from CQEngine itself, not from this plugin:

1. **A primary key attribute.** Persistence needs a `SimpleAttribute` which uniquely identifies
   each object. Any non-heap CQEngine persistence requires this.
2. **Close your `ResultSet`s.** A result set holds a database connection until closed. Use
   try-with-resources, as you would with CQEngine's own disk or off-heap persistence.

If you already use `DiskPersistence`, the mapping is one-to-one:

| CQEngine | this plugin |
|---|---|
| `DiskPersistence.onPrimaryKey(pk)` | `DuckDBPersistence.onPrimaryKeyInTempFile(pk)` |
| `DiskPersistence.onPrimaryKeyInFile(pk, file)` | `DuckDBPersistence.onPrimaryKeyInFile(pk, file)` |
| `OffHeapPersistence.onPrimaryKey(pk)` | `DuckDBPersistence.onPrimaryKey(pk)` (the default) |
| `DiskIndex.onAttribute(attr)` | `DuckDBIndex.onAttribute(attr)` |
| `SQLiteIndexFlags.BULK_IMPORT` | `DuckDBFlags.BULK_IMPORT` (the same flag value) |

You can also mix: on-heap indexes still work on a DuckDB-backed collection, which is a good way to
keep one small, very hot index in memory while the data lives in DuckDB.

## Two ways to store the objects

### BLOB (the default) - works with any class

```java
DuckDBPersistence.onPrimaryKey(Car.CAR_ID)
```

Each object is serialized into one BLOB column, exactly as CQEngine's own disk persistence does.
No mapping code, no restrictions on field types.

### Columnar - smallest, and queryable with plain SQL

```java
DuckDBPersistence.builder(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))   // a record
        .build();                                             // in memory unless .file(...) is given
```

Each object is shredded into one typed column per field, so DuckDB's dictionary, run-length and
FSST compression apply per column. That is where 42 MB becomes 28 MB - and much more than that
for data with repetitive columns. It also means the database is readable with any SQL tool:

```sql
SELECT manufacturer, count(*), avg(price) FROM cq_objects GROUP BY 1;
```

Three ways to describe a layout:

```java
ColumnarLayout.ofRecord(Car.class);        // records: components + canonical constructor
ColumnarLayout.reflective(Car.class);      // any class: all instance fields, read and written reflectively
ColumnarLayout.builder(Car.class)          // explicit control
        .column("carId", Integer.class, Car::carId)
        .column("name", String.class, Car::name)
        .rowFactory(values -> new Car((Integer) values[0], (String) values[1]))
        .build();
```

Columnar layouts require every field to have a type DuckDB understands (see below). A class with a
`List` or a nested object field must use BLOB storage - or an explicit layout which maps only the
scalar fields, if you are willing to define the factory yourself.

**Which to choose:** columnar if you want the smallest footprint, SQL access to your data, or you
mostly filter and aggregate. BLOB if your objects have field types a column cannot hold, or your
queries routinely materialise very large numbers of whole objects.

## Supported attribute and column types

`String`, `Character`, `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`,
`BigInteger`, `BigDecimal`, `UUID`, `byte[]`, enums, `java.util.Date`, `java.sql.Date/Time/Timestamp`,
`LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`.

Types are mapped to preserve Java's `Comparable` ordering, so a range query pushed into SQL returns
exactly what an on-heap index would. Two consequences worth knowing:

- **Enums are stored as their ordinal**, because Java orders enums by ordinal and not by name. The
  stored data is therefore sensitive to reordering the constants in the enum declaration.
- **`OffsetDateTime` keeps its instant, not its original offset** - a `TIMESTAMP WITH TIME ZONE`
  column stores a point in time, so values come back in the JVM's zone.

## Bulk loading

DuckDB is at its best loading data in batches and at its worst one row at a time. There are three
ways to write, in increasing order of speed:

```java
cars.add(car);                       // ~3-5 ms   per object
cars.addAll(listOfCars);             // ~4 µs     per object
try (var writer = persistence.bulkWriter()) {
    writer.add(car);                 // ~2.5 µs   per object
}
```

Any `addAll` of more than 16 objects (`appenderThreshold`) is automatically streamed through
DuckDB's Appender rather than inserted row by row, in chunks of `stagingChunkRows` so that a large
load does not need memory proportional to the batch. `BULK_IMPORT` tells it the objects are new so
it can skip the delete-before-insert which makes re-adding idempotent:

```java
QueryOptions options = new QueryOptions();
FlagsEnabled.forQueryOptions(options).add(DuckDBFlags.BULK_IMPORT);
cars.update(Collections.emptyList(), millionsOfCars, options);
```

### Streaming: `DuckDBBulkWriter`

`addAll` needs the whole batch in memory as a collection first. When the data is a stream - a file
being parsed, a cursor, a queue - a bulk writer keeps one DuckDB Appender open per table and pushes
rows straight into them:

```java
try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
    while (records.hasNext()) {
        writer.add(toCar(records.next()));
    }
}   // flushed and closed here
```

Loading a million objects this way took **2.4 s** against 3.8 s for `addAll` with `BULK_IMPORT`,
and produced a **30 MB** file against 36 MB - appending straight into the target tables compresses
into better row groups than staging the rows and copying them. Memory stays bounded no matter how
many objects pass through.

It is a loading tool, not a general write path, and it trades away four things:

- **The objects must be new.** Rows are appended, not merged, so an object whose primary key is
  already stored fails the primary key constraint at the next flush. Use `collection.update(...)`
  to replace existing objects.
- **The collection is inconsistent until you flush.** DuckDB makes appended rows visible in batches
  as its buffers fill, and each table flushes independently, so a query running during a session
  can see an object that is not yet in every index. `flush()` and `close()` make it consistent, and
  `flush()` is also where a duplicate key is reported.
- **Add your indexes before opening the writer**, so that it writes them too. An index added
  afterwards is still built correctly, but from the object table rather than by the writer.
- **One thread.** A writer is not thread-safe and holds the persistence's write lock for its
  lifetime, so other writers wait. Readers are never blocked.

## Tuning

```java
DuckDBPersistence.builder(Car.CAR_ID)
        .inMemory()                     // the default; .file(f) to persist instead
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .memoryLimit("512MB")           // cap DuckDB's buffer pool - see below
        .objectCacheSize(50_000)        // bounded heap cache of hot objects; off by default
        .appenderThreshold(16)          // batch size above which the Appender is used
        .stagingChunkRows(131_072)      // rows staged at a time during a bulk load
        .maxPooledConnections(32)       // connections kept open for reuse between requests
        .serializeWrites(true)          // serialise writers; readers are never blocked
        .property("threads", "4")
        .build();
```

### Set a memory limit

This is the single most effective setting, and the one most likely to surprise you. DuckDB's
default `memory_limit` is 80% of system RAM, and it will use a large share of that for its buffer
pool regardless of how small your data is. In the benchmark above, the same million objects sat at
**939 MB** resident with the default limit and **222 MB** with `memoryLimit("256MB")` - same data,
same queries, same file.

```java
DuckDBPersistence.builder(Car.CAR_ID).memoryLimit("512MB").build();
```

A file-backed database honours the limit by evicting pages it can re-read from disk. An in-memory
one spills to temporary files when it has to, so a low limit costs latency rather than correctness.
Bulk loads stage `stagingChunkRows` rows at a time, so a tight limit does not break large
`addAll` calls.

### ART indexes are opt-in, on purpose

`DuckDBIndex.onAttribute(...)` creates a two-column table and nothing else. DuckDB will scan those
compressed columns, which for a million rows takes a couple of milliseconds. You can additionally
ask for DuckDB ART indexes:

```java
cars.addIndex(DuckDBIndex.onAttributeWithArtIndex(Car.MANUFACTURER));
```

Measure before you do. In the benchmark above, ART indexes on three attributes grew the database
from 28 MB to 128 MB - more than four times the size of the data itself - and made no measurable
difference to any of those queries, because DuckDB was already scanning the compressed columns
faster than it could traverse an index.

Where they do help is a highly selective equality lookup into a large index table: on a million-row
index over 50,000 distinct values, fetching the ~20 matching objects took 1.19 ms by scan and
0.87 ms with an ART index. Worth it for a hot lookup path; not worth it by default.

## Concurrency

- **Reads** never block and never lock: DuckDB's MVCC gives each request a consistent snapshot.
- **Writes** are serialised against each other by default, because two DuckDB transactions writing
  the same table at once cause one to fail with a conflict error. Turn this off with
  `.serializeWrites(false)` if your application coordinates its own writes.
- One JVM process, one database file: DuckDB does not support several processes writing the same
  file. All connections are duplicated from a single open database.

## Storage management

```java
persistence.getBytesUsed();   // size of the database file, including its write-ahead log
persistence.optimize();       // sort the index tables so DuckDB can skip blocks - see below
persistence.compact();        // CHECKPOINT: flush and reclaim space from deleted rows
persistence.analyze();        // refresh DuckDB's planner statistics
persistence.close();          // close the database; call this when you are done
```

Note that DuckDB cannot checkpoint while any transaction is open, and every unclosed `ResultSet`
holds one. `compact()` reports that as an error; `optimize()` and `getBytesUsed()` treat the
checkpoint as housekeeping and carry on without it.

### `optimize()` after a bulk load

Index tables are written in the order objects arrive, so the values in any one block span most of
the range and DuckDB cannot skip a single block: an equality or range query scans the whole index,
and that cost grows with the collection. `optimize()` rewrites each index table ordered by value,
which makes the per-block minima and maxima meaningful.

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
the index - and queries which are *entirely* materialisation (`equal` returning 2% of the
collection, iterating everything) gain nothing at all from it.

It rewrites every index table, so it is a maintenance operation rather than something to call
routinely: it needs room for a second copy of the largest index while it runs, and objects added
afterwards land unsorted at the end, so the benefit decays as the collection is modified.

`expand(long)` exists for source compatibility with CQEngine's SQLite persistence and does nothing:
DuckDB grows its own file and offers no way to pre-allocate it.

## Serialization note (BLOB mode)

CQEngine 3.6.0 pins Kryo 5.0.0-RC1 and registers serializers that reflect into `java.util`
internals. On Java 17+ that combination cannot serialize records at all, and throws
`InaccessibleObjectException` for anything else unless you start the JVM with
`--add-opens java.base/java.util=ALL-UNNAMED`.

This plugin therefore depends on a current Kryo and uses its own `KryoPojoSerializer` by default,
which needs no JVM flags and handles records. Annotating your class with
`@PersistenceConfig(serializer = ...)` still selects your own serializer, as in stock CQEngine.

Fields holding the JDK's internal collection wrappers (`Arrays.asList(...)`,
`Collections.unmodifiableList(...)`) still cannot be serialized without `--add-opens`; use a plain
`ArrayList`, or store those objects columnar.

## What is stored where

| table | contents |
|---|---|
| `cq_<collection>` | one row per object: `objectKey` (primary key) plus either a `value` BLOB or one column per field |
| `cqidx_<collection>_<attribute>` | one row per indexed attribute value: `(objectKey, value)`; several rows per object for multi-valued attributes |

A collection is also exposed as a view under its bare name (`car`), which is what `database.table(cars)`
returns and what you should use in SQL.

Both are ordinary DuckDB tables. Point any SQL tool at the file and query them.

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

## Licence

Apache License 2.0 - see [LICENSE](LICENSE). Use it, fork it, ship it, sell it; no attribution
required beyond keeping the notice. The same licence CQEngine itself uses, so there is no
compatibility question if you embed both.

Depends on [CQEngine](https://github.com/npgall/cqengine) (Apache 2.0) and
[DuckDB](https://duckdb.org/) (MIT), both permissive.

Not affiliated with the DuckDB project.

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

## Documentation

**[bdarwin.github.io/quackjvm](https://bdarwin.github.io/quackjvm/)** — searchable, with every
page below. The same pages live in [`docs/`](docs/) if you would rather read them here:

| | |
|---|---|
| [Getting started](docs/getting-started.md) | Install it, store your first objects, run your first query. |
| [Storing objects](docs/storing-objects.md) | BLOB or columnar, what each costs, which types are supported. |
| [Querying](docs/querying.md) | Every query form, and what is pushed into SQL. |
| [Joins across collections](docs/joins.md) | `existsIn`, matched pairs, and arbitrary SQL. |
| [Aggregates and projections](docs/aggregates.md) | Answering questions without rebuilding objects. |
| [Writing data](docs/writing.md) | `add`, `addAll`, `BULK_IMPORT`, and the streaming bulk writer. |
| [Tuning](docs/tuning.md) | Memory limits, `optimize()`, ART indexes, Arrow, concurrency. |
| [Troubleshooting](docs/troubleshooting.md) | Every error message you are likely to see. |
| [Migrating](docs/migrating.md) | From on-heap CQEngine, or from its SQLite persistence. |
| [API reference](docs/api-overview.md) | Class by class. |

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

## How it works, and why it is not just a port

CQEngine already ships SQLite-backed persistence, so the obvious implementation is to point
`SQLiteIndex` at DuckDB. That produces a correct and unusably slow plugin.

CQEngine's SQLite indexes retrieve in two steps: ask the index table for the matching primary
keys, then look up each object by key. SQLite answers a point query in a few microseconds, so this
is fine. DuckDB spends a few hundred microseconds on *any* query, because it is built for scanning
columns rather than for point lookups. Fetching 1000 objects one key at a time takes ~210 ms.

This plugin instead pushes the key set into the object lookup, so a retrieval is a single
statement:

```sql
SELECT o.* FROM cq_objects o
WHERE o.objectKey IN (SELECT objectKey FROM cqtbl_manufacturer WHERE value = ?)
```

Measured on the same data, that is 2.8 ms instead of 213 ms - a 75x difference, and the reason this
plugin is usable at all. Where a query genuinely cannot be expressed in SQL (CQEngine's
`FilterQuery`, whose predicate is arbitrary Java), keys are streamed out, filtered on the heap, and
the matching objects fetched 1024 at a time rather than one at a time.

Results are streamed rather than materialised, so iterating a million-row result set uses a
constant, small amount of heap.
