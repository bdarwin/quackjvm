# Aggregates and projections

**This is the single biggest performance lever in quackjvm.** It is also the part that has no
equivalent in an object query engine, so it is easy to miss.

The dominant cost of a columnar store is not finding your data — it is turning columns back into
Java objects. Most questions do not need the objects at all. Asking DuckDB for the *answer* rather
than for the *rows* skips that step entirely.

```java
// 200,000 matching cars, summed.
// 99.8 ms if you materialise them. 2.3 ms if you do not.
double total = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
                       .scalar(Double.class);
```

Measured on 1,000,000 cars, 200,000 matching:

| | time | |
|---|---|---|
| retrieve matching objects, sum in Java | 99.8 ms | what an object query engine makes you do |
| `query(...).scalar(Double.class)` | **2.3 ms** | **44x faster** |
| `query(...).list(Double.class)` — one column, 200k rows | 11.6 ms | 8.6x |
| `query(...).records(MakeStats.class)` — grouped | 5.3 ms | – |

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
