# Troubleshooting

Every error you are likely to hit, what it actually means, and the fix. The messages below are the
real ones from the source, so you can search this page for what you see in your log.

## `No DuckDB column type is defined for Java type: com.example.Address`

```
No DuckDB column type is defined for Java type: com.example.Address. Supported types are
the primitive wrappers, String, BigInteger, BigDecimal, UUID, byte[], enums, java.util.Date,
the java.sql date/time types, and the java.time types
LocalDate/LocalTime/LocalDateTime/Instant/OffsetDateTime.
```

A field in your columnar layout has a type DuckDB cannot store as a column. This is thrown when the
layout is **built**, not at the first insert, so you find out immediately.

Three ways out:

```java
// 1. Leave that field out of the layout and map the parts you query on.
ColumnarLayout.<Order>builder(Order.class)
        .column("orderId", Integer.class, Order::getOrderId)
        .column("city", String.class, o -> o.getAddress().getCity())
        .build();

// 2. Store the whole object as a BLOB instead - any class works, nothing is queryable in SQL.
DuckDBPersistence.onPrimaryKey(Order.ORDER_ID);

// 3. Store it as a String or a JSON column and parse on the way out.
```

See [Storing objects](storing-objects.md#supported-types) for the full type table.

## `A columnar layout must have at least one column: com.example.Thing`

`ColumnarLayout.reflective(...)` found no usable fields — usually because every field has an
unsupported type, or because the class has no fields at all. Use the explicit `builder()` form and
name the columns you want.

## `No canonical constructor found for record com.example.Thing`

`ColumnarLayout.ofRecord(...)` was given a class that is not a record, or a record whose canonical
constructor is not accessible. Use `reflective(...)` or `builder(...)` for non-records.

## `A rowFactory is required to rebuild com.example.Thing from its columns`

The layout can take objects apart but not put them back together. `ofRecord` and `reflective`
supply a factory for you; the `builder()` form needs one:

```java
ColumnarLayout.<Car>builder(Car.class)
        .column("carId", Integer.class, Car::getCarId)
        .column("make", String.class, Car::getMake)
        .rowFactory(values -> new Car((Integer) values[0], (String) values[1]))
        .build();
```

## `Cannot join on 'ownerId'`

```
Cannot join on 'ownerId': it is neither the primary key of its collection nor indexed with
DuckDBIndex. Add an index on it, or join on the primary key.
```

A join needs a column it can look values up in. Either add the index:

```java
vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
```

…or join on the primary key instead. Note an on-heap CQEngine index does not count here — the join
runs inside DuckDB and needs a DuckDB index table.

## `Collection 'car' has no column 'manufacturer'`

```
Collection 'car' has no column 'manufacturer'. Its columns are [objectKey, value]. It stores
objects as BLOBs, so its fields are not columns; build it with a ColumnarLayout to query them
in SQL.
```

You asked for `database.column(cars, Car.MANUFACTURER)` on a BLOB-backed collection. The object is
one opaque column, so its fields are invisible to SQL. Rebuild the collection with a columnar
layout.

The other form of the same message — *"Attribute names must match the names in its
ColumnarLayout"* — means the collection *is* columnar but the attribute's name does not match any
column. CQEngine attribute names and layout column names must agree; check `database.describe()`
for what the columns actually are.

## `A DuckDBIndex on attribute 'price' requires the IndexedCollection to be configured with DuckDBPersistence, but found: ...`

You added a `DuckDBIndex` to a plain on-heap collection. Either give the collection a
`DuckDBPersistence`, or use CQEngine's own index types on it.

## `DuckDBIndex on attribute 'price' requires the persistence to be configured with a primary key attribute`

Every DuckDB-backed collection needs a primary key, because it is how rows are addressed. Use one
of the `onPrimaryKey*` factories or `builder(primaryKeyAttribute)`.

## `DuckDB cannot checkpoint while a transaction is open`

```
Failed to checkpoint ... DuckDB cannot checkpoint while a transaction is open, which usually
means a ResultSet from one of its collections has not been closed.
```

This is the most common runtime problem and it is almost always a leaked `ResultSet`:

```java
// wrong - holds a connection and a transaction for the life of the JVM
ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"));
for (Car car : results) { ... }

// right
try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
    for (Car car : results) { ... }
}
```

The same applies to `database.join(...).stream()` and `Rows.stream()`. Everything else in `Rows`
(`scalar`, `list`, `records`, `count`, `forEachRow`, `forEachValue`, `forEachRecord`) closes itself.

`compact()` surfaces this as an error. `optimize()` and `getBytesUsed()` treat the checkpoint as
housekeeping and carry on without it, so a leak can go unnoticed until you try to reclaim space.

## `DuckDBPersistence has been closed`

Something is still using a collection after its persistence, or the `DuckDBDatabase` that owns it,
was closed. Closing a database closes every collection in it — a `try (DuckDBDatabase database =
...)` block ends the life of everything inside.

## `Cannot append value of type java.lang.Long (declared attribute type java.lang.Integer) to DuckDB`

An attribute returned a value whose type does not match what the attribute declares. Usually a
`SimpleAttribute<Car, Integer>` whose `getValue` returns something widened to `Long`, or a layout
column declared with the wrong class.

## `Table 'cq_car' was built with orReplace=true, which requires a primary key on its key column`

Only reachable when using `TableWriter` from `quackjvm-core` directly. Create the table with
`createTable(connection, true)` so it gets a primary key, or construct the writer with
`orReplace=false`.

## `Arrow needs --add-opens=java.base/java.nio=ALL-UNNAMED on Java 17 and later`

Arrow is on the classpath but the JVM flag is not set. Add it, or remove the Arrow dependencies and
let the JDBC path be used. To check at runtime:

```java
if (!ArrowSupport.isAvailable()) {
    System.out.println(ArrowSupport.getUnavailableReason());
    System.out.println("Add: " + ArrowSupport.getRequiredJvmFlag());
}
```

## A duplicate key fails only at `flush()`

`DuckDBBulkWriter` appends rather than merges, so a repeated primary key is not detected when you
call `add` — it surfaces at the next flush, as a primary key constraint violation. If your stream
can contain duplicates, use `collection.addAll(...)`, which replaces.

## `InaccessibleObjectException`, or Kryo failing on a record

This happens with **stock CQEngine's** serializer, not with quackjvm's. CQEngine 3.6.0 pins Kryo
5.0.0-RC1, which cannot serialize records at all on Java 17+ and throws
`InaccessibleObjectException` for anything else unless you start the JVM with
`--add-opens java.base/java.util=ALL-UNNAMED`.

quackjvm depends on a current Kryo and uses its own `KryoPojoSerializer` by default, which needs no
flags and handles records. If you see this, you have selected a different serializer with
`@PersistenceConfig(serializer = ...)`.

Fields holding the JDK's internal collection wrappers (`Arrays.asList(...)`,
`Collections.unmodifiableList(...)`) still cannot be serialized without `--add-opens`. Use a plain
`ArrayList`, or store those objects columnar.

