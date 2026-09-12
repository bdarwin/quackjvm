---
hide:
  - navigation
---

<div class="hero" markdown>

# DuckDB for the JVM

<p class="tagline" markdown>
DuckDB's JDBC driver makes you treat an embedded columnar engine like a remote database. quackjvm
does not: Java objects become typed columns, results come back as Arrow batches, and a CQEngine
collection that cost you a gigabyte of heap costs three megabytes.
</p>

</div>

<div class="stats" markdown>
<div class="stat"><span class="n">861&nbsp;MB&nbsp;→&nbsp;3&nbsp;MB</span><span class="l">Java heap, 1M objects</span></div>
<div class="stat"><span class="n">1,361&nbsp;MB&nbsp;→&nbsp;132&nbsp;MB</span><span class="l">process memory</span></div>
<div class="stat"><span class="n">129&nbsp;s&nbsp;→&nbsp;0.27&nbsp;s</span><span class="l">join across collections</span></div>
<div class="stat"><span class="n">44×</span><span class="l">aggregate vs. rebuilding objects</span></div>
</div>

## Install

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.github.bdarwin</groupId>
        <artifactId>quackjvm-core</artifactId>
        <version>1.0.0</version>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    implementation 'io.github.bdarwin:quackjvm-core:1.0.0'
    ```

Add `quackjvm-cqengine` instead if you use CQEngine; it brings the core with it. Java 17 or later.

## The shortest possible version

```java
// One line changes. Your queries do not.
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));

cars.addAll(millionCars);
cars.retrieve(equal(Car.MANUFACTURER, "Ford"));   // exactly as before
```

A million objects that cost 861 MB of Java heap now cost 3 MB, and the garbage collector has
nothing to walk.

## Two modules, take either

<div class="grid cards" markdown>

-   :material-database-outline: **`quackjvm-core`**

    ---

    DuckDB for the JVM, depending on nothing but the JDBC driver. Java-object-to-column mapping,
    an Arrow read path **10× faster** than reading rows through JDBC, bulk loading at 2.4 µs per
    object, connection pooling, and typed SQL results.

    [:octicons-arrow-right-24: Storing objects](storing-objects.md)

-   :material-layers-search-outline: **`quackjvm-cqengine`**

    ---

    One plugin built on the core: a `Persistence` for [CQEngine](https://github.com/npgall/cqengine).
    Drop it in where you use CQEngine's on-heap, off-heap or SQLite persistence and get two things
    CQEngine cannot do — a collection that costs almost no heap, and **joins across collections**.

    [:octicons-arrow-right-24: Getting started](getting-started.md)

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

## What the JDBC driver does not give you

Measured against `duckdb_jdbc` 1.4.1, and the reason this project exists:

| capability | JDBC driver | quackjvm |
|---|---|---|
| reading results | one boxed value per call; `DuckDBVector` is package-private, so the columnar chunk already in your JVM is unreachable | Arrow columnar batches — **1M rows × 4 columns: 602 ms → 60 ms** |
| storing Java objects | write your own row mapping | `ColumnarLayout` maps records, beans or explicit accessors to typed columns |
| bulk loading | `Appender`, scalars only | `TableWriter` with chunked staging, **2.4 µs per object** |
| LIST / STRUCT / MAP / ARRAY | readable, **no write path at all** | planned, via Arrow |
| Java UDFs | absent | planned |

## The honest trade

!!! warning "This is a memory trade, not a speed one"

    On-heap CQEngine wins every single-collection query, and it is not close — between 100× and
    3,000× on the small ones. No amount of tuning changes that: a pointer dereference beats a
    database query. **Take this when memory is your constraint, not when latency is.**

1,000,000 objects, three indexed attributes, JDK 25 / Apple Silicon, `memory_limit=256MB`. Memory
is process RSS, because DuckDB and SQLite both keep their data in native memory that
`Runtime.totalMemory()` cannot see.

| | CQEngine on-heap | CQEngine SQLite | quackjvm (columnar) |
|---|---|---|---|
| process memory | 1,361 MB | **87 MB** | 132 MB |
| Java heap | 861 MB | **3 MB** | **3 MB** |
| on disk | – | 225 MB | **36 MB** |
| loading 1M objects | **2.7 s** | 12.2 s | 5.3 s |
| point lookup by key | **12 µs** | **564 µs** | 668 µs |
| narrow range | **40 µs** | **1.9 ms** | 4.3 ms |
| count matches | **3.4 µs** | 7.7 ms | 1.3 ms |
| query returning 2% | **1.2 ms** | 98.2 ms | 12.6 ms |
| iterate everything | **186 ms** | 757 ms | 333 ms |
| single `add()` | **4.1 µs** | 1.1 ms | 5.8 ms |
| bulk write per object | 3.1 µs | – | **2.4 µs** |
| join 200k to 50k | 0.373 s | – | **0.270 s** |

Two rows go the other way, and they are why this exists: **bulk writing is faster than the heap,
and a join across collections is faster than the heap.** Those are the workloads a database is
built for.

[Read the full comparison :octicons-arrow-right-24:](migrating.md#what-you-are-actually-trading){ .md-button }
[See it on GitHub :octicons-mark-github-16:](https://github.com/bdarwin/quackjvm){ .md-button }
