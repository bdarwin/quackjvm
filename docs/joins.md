# Joins across collections

This is the thing an on-heap CQEngine collection cannot do well. CQEngine has `existsIn`, but it
evaluates it by iterating one collection and probing the other once per object. When both
collections live in one DuckDB database, the same query becomes a single semi-join.

Everything on this page needs a **`DuckDBDatabase`**: collections must share one database for the
tables to be joinable.

```java
try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("256MB").build()) {

    IndexedCollection<Vehicle> vehicles = database.collection(Vehicle.VEHICLE_ID)
            .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class))
            .build();
    IndexedCollection<Person> people = database.collection(Person.PERSON_ID)
            .columnarLayout(ColumnarLayout.ofRecord(Person.class))
            .build();

    vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
    people.addIndex(DuckDBIndex.onAttribute(Person.COUNTRY));
    ...
}
```

A **columnar layout is required** for the filtering side of a join, because it is what turns the
object's fields into real columns SQL can see. With the default BLOB layout the join still runs,
but the conditions cannot be evaluated in the database.

Closing the database closes every collection in it.

There are three ways to ask, in increasing order of power.

## 1. `existsIn` — stock CQEngine syntax

No new API. Write the query CQEngine has always supported and it is translated into one statement.

```java
// Vehicles whose owner lives in France.
try (ResultSet<Vehicle> results = vehicles.retrieve(
        existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID, equal(Person.COUNTRY, "FR")))) {
    System.out.println(results.size() + " vehicles");
}
```

It composes with ordinary conditions, still as a single statement:

```java
vehicles.retrieve(and(
        greaterThan(Vehicle.PRICE, 45_000.0),
        existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID, equal(Person.COUNTRY, "DE"))));
```

Negate it with `not(...)` for the anti-join — "vehicles whose owner is not in France":

```java
vehicles.retrieve(not(existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID,
                               equal(Person.COUNTRY, "FR"))));
```

Note that a vehicle whose `ownerId` matches nobody at all is included, as SQL's `NOT EXISTS` would
include it. CQEngine has no `notExistsIn` factory; `not(existsIn(...))` is the spelling.

Why this matters, measured on 80,000 vehicles against 20,000 people:

| | time |
|---|---|
| CQEngine evaluating `existsIn` object by object | **129 s** |
| translated to one SQL semi-join | **0.27 s** |

That is not a tuning difference. The nested-loop form is O(left x right) and degrades as either
side grows; the semi-join is one hash join over two columns.

## 2. `join()` — the matched pairs

`existsIn` filters one collection. It cannot tell you *which* person each vehicle matched. For that,
use `database.join(...)`, which returns both sides.

```java
try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
        .on(Vehicle.OWNER_ID, Person.PERSON_ID)
        .whereRight(equal(Person.COUNTRY, "IE"))
        .whereLeft(greaterThan(Vehicle.PRICE, 49_000.0))
        .stream()) {

    pairs.forEach(pair -> System.out.println(pair.left() + " <-> " + pair.right()));
}
```

`JoinPair` is a record with `left()` and `right()`. The stream holds a database connection, so
**close it** — a try-with-resources around the stream is the whole discipline.

If you only need the number of matches, `count()` answers in the database without rebuilding a
single object:

```java
long matches = database.join(vehicles, people)
        .on(Vehicle.OWNER_ID, Person.PERSON_ID)
        .whereRight(equal(Person.COUNTRY, "IE"))
        .count();
```

`whereLeft` and `whereRight` are both optional; omit them for an unfiltered join. Both take
ordinary CQEngine `Query` objects, so everything in [Querying](querying.md) applies.

## 3. `sql()` — anything at all

Once both collections are tables in one database, SQL is available over them directly. This covers
grouping, aggregation, window functions, pivots, `UNION`, CTEs — everything CQEngine has no answer
for.

The one problem is naming: you did not choose the table names, quackjvm did. So ask for them:

```java
database.table(vehicles)                          // -> "vehicle"
database.column(vehicles, Vehicle.OWNER_ID)       // -> "ownerId"
database.columns(vehicles)                        // -> every column name
```

Using those accessors rather than hard-coded strings means your SQL keeps working if a collection
is renamed.

```java
try (Stream<SqlRow> rows = database.sql(
        "SELECT p.country, count(*) AS vehicles, round(avg(v.price), 2) AS avgPrice "
      + "FROM " + database.table(vehicles) + " v "
      + "JOIN " + database.table(people) + " p "
      + "  ON v." + database.column(vehicles, Vehicle.OWNER_ID)
      + "   = p." + database.column(people, Person.PERSON_ID) + " "
      + "GROUP BY 1 ORDER BY 2 DESC")) {

    rows.forEach(row -> System.out.printf("  %-3s %,8d vehicles, average %,10.2f%n",
            row.getString("country"), row.getLong("vehicles"), row.getDouble("avgPrice")));
}
```

Output:

```
  US    16,250 vehicles, average  25,997.46
  FR    16,250 vehicles, average  26,003.54
  IE    16,250 vehicles, average  25,998.46
  DE    16,250 vehicles, average  25,999.46
```

Pass parameters rather than concatenating values — the varargs are bound as JDBC parameters:

```java
database.sql("SELECT count(*) FROM " + database.table(vehicles) + " WHERE price > ?", 45_000.0);
```

For results you want as typed Java values rather than rows, `database.query(...)` returns a
[`Rows`](aggregates.md) instead of a raw stream. It is almost always the nicer call.

## Seeing what is in there

When you are writing SQL by hand, `describe()` prints the whole database — every collection, its
object type, its layout, and its columns:

```java
System.out.println(database.describe());
```

```
DuckDB database (in memory), queryable with sql():
  person  [Person, columnar]
      columns: objectKey INTEGER, personId INTEGER, country VARCHAR, name VARCHAR
  vehicle  [Vehicle, columnar]
      columns: objectKey INTEGER, vehicleId INTEGER, make VARCHAR, ownerId INTEGER, price DOUBLE
```

`objectKey` is the primary key column quackjvm maintains; the rest are your object's fields.

## Choosing between the three

| you want | use |
|---|---|
| objects from one collection, filtered by another | `existsIn` — no code change |
| both sides of each match | `database.join(...).stream()` |
| a count, sum, group, pivot, or anything else | `database.query(...)` / `database.sql(...)` |

A full runnable version of all three is [`examples/CqEngineJoins.java`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CqEngineJoins.java).