## Memory is much higher than expected

You have not set a memory limit. DuckDB's default is 80% of system RAM and it will use a large
share of that for its buffer pool no matter how small the data is — 939 MB versus 222 MB for the
same million objects in the benchmark.

```java
DuckDBPersistence.builder(Car.CAR_ID).memoryLimit("256MB").build();
```

See [Tuning](tuning.md#set-a-memory-limit).

Note also that **"in memory" does not mean "on the heap"**: an in-memory DuckDB database lives in
native memory, so it shows in the process RSS and not in `-Xmx`. That is the point — the garbage
collector never walks it.

## Queries are slower than expected

In rough order of how often it is the answer:

1. **The attribute is not indexed**, so every query scans. `cars.addIndex(DuckDBIndex.onAttribute(...))`.
2. **You are materialising objects you do not need.** A `sum` over 200,000 objects is 99.8 ms if you
   rebuild them and 2.3 ms if you ask DuckDB — see [Aggregates](aggregates.md).
3. **You have not called `optimize()`** after a bulk load. Counting stops growing with collection
   size once you do.
4. **The collection was not built through `DuckDBDatabase`**, so compound `and`/`or` queries are
   not pushed down as one statement. `new ConcurrentIndexedCollection<>(persistence)` works but
   gives that up; `database.collection(pk).build()` does not.
5. **Arrow is not on the classpath**, so wide results are read one boxed value at a time.
6. **You are making very many tiny queries.** Every query has a floor around 0.5 ms inside DuckDB.
   This is the one case where an on-heap collection is simply the better tool — see
   [Querying](querying.md#what-a-query-costs).

## A query returns objects twice

That is CQEngine's `or` behaviour, not quackjvm's: an object matching both branches is returned
once per branch. Ask for deduplication:

```java
cars.retrieve(or(equal(Car.MANUFACTURER, "BMW"), equal(Car.COLOUR, RED)),
              queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION)));
```

## Enum values come back as numbers in SQL

Enums are stored as their **ordinal**, which is what makes them indexable (CQEngine's own SQLite
indexes reject enums outright). Java-side round-trips are exact; hand-written SQL sees an integer.
Compare against the ordinal, or store the enum's `name()` as a `String` column if you want readable
SQL.

## `OffsetDateTime` comes back with a different offset

DuckDB's `TIMESTAMP WITH TIME ZONE` stores an **instant**, not an offset. The moment in time is
preserved exactly; the original offset is not. If you need the original zone, store it in its own
column.

## Two processes cannot open the same file

DuckDB does not support several processes writing one database file. One JVM process per file; all
connections inside it are duplicated from a single open database.
