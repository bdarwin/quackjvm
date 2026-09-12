# Getting started

quackjvm is two Maven artifacts. Take only the one you need.

| artifact | what it gives you | depends on |
|---|---|---|
| `io.github.bdarwin:quackjvm-core` | DuckDB for the JVM: Java-to-DuckDB type mapping, columnar object layouts, bulk loading, a connection pool, and a small SQL API. No query framework. | DuckDB's JDBC driver |
| `io.github.bdarwin:quackjvm-cqengine` | A DuckDB-backed `Persistence` for CQEngine, plus indexes, joins and a bulk writer. | the core, and CQEngine |

## Requirements

- Java 17 or later.
- On JDK 22 and later, run with `--enable-native-access=ALL-UNNAMED`; DuckDB's driver loads a
  native library and the JVM otherwise warns about it.
- Apache Arrow is optional. With `arrow-vector`, `arrow-c-data` and `arrow-memory-unsafe` on the
  classpath, results are read as columnar batches; without them quackjvm falls back to reading
  values through JDBC. Arrow also needs `--add-opens=java.base/java.nio=ALL-UNNAMED`.

## Adding the dependency

```xml
<dependency>
    <groupId>io.github.bdarwin</groupId>
    <artifactId>quackjvm-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add `quackjvm-cqengine` instead if you want the CQEngine plugin; it brings the core with it.

Gradle:

```groovy
implementation 'io.github.bdarwin:quackjvm-core:1.0.0'
// or
implementation 'io.github.bdarwin:quackjvm-cqengine:1.0.0'
```

## Core: objects as columns, queried with SQL

Nothing here knows about CQEngine. A `ColumnarLayout` says how to shred an object into one typed
column per field and how to rebuild it; `TableWriter` writes rows; `SqlQuery` reads them back.

```java
record Reading(int sensorId, String site, double celsius, LocalDate day) {}

ColumnarLayout<Reading> layout = ColumnarLayout.ofRecord(Reading.class);

List<ColumnDef> columns = layout.getColumns().stream()
        .map(column -> new ColumnDef(column.getName(), column.getType()))
        .toList();

try (DuckDBConnection connection =
             (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

    TableWriter writer = new TableWriter("reading", columns, true,
            TableWriter.DEFAULT_APPENDER_THRESHOLD);
    writer.createTable(connection, true);
    writer.write(connection, rows.iterator(), true);

    // SqlQuery closes the connection it is given when the stream is closed, so give it a
    // duplicate of the root connection rather than the root connection itself.
    try (Stream<SqlRow> rows = SqlQuery.stream(connection.duplicate(),
            "SELECT site, avg(celsius) AS avgCelsius FROM reading GROUP BY site")) {
        rows.forEach(row -> System.out.println(row.getString("site") + " " + row.getDouble("avgCelsius")));
    }
}
```

Two things to know before you write this yourself:

- `TableWriter`'s `orReplace` flag and `createTable`'s `primaryKeyOnKeyColumn` flag have to agree.
  `INSERT OR REPLACE` needs a key to conflict on, so `orReplace = true` with a table created
  without a primary key fails at the first write.
- A batch larger than the appender threshold is streamed through a temporary staging table in
  bounded chunks, so an iterator of a million rows never has to exist in memory as a list.

Working code: [`examples/src/main/java/CoreColumnarRecords.java`](../examples/src/main/java/CoreColumnarRecords.java)
and [`CoreBulkLoad.java`](../examples/src/main/java/CoreBulkLoad.java).

## CQEngine: moving a collection off the heap

One argument to the constructor, and the objects and their indexes live in DuckDB rather than in
the Java heap. The query code does not change.

```java
// before
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();
cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));

// after
DuckDBPersistence<Car, Integer> persistence = DuckDBPersistence.builder(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .memoryLimit("256MB")
        .build();
IndexedCollection<Car> cars = new DuckDBIndexedCollection<>(persistence);
cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));

// unchanged
cars.retrieve(and(equal(Car.MANUFACTURER, "Ford"), between(Car.PRICE, 20_000.0, 21_000.0)));
```

- Use `DuckDBIndexedCollection` rather than `ConcurrentIndexedCollection` where you can. Both work,
  but only the former sends a whole `and`/`or`/`not` expression to DuckDB as a single statement;
  CQEngine otherwise intersects the branches in Java, rebuilding objects only to discard them.
- Add indexes before loading data. An index added afterwards is correct, but it is built by
  rescanning the object table.
- Close every `ResultSet`. It holds a database connection until it is closed.
- Never write one object at a time in a loop. `add()` is a transaction; use `addAll` or
  `DuckDBBulkWriter`.
- Close the persistence, or the `DuckDBDatabase` if several collections share one.

Working code: [`examples/src/main/java/CqEngineSwap.java`](../examples/src/main/java/CqEngineSwap.java)
and [`CqEngineIndexes.java`](../examples/src/main/java/CqEngineIndexes.java).

## CQEngine: two collections in one database

Collections which share a `DuckDBDatabase` are separate tables in the same DuckDB instance, which
is what lets them be joined.

```java
try (DuckDBDatabase database = DuckDBDatabase.inMemory()) {
    IndexedCollection<Vehicle> vehicles = database.collection(Vehicle.VEHICLE_ID)
            .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class))
            .build();
    IndexedCollection<Person> people = database.collection(Person.PERSON_ID)
            .columnarLayout(ColumnarLayout.ofRecord(Person.class))
            .build();

    // CQEngine's own syntax, evaluated as one SQL semi-join
    vehicles.retrieve(existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID,
            equal(Person.COUNTRY, "FR")));

    // the matched pairs, which CQEngine has no way to express
    try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
            .on(Vehicle.OWNER_ID, Person.PERSON_ID)
            .whereRight(equal(Person.COUNTRY, "FR"))
            .stream()) {
        pairs.forEach(pair -> System.out.println(pair.left() + " " + pair.right()));
    }

    // and arbitrary SQL over both
    try (Stream<SqlRow> rows = database.sql(
            "SELECT p.country, count(*) AS vehicles FROM vehicle v "
          + "JOIN person p ON v.ownerId = p.personId GROUP BY 1")) {
        rows.forEach(System.out::println);
    }
}
```

A columnar layout is required for any of this: a collection stored as BLOBs has only a key and an
opaque blob, so SQL cannot see its fields. `database.describe()` prints every collection, the name
to use for it in `sql(...)`, and its columns.

Working code: [`examples/src/main/java/CqEngineJoins.java`](../examples/src/main/java/CqEngineJoins.java).

## Next

- [Storing objects](storing-objects.md) — BLOB or columnar, and which types are supported
- [Querying](querying.md) — what is pushed into SQL and what is not
- [Joins across collections](joins.md) — `existsIn`, matched pairs, and arbitrary SQL
- [Aggregates and projections](aggregates.md) — the biggest performance lever here
- [Writing data](writing.md) — batching, `BULK_IMPORT`, and the streaming bulk writer
- [Tuning](tuning.md) — start with `memoryLimit` and `optimize()`
- [Troubleshooting](troubleshooting.md) — every error you are likely to see
- [Migrating](migrating.md) — from on-heap CQEngine, or from its SQLite persistence
- [API overview](api-overview.md) — class by class
- [examples/README.md](../examples/README.md) — every example, and how to run them
