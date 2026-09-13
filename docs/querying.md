# Querying

Your query code does not change. Everything CQEngine can express still works; quackjvm changes
only where the work happens.

```java
cars.retrieve(equal(Car.MANUFACTURER, "Ford"));
cars.retrieve(between(Car.PRICE, 10_000.0, 20_000.0));
cars.retrieve(and(equal(Car.COLOUR, BLUE), lessThan(Car.PRICE, 5_000.0)));
```

**Always close a `ResultSet`.** It holds a database connection until you do. This is true of
CQEngine's own disk and off-heap persistence as well, but on the heap you get away with forgetting.

```java
try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
    for (Car car : results) {
        // ...
    }
}
```

A leaked result set will eventually show up as `compact()` or `getBytesUsed()` failing with
"DuckDB cannot checkpoint while a transaction is open" — see
[Troubleshooting](troubleshooting.md).

## Adding indexes

Without an index, a query works but scans every object. With one, DuckDB gets a two-column table
it can filter.

```java
cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
```

An index added to a collection that already holds data is built from it immediately, so order does
not matter. If the DuckDB file already contains the index from a previous run, it is reused as-is
rather than rebuilt.

You can mix in on-heap CQEngine indexes, which is a good way to keep one small, very hot index in
memory while the data stays in DuckDB:

```java
cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));   // stays on the heap
```

## What gets pushed into SQL

| query | pushed down? |
|---|---|
| `equal`, `in`, `lessThan`, `greaterThan`, `between`, `startsWith`, `has` | yes, on an indexed attribute |
| `and`, `or`, `not` of the above | yes — as one statement, see below |
| `existsIn` against a collection in the same database | yes, as a semi-join |
| anything on an **unindexed** attribute | no — CQEngine filters on the heap |
| `FilterQuery` (arbitrary Java predicate) | no — values are streamed out and tested in Java |
| `orderBy` | no — CQEngine sorts the returned objects |

Nothing breaks when a query cannot be pushed down. It is evaluated the way CQEngine always has,
which is correct but slower. **The rule of thumb is simply: index what you filter on.**

## Compound queries

CQEngine normally evaluates `and(a, b)` by retrieving both sides and intersecting them in Java.
With the data in a database that means rebuilding every object matching the cheaper branch, only to
throw most of them away.

A collection built through `DuckDBDatabase` translates the whole expression into one statement:

```java
IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .build();                                    // <- this form does the push-down

cars.retrieve(and(equal(Car.COLOUR, BLUE),
                  lessThan(Car.PRICE, 5_000.0),
                  equal(Car.MANUFACTURER, "Ford")));
```

Measured on 200,000 cars:

| query | CQEngine's planner | one SQL statement |
|---|---|---|
| `and` of two attributes | 48.7 ms | **15.0 ms** |
| `and` of three attributes | 90.6 ms | **17.2 ms** |

Look at the second row against the first. A third condition took CQEngine's planner from 49 ms to
91 ms — another result set to build and intersect — while in SQL it went from 15 ms to 17 ms,
because a narrower query returns *fewer* objects to rebuild. **Adding conditions stops making a
query more expensive.**

This needs the collection to come from `database.collection(...)`. Constructing
`new ConcurrentIndexedCollection<>(persistence)` directly still works and is a valid drop-in — it
simply gives up this optimisation.

## Ordering

```java
try (ResultSet<Car> results = cars.retrieve(
        has(Car.PRICE), queryOptions(orderBy(ascending(Car.PRICE), ascending(Car.CAR_ID))))) {
    ...
}
```

Ordering is applied by CQEngine to the objects it gets back, so an ordered query gives up the
compound push-down. For a large ordered result, consider asking DuckDB directly instead — see
[Aggregates and projections](aggregates.md), where `ORDER BY` happens in the database.

## Duplicates from `or`

This is CQEngine behaviour rather than anything quackjvm does, but it surprises people: an object
matching both branches of an `or` is returned **twice** unless you ask for deduplication.

```java
cars.retrieve(or(equal(Car.MANUFACTURER, "BMW"), equal(Car.COLOUR, RED)),
              queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION)));
```

Whether you actually see duplicates depends on which branches happen to be index-backed, so a
query can start returning them when you add an index. If you care about distinct results, ask for
them explicitly.

## What a query costs

Every query is a database query, so it carries a floor of roughly **0.5 ms** — planning and
execution inside DuckDB, not JDBC overhead. Against on-heap CQEngine, on a million objects:

| | on-heap | DuckDB (columnar file) |
|---|---|---|
| point lookup by primary key | 7.2 µs | 556 µs |
| narrow range, a few dozen matches | 39 µs | 3.2 ms |
| count matches, nothing materialised | 3.6 µs | 906 µs |
| query returning 2% of the collection | 1.1 ms | 13.2 ms |
| iterate everything | 210 ms | 339 ms |

### Why a point lookup costs what it does

Not because of JDBC, and not because of anything quackjvm does. **DuckDB sequentially scans a table
even for an equality on its primary key** — it does not use the index. Measured on a million rows,
fetching one row by key:

| | |
|---|---|
| table with a `PRIMARY KEY` | 224 µs |
| table with no key at all | 233 µs |
| table with an explicit ART index | 254 µs — *slower* |
| `SELECT 1`, touching no table | 48 µs |

A vectorised scan of a million compressed rows in 224 µs is fast for a scan. It is just never going
to beat a B-tree descent or a pointer dereference, and no index you add will change it. This is
what an analytical engine is. Plan around it: if your workload is dominated by single-object
lookups, keep those objects on the heap.

The pattern is worth internalising: **the bigger the query, the smaller the relative penalty.**
A point lookup is 77x slower; iterating the whole collection is 1.6x. The fixed cost dominates
small queries and disappears into large ones.

If your application makes very many tiny queries, that ratio is the whole story and an on-heap
collection is the better tool. If it makes selective queries inside a request, you are trading tens
of microseconds for a few milliseconds and getting your heap back.

## Does it get worse as data grows?

No — every operation is constant or linear, never worse. Measured from 125,000 to 4,000,000
objects, a 32-fold increase:

| operation | 125k | 4M | growth |
|---|---|---|---|
| point lookup | 538 µs | 671 µs | **1.25x** |
| count matches | 835 µs | 2.2 ms | 2.6x |
| narrow range | 1.7 ms | 25.2 ms | 15x |
| iterate everything | 84.7 ms | 2.71 s | 32x |

One caveat: a **narrow range query is O(n) here** but O(log n) on an on-heap `NavigableIndex`,
which descends a tree. DuckDB scans the index table instead. `optimize()` narrows the gap and makes
counting flat — see [Tuning](tuning.md#optimize-after-a-bulk-load).
