# Live tables

Java objects queried as a table, with no load step and nothing copied.

```java
List<Car> cars = ...;                                  // an ordinary list, still yours
LiveTable table = LiveTables.register(connection, "car", cars, ColumnarLayout.ofRecord(Car.class));

Rows.of(connection, "SELECT make, count(*) FROM car GROUP BY 1");
Rows.of(connection, "SELECT c.make, sum(s.amount) FROM car c JOIN sale s ON s.carId = c.carId GROUP BY 1");
```

Underneath: a DuckDB table function - which the Java client gained in 1.5 - plus a view over it, so SQL
says `car` rather than `car()`. The columns come from a `ColumnarLayout`, the same description that
stores objects in a table, so the two have the same schema.

## What to expect of it

**It is live, not a snapshot.** Each query iterates the collection as it is then; add an object and the
next query sees it. A change made *while* a query is running behaves as it would for any other
iteration of that collection - an `ArrayList` throws `ConcurrentModificationException`, a
`CopyOnWriteArrayList` does not. When a query must see one consistent state:

```java
LiveTables.snapshot(connection, "car", cars, layout);   // copies once, at registration
```

**Only the columns asked for are read.** Projection pushdown is on: a query about `make` never calls
the `price` accessor. Measured in the example - 0 reads of `price` for a query that does not mention it.

**One thread per scan.** A Java `Iterable` cannot be split safely in general, so the scan is
single-threaded. What DuckDB does with the rows afterwards - joins, grouping, sorting - is still
parallel.

**A supplier works too**, for a collection that gets replaced wholesale:

```java
LiveTables.register(connection, "car", () -> cache.currentContents(), layout);
```

## Types

Every type `DuckDBTypes` maps, except three that DuckDB's Java chunk writer cannot write in 1.5.5 or 2.0:
`UUID`, `byte[]` (BLOB) and `LocalTime` (TIME). Registering a layout with one of those is **refused at
registration**, naming the column and saying to map it to `String` instead - rather than failing on the
first row of the first query.

Three more are declared as what quackjvm stores them as, because `addResultColumn` will not take them:
a `Character` becomes VARCHAR, an `Instant` becomes TIMESTAMP, an enum becomes INTEGER holding the
ordinal. A `BigDecimal` is declared `DECIMAL(38,10)` - the scale quackjvm stores decimals at - rather
than the driver's default of 18.

## What it costs

Measured on this machine, DuckDB 2.0.0, against loading the same objects with the appender first:

| objects | live: group by make | live: join to 200k stored rows | load first | then group | then join |
|---|---|---|---|---|---|
| 100,000 | 46.4 ms | 10.3 ms | 69.1 ms | 1.2 ms | 1.9 ms |
| 1,000,000 | 469.2 ms | 44.6 ms | 783.9 ms | 1.5 ms | 3.0 ms |

Reading the objects is the cost - about 470 ns an object for a string column, against 1.5 ms for the
same question over a stored table, which is columnar and compressed where this is a loop over the heap.
At a million objects the join costs a tenth of the group-by because it reads one `int` per object rather than a string:
that is the projection pushdown showing.

So: **live wins when the data would otherwise have to be loaded first, and when it changes under you.
Loading wins once the same rows are queried a second time** - on DuckDB 2.0 the load costs about two
live queries (784 ms against 469 at a million objects; on 1.5 it was about one, 479 ms), and every
query after that is free by comparison.

## Taking it away

```java
table.unregister();     // or close(), or try-with-resources
```

Drops the view and detaches the data, so the name stops answering. The table function itself stays
registered, because DuckDB (1.5.5 and 2.0) has no way to unregister one - a query written against the function
directly after that says the live table is gone rather than returning stale rows.

See `examples/src/main/java/CoreLiveTables.java` for all of it, with its real output.
