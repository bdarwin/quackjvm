# Aggregates and projections

**This is the single biggest performance lever in quackjvm.** It is also the part that has no
equivalent in an object query engine, so it is easy to miss.

The dominant cost of a columnar store is not finding your data — it is turning columns back into
Java objects. Most questions do not need the objects at all. Asking DuckDB for the *answer* rather
than for the *rows* skips that step entirely.

```java
// 200,000 matching cars, summed.
// 85 ms if you materialise them. 2.2 ms if you do not.
double total = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
                       .scalar(Double.class);
```

Measured on 1,000,000 cars, 200,000 matching, DuckDB 2.0 (median of three runs of
`AnalyticsBenchmark`) — and compared against an **on-heap** CQEngine
collection holding the same objects, not just against quackjvm doing it the slow way:

| | on-heap | quackjvm | |
|---|---|---|---|
| sum a column over 200k matches | 6.8 ms | **2.2 ms** | **3x faster** |
| group by make: count and average price | 67.5 ms | **3.6 ms** | **19x faster** |
| pivot: makes across years | *not possible* | 9.7 ms | – |
| median price | *not possible*\* | 16.4 ms | – |
| approximate distinct models | *not possible* | 2.0 ms | – |

<sub>\* possible, but only by materialising all 1,000,000 objects and sorting them in Java.</sub>

Note what the first row says: this is not merely faster than quackjvm materialising the objects
(85 ms), it is **three times faster than the heap**, which has the objects in hand already. Rebuilding
objects is what costs, and an aggregate rebuilds none.

## The `Rows` API

`database.query(sql, params...)` returns a `Rows`. Nothing runs until you ask for a shape, and the
shape you ask for decides how the result is read.

`Rows` lives in **`quackjvm-core`** and takes any JDBC `Connection`, so it works with or without
CQEngine:

```java
Rows rows = Rows.of(connection, "SELECT ...", params);
```

### One value

```java
double total  = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
                        .scalar(Double.class);
long   cars   = database.query("SELECT count(*) FROM car").scalar(Long.class);
String newest = database.query("SELECT max(model) FROM car").scalar(String.class);
```

`scalar` throws if the query returns no row. When "no rows" is a legitimate answer, use
`scalarOptional`:

```java
Optional<Double> cheapest = database
        .query("SELECT min(price) FROM car WHERE make = ?", "Delorean")
        .scalarOptional(Double.class);
```

### One column

```java
List<String> makes = database.query("SELECT DISTINCT make FROM car").list(String.class);

List<Double> prices = database.query(
        "SELECT price FROM car WHERE make = ? ORDER BY price DESC", "Ford")
        .list(Double.class);
```

For a column too large to hold in memory, stream it instead:

```java
database.query("SELECT price FROM car")
        .forEachValue(Double.class, price -> accumulator.accept(price));
```

### Rows as records

Give it a record whose components line up with the selected columns **by position** — names are not
matched, order is:

```java
record MakeStats(String make, long cars, double averagePrice) {}

List<MakeStats> stats = database.query(
        "SELECT make, count(*), avg(price) FROM car GROUP BY 1 ORDER BY 2 DESC")
        .records(MakeStats.class);

stats.forEach(s -> System.out.printf("%-10s %,8d cars, avg %,10.2f%n",
        s.make(), s.cars(), s.averagePrice()));
```

