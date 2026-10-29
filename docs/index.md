---
hide:
  - navigation
---

<div class="hero" markdown>

# An analytical engine for the JVM

<p class="tagline" markdown>
Your Java objects become typed columns in <a href="https://duckdb.org/">DuckDB</a>. You ask
questions of them in SQL — aggregates, groupings, pivots, window functions, joins — and get answers
back as Java values, without rebuilding a single object. A million objects cost four megabytes of
heap instead of eight hundred.
</p>

</div>

<div class="stats" markdown>
<div class="stat"><span class="n">19×</span><span class="l">faster grouping than an on-heap collection</span></div>
<div class="stat"><span class="n">862&nbsp;MB&nbsp;→&nbsp;4&nbsp;MB</span><span class="l">Java heap, 1M objects</span></div>
<div class="stat"><span class="n">3×</span><span class="l">faster aggregates than the heap</span></div>
<div class="stat"><span class="n">33&nbsp;s&nbsp;→&nbsp;6&nbsp;ms</span><span class="l">join across collections</span></div>
</div>

DuckDB is an analytical engine, and the JVM has never really had one. Its JDBC driver makes you
treat it as a remote database: one boxed value per call, no way to map an object to columns, no
write path for its own nested types. quackjvm is the layer that makes it what it actually is — an
in-process columnar engine you can put Java objects into and ask real questions of.

```java
// Objects in.
writer.write(connection, cars.stream().map(layout::toRow).iterator(), true);

// Questions out. None of these rebuild an object.
double total = db.query("SELECT sum(price) FROM car WHERE make = ?", "Ford").scalar(Double.class);
List<Stats> by = db.query("SELECT make, count(*), avg(price) FROM car GROUP BY 1").records(Stats.class);
db.query("PIVOT car ON colour USING count(*) GROUP BY make").forEachRow(System.out::println);
```

## Install

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.github.bdarwin</groupId>
        <artifactId>quackjvm-core</artifactId>
        <version>1.1.0</version>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    implementation 'io.github.bdarwin:quackjvm-core:1.1.0'
    ```

Add `quackjvm-cqengine` instead if you use CQEngine; it brings the core with it. Java 17 or later.

## The shortest possible version

A record becomes a table. Nothing else is required — no framework, no mapping code.

```java
record Reading(int sensorId, String site, double celsius, LocalDate day) {}

ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);   // columns from the record

TableWriter writer = new TableWriter("reading", layout.toColumnDefs(), true,
        TableWriter.DEFAULT_APPENDER_THRESHOLD);
writer.createTable(connection, true);
writer.write(connection, readings.stream().map(layout::toRow).iterator(), true);

double average = Rows.of(connection.duplicate(),
        "SELECT avg(celsius) FROM reading WHERE site = ?", "kitchen").scalar(Double.class);

record SiteStats(String site, long readings, double avgCelsius) {}
List<SiteStats> stats = Rows.of(connection.duplicate(),
        "SELECT site, count(*), avg(celsius) FROM reading GROUP BY 1").records(SiteStats.class);