Component types must be ones DuckDB understands — the same set listed in
[Storing objects](storing-objects.md#supported-types). A mismatch fails immediately with the column
index and both types, not with a `ClassCastException` later.

And for large results, the streaming form:

```java
database.query("SELECT make, count(*), avg(price) FROM car GROUP BY 1")
        .forEachRecord(MakeStats.class, System.out::println);
```

### Counting

```java
long matches = database.query("SELECT * FROM car WHERE price > ?", 40_000.0).count();
```

This wraps your query in a `count(*)` rather than reading and discarding rows.

### Anything else

`forEachRow` and `stream` give you the raw `SqlRow` — useful when the shape is dynamic, such as the
output of a `PIVOT`:

```java
database.query("PIVOT car ON colour USING count(*) GROUP BY make")
        .forEachRow(System.out::println);

try (Stream<SqlRow> rows = database.query("SELECT make, price FROM car").stream()) {
    rows.limit(10).forEach(row ->
            System.out.println(row.getString("make") + " " + row.getDouble("price")));
}
```

**`stream()` holds a connection and must be closed.** `forEachRow`, `list`, `records`, `scalar` and
`count` all close everything themselves — prefer them.

`SqlRow` gives `get(int)`, `get(String)`, `getString`, `getLong`, `getDouble`, `getColumnNames` and
`toArray`. It is **one reusable view over the cursor**, so a row you want to keep beyond the
iteration must be copied with `toArray()`.

## Many people, the same panels

A dashboard is the shape this is best at, and it is worth its own numbers because it is neither of
the two cases usually measured. It is not many small queries and it is not one big analytical
query — it is **many large queries at once**, which pull DuckDB's tuning in opposite directions.

Measured on 2,000,000 sales, four panels — a group-by, a pivot, a filtered revenue-by-year, and a
headline scalar aggregate — with each "user" looping over them. Against an on-heap CQEngine
collection doing the same four in Java (DuckDB 2.0, `DashboardBenchmark`, the mean of two runs):

| users | on-heap panels/s | quackjvm panels/s | on-heap p50 | quackjvm p50 |
|---|---|---|---|---|
| 1 | 11.8 | **611** | 123 ms | **1.4 ms** |
| 2 | 25.6 | **905** | 120 ms | **2.0 ms** |
| 4 | 49.2 | **1,077** | 123 ms | **3.4 ms** |
| 8 | 98.9 | **1,074** | 121 ms | **6.4 ms** |
| 16 | 103.6 | **1,033** | 163 ms | **13.2 ms** |

**50x the throughput for one user, 10x at sixteen, and p50 latency 12–90x lower.** A panel that
takes the heap 123 ms takes DuckDB 1.4 ms, because a group-by over two million rows is a columnar
scan rather than two million virtual calls. The heap's throughput grows with users until the cores
run out; DuckDB's is near its ceiling by four.

### Tuning it: `threads` is the dial

DuckDB parallelises *within* a query, which is what makes one panel fast and what makes sixteen
panels fight over the same cores. The default is one thread per core; on a 10-core machine:

| users | default | `threads=1` | `threads=2` | `threads=4` |
|---|---|---|---|---|
| 1 | **611** | 175 | 298 | 492 |
| 4 | **1,077** | 662 | 785 | 1,014 |
| 8 | 1,074 | **1,296** | 1,277 | 1,223 |
| 16 | 1,033 | **1,384** | 1,302 | 1,135 |
| p99 at 16 users | 48.5 ms | **32.4 ms** | 34.7 ms | 42.7 ms |

The `threads=4` column at eight and sixteen users moved by up to 20% between the two runs; the
others by under 5%.

Three things to take from it:

- **Total throughput saturates around 1,000–1,400 panels/s whatever you do.** That is the machine,
  not the setting. What the setting changes is how the work is shared out.
- **The default is best at low concurrency and worst at high.** It wins outright at one user
  (611 against 175) and loses at sixteen, where its p99 is half as long again as `threads=1`'s.
- **`threads=2` is the best all-rounder.** At eight and sixteen users it is within 6% of
  `threads=1`, the fastest there, and at one user it does 298 panels/s (3.4 ms) against
  `threads=1`'s 175 (5.8 ms).

```java
DuckDBDatabase.builder().property("threads", "2").build();
```

If a handful of people share a dashboard, leave the default. If you are serving many concurrent
sessions and care about the tail, set it to 2. See [Tuning](tuning.md#threads-is-the-only-one-that-matters-under-concurrency).

### At multi-million scale, pre-aggregation is the only lever that matters

The numbers above are 2,000,000 rows and simple panels. Scale both and the picture sharpens.
**10,000,000 rows, 16 concurrent users, four genuinely complex panels** — a window function ranking
top models within each region, a three-dimensional roll-up with a `HAVING`, a two-dimensional
`PIVOT`, and a share-of-total using a correlated aggregate:

| | panels/s | p50 | p99 |
|---|---|---|---|
| base table, all 16 at once | 41.2 | 334 ms | 825 ms |
| base table, at most 2 running | 34.9 | 458 ms | **535 ms** |
| **pre-aggregate, all 16 at once** | **3,655** | **3.2 ms** | 17.6 ms |
| pre-aggregate, at most 4 running | 2,020 | 7.9 ms | **9.8 ms** |

**89x the throughput and a 47x smaller tail** (DuckDB 2.0, `AdmissionBenchmark`, one run). The
pre-aggregate is 18,000 rows — 0.18% of the base table — and takes 75 ms to build.

Two things follow, and they are the whole of the guidance for this shape of workload:

**Limiting concurrency shortens the tail and never adds throughput.** Look at the first two rows:
allowing only two queries to run at a time costs some throughput (41.2 → 34.9 panels/s) and cuts p99
by a third (825 ms → 535 ms). That is because the machine is already saturated — DuckDB parallelises *within*
a query and expects to own the machine while it runs one, so sixteen at once is sixteen queries
fighting for the same cores. Making them queue does not create capacity, it just stops them
trampling each other. **You cannot tune your way to more throughput here.**

**Reducing the work is what creates capacity.** That is the third row, and it is not a tuning
change at all — it is asking a smaller question.

**And the two interact.** With the pre-aggregate, limiting concurrency *hurts* throughput (3,655 → 2,020),
because the queries are now short enough that queueing is pure overhead. So: limit concurrency when
queries are slow, do not when they are fast. Fix the queries first and the question goes away.

!!! warning "`threads` is a global setting, not a per-connection one"

    The obvious idea — give analytical queries many threads and interactive ones few — does not
    work. `SET threads=1` on any connection changes it for every connection of that database,
    verified by reading `current_setting('threads')` back from the others. There is no per-query
    parallelism budget in DuckDB 1.5 or 2.0, which is why admission control on our side of the boundary
    is the only remaining lever on latency.

### Pre-aggregate the panels

!!! tip "There is an API for this now"

    [Materializations](materializations.md) manage the table for you, including a refresh that does
    not interrupt readers — the obvious `DROP` then `CREATE TABLE AS` produced 1,280 reader failures
    across fifteen refreshes; the managed refresh produces none.

    ```java
    database.materialize("sales_rollup").as("SELECT region, make, ... GROUP BY 1, 2").build();
    ```


A dashboard usually slices the same few measures a handful of ways, which means the base table is
doing far more work than the question needs. One table fixes that:

```sql
CREATE TABLE agg AS
SELECT region, make, colour, year, count(*) AS n, sum(price) AS total
FROM sale GROUP BY 1,2,3,4;
```

On 2,000,000 sales that is **3,000 rows — 0.2% of the base table — built in 17 ms**, and panels run
against it 4–8x faster. Keep the measures **additive** (counts and sums, never averages) and derive
the rest at query time as `sum(total)/sum(n)`; you cannot average an average.

Two things make this better than caching the answers. It **serves slices you never anticipated** —
a region × year breakdown nobody precomputed is still 4x faster against the pre-aggregate, where a
cache would simply miss. And it can be **kept in step incrementally**: for append-only data, fold in
the new rows rather than rebuilding.

```sql
INSERT INTO agg
SELECT region, make, colour, year, count(*), sum(price)
FROM sale WHERE saleId >= :watermark GROUP BY 1,2,3,4;
```

3.7 ms against 14.7 ms for a full rebuild, and the result is identical.

Note that **DuckDB has no materialised views** — `CREATE MATERIALIZED VIEW` is a parser error and a
plain `CREATE VIEW` is live, re-scanning the base table every time. A pre-aggregate is a real table
you maintain. The full comparison, including what it cannot do, is in
[Pre-aggregation vs. a result cache](proposals/result-cache.md).

### Give each request its own `QueryOptions`

Worth repeating here because a dashboard server is exactly where it goes wrong: do not hoist one
`QueryOptions` out of a loop and share it between request threads. See
[Tuning](tuning.md#concurrency).

## Which read path is used

`scalar` and `scalarOptional` deliberately read through plain JDBC: setting up a columnar export
costs more than reading a single value. `list`, `records` and `forEachRecord` use Arrow when it is
on the classpath, which is roughly 10x faster for wide results. You do not choose — the right path
is picked per call. See [Tuning](tuning.md#arrow) for enabling Arrow.

## Things with no object-query equivalent

Once the data is in real columns, the whole of DuckDB's SQL is available. A few that come up often:

```java
// Pivot: one column per colour, one row per make.
database.query("PIVOT car ON colour USING count(*) GROUP BY make").forEachRow(System.out::println);

// Window function: rank within each make.
record Ranked(String make, String model, double price, long rank) {}
database.query("SELECT make, model, price, "
             + "rank() OVER (PARTITION BY make ORDER BY price DESC) "
             + "FROM car QUALIFY rank() OVER (PARTITION BY make ORDER BY price DESC) <= 3")
        .records(Ranked.class);

// Approximate distinct counts over a billion-row table, in milliseconds.
long distinctModels = database.query("SELECT approx_count_distinct(model) FROM car")
                              .scalar(Long.class);

// Percentiles.
double median = database.query("SELECT median(price) FROM car").scalar(Double.class);

// Read a Parquet or CSV file and join it against your collection, with no import step.
database.query("SELECT c.make, f.rating FROM car c JOIN 'ratings.csv' f ON c.model = f.model")
        .forEachRow(System.out::println);

long rowsInFile = database.query("SELECT count(*) FROM 'data/*.parquet'").scalar(Long.class);
```

**One limitation:** `Rows` runs queries, so a statement that returns no result set — `COPY`,
`CREATE`, `INSERT` — fails with *"executeQuery() can only be used with queries that return a
ResultSet"*. To export to Parquet, use a plain JDBC `Statement`:

```java
try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + path);
     Statement statement = connection.createStatement()) {
    statement.execute("COPY car TO 'cars.parquet' (FORMAT PARQUET)");
}
```

## Table and column names

Your SQL needs the names quackjvm chose. Ask rather than hard-code:

```java
database.table(cars)                       // "car"
database.column(cars, Car.MANUFACTURER)    // "manufacturer"
database.columns(cars)                     // every column
System.out.println(database.describe());   // the whole database
```

Aggregates need a **columnar layout** — with the default BLOB layout the only column is the
serialised object, so there is nothing to sum. See
[Storing objects](storing-objects.md#which-to-choose).

## Without CQEngine

`Rows` needs only a connection, so the core module on its own is a perfectly good typed SQL layer:

```java
try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
    long rows = Rows.of(connection.duplicate(), "SELECT count(*) FROM 'data/*.parquet'")
                    .scalar(Long.class);
}
```

`Rows.of` takes ownership of the connection it is given and closes it, so hand it a
`duplicate()` — duplicates share the same in-memory database.

The runnable version is [`examples/CoreColumnarRecords.java`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CoreColumnarRecords.java).