```

`Rows` gives you `scalar`, `list`, `records`, `count`, `forEachRow` and `stream`, and picks the read
path for you — plain JDBC for a single value, Arrow columnar batches for anything wide. It needs
only a `Connection`, so it reads Parquet and CSV just as happily:

```java
long rows = Rows.of(connection.duplicate(), "SELECT count(*) FROM 'data/*.parquet'").scalar(Long.class);
```

??? note "Already using CQEngine? One line changes."

    ```java
    // before
    IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();

    // after - same queries, 300x smaller heap
    IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
            DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
    ```

    Your query code does not change, and you can drop into SQL over the same data whenever the
    object API runs out. See [Querying](querying.md) and [Migrating](migrating.md).

## The modules

<div class="grid cards" markdown>

-   :material-database-outline: **`quackjvm-core`**

    ---

    DuckDB for the JVM, depending on nothing but the JDBC driver. Java-object-to-column mapping,
    an Arrow read path **10× faster** than reading rows through JDBC, bulk loading at 2.4 µs per
    object, connection pooling, and typed SQL results.

    [:octicons-arrow-right-24: Storing objects](storing-objects.md)

-   :material-layers-search-outline: **`quackjvm-cqengine`** *(optional)*

    ---

    One plugin built on the core, for people already using
    [CQEngine](https://github.com/npgall/cqengine). It swaps their persistence in one line and adds
    two things CQEngine cannot do — a collection costing almost no heap, and **joins across
    collections**. Everything above still works underneath it.

    [:octicons-arrow-right-24: Using the plugin](querying.md)

-   :material-robot-outline: **`quackjvm-agent`** *(optional)*

    ---

    For SQL and data the application does not write itself — an AI agent, a user, another
    service: guarded read-only queries, a catalog that describes the database, live Java
    collections and Java functions in SQL, and vector similarity.

    [:octicons-arrow-right-24: Guarding queries](guarding-queries.md)

</div>

## Where to go next

<div class="grid cards" markdown>

-   :material-rocket-launch-outline: **[Getting started](getting-started.md)**

    Install it, store your first objects, run your first query. Start here.

-   :material-table-column: **[Storing objects](storing-objects.md)**

    BLOB or columnar, what each costs, which types are supported, how layouts work.

-   :material-magnify: **[Querying](querying.md)**

    Every query form, what is pushed into SQL and what is not, and how to tell.

-   :material-vector-link: **[Joins across collections](joins.md)**

    The thing CQEngine cannot do: `existsIn`, joined pairs, and arbitrary SQL.

-   :material-sigma: **[Aggregates and projections](aggregates.md)**

    Answering questions without rebuilding objects — the biggest performance lever here.

-   :material-database-import-outline: **[Writing data](writing.md)**

    `add`, `addAll`, `BULK_IMPORT`, and the streaming bulk writer.

-   :material-tune: **[Tuning](tuning.md)**

    Memory limits, caches, `optimize()`, ART indexes, Arrow, concurrency.

-   :material-lifebuoy: **[Troubleshooting](troubleshooting.md)**

    Every error message you are likely to see, and what it means.

-   :material-swap-horizontal: **[Migrating](migrating.md)**

    Coming from on-heap CQEngine, or from its SQLite persistence.

-   :material-book-open-variant: **[API reference](api-overview.md)**

    Class by class.

</div>

!!! note "Design notes"

    [Buffered writing](proposals/write-buffer.md) — where the remaining write latency goes, whether
    `quackjvm-core` should expose `commit()` and `flush()`, and what an implicit buffer would cost
    the CQEngine plugin. Proposed, not implemented.

## What the JDBC driver does not give you

Measured against `duckdb_jdbc` 1.5.5, and the reason this project exists:

| capability | JDBC driver | quackjvm |
|---|---|---|
| reading results | one boxed value per call; `DuckDBVector` is package-private, so the columnar chunk already in your JVM is unreachable | Arrow columnar batches — **1M rows × 4 columns: 602 ms → 60 ms** |
| storing Java objects | write your own row mapping | `ColumnarLayout` maps records, beans or explicit accessors to typed columns |
| bulk loading | `Appender`, scalars only | `TableWriter` with chunked staging, **2.4 µs per object** |
| LIST / STRUCT / MAP / ARRAY | readable, **no write path at all** | planned, via Arrow |
| Java UDFs and table functions | **shipped in 1.5** — raw, one callback interface | not wrapped yet |

## The honest trade

There are two halves to this and most comparisons only show one.

### What it costs

!!! warning "Point queries are a memory trade, not a speed one"

    On-heap CQEngine wins every small single-collection query, and it is not close — between 50×
    and 360×. No amount of tuning changes that, and the reason is structural: **DuckDB sequentially
    scans a table even for an equality on its primary key.** It does not use the index. A one-row
    lookup on a million rows costs 205 µs with a primary key, 235 µs with no key, and 203 µs with
    an explicit ART index. It is an analytical engine, and a pointer dereference beats it every
    time.

1,000,000 objects, three indexed attributes, JDK 25 / Apple Silicon, DuckDB 2.0, `memory_limit=256MB`.
Memory is process RSS, because DuckDB and SQLite both keep their data in native memory that
`Runtime.totalMemory()` cannot see. Most of DuckDB's is allocator retention rather than data: DuckDB
itself accounts for about 45 MB of it (see [Migrating](migrating.md#reproducing-these-numbers)).

| | CQEngine on-heap | CQEngine SQLite | quackjvm (columnar) |
|---|---|---|---|
| process memory | 1,303 MB | **97 MB** | 401 MB |
| Java heap | 862 MB | **3 MB** | 4 MB |
| on disk | – | 225 MB | **49 MB** |
| loading 1M objects | **2.5 s** | 11.7 s | 3.4 s |
| point lookup by key | **7.3 µs** | 552 µs | 379 µs |
| narrow range | **37 µs** | 1.2 ms | 3.0 ms |
| count matches | **2.4 µs** | 7.0 ms | 869 µs |
| query returning 2% | **1.2 ms** | 96.1 ms | 16.4 ms |
| iterate everything | **212 ms** | 750 ms | 332 ms |
| single `add()` | **4.7 µs** | 980 µs | 1.1 ms |
| bulk write per object | 2.5 µs | – | **2.4 µs** |
| join 200k to 50k | 0.229 s | – | **0.015 s** |

### What it buys

The table above asks the heap's questions. Ask a database's questions and it inverts — because the
expensive part of an object store is rebuilding objects, and none of these need one rebuilt:

| 1M cars, 200k matching | on-heap | quackjvm | |
|---|---|---|---|
| sum a column over 200k matches | 6.8 ms | **2.2 ms** | **3× faster** |
| group by make: count and average price | 67.5 ms | **3.6 ms** | **19× faster** |
| pivot: makes across years | *not possible* | 9.7 ms | – |
| median price | *not possible\** | 16.4 ms | – |
| approximate distinct models | *not possible* | 2.0 ms | – |
| join across two collections | 0.229 s | **0.015 s** | 15× faster |

<small>\* possible, but only by materialising all 1,000,000 objects and sorting them in Java.</small>

And the whole of SQL comes with it: window functions, `QUALIFY`, CTEs, `UNION`, reading a Parquet
or CSV file and joining it against your collection without an import step.

The clearest case is a **dashboard**: several people looking at the same group-bys and pivots. Four
panels over 2,000,000 rows, on-heap against quackjvm — **12 panels/s becomes 611, and 123 ms
becomes 1.4 ms**. See [Many people, the same panels](aggregates.md#many-people-the-same-panels).

The rule underneath both tables: **rebuilding objects is what costs.** Materialising those same
200,000 cars and summing them in Java takes quackjvm 85 ms; asking DuckDB for the sum takes 2.2 ms.
If your workload fetches objects one at a time, stay on the heap. If it asks questions *about* many
objects, this is 3–20× faster and can express things the heap cannot.

[Read the full comparison :octicons-arrow-right-24:](migrating.md#what-you-are-actually-trading){ .md-button }
[See it on GitHub :octicons-mark-github-16:](https://github.com/bdarwin/quackjvm){ .md-button }
