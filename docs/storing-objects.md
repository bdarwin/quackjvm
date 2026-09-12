# Storing objects

quackjvm stores each object as a row in a DuckDB table. You choose how the object's state is
written into that row, and the choice matters for size and for speed.

## The two layouts

### BLOB — works with any class, no mapping

```java
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
```

Each object is serialized into a single `BLOB` column. Nothing about your class has to change;
fields of any type work, including collections and nested objects.

The table looks like this:

| objectKey | value |
|---|---|
| 1 | `\x01\x0aFord\x05focus…` |

### Columnar — one typed column per field

```java
IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .build();
```

| objectKey | carId | manufacturer | model | price |
|---|---|---|---|---|
| 1 | 1 | Ford | focus | 12000.0 |

Now DuckDB can compress each column on its own — dictionary-encode `manufacturer`, run-length
encode repeated values, bit-pack small integers — and can read one column without touching the
others. It also means **your data is queryable with plain SQL** by any tool that can open a
DuckDB file.

## Which to choose

Measured on a million objects of eight fields:

| | BLOB | columnar |
|---|---|---|
| on disk | 51 MB | **36 MB** |
| iterate everything | 653 ms | **343 ms** |
| query returning 2% of the collection | 20.9 ms | **13.8 ms** |
| works with any field type | **yes** | scalar fields only |
| queryable as SQL | no | **yes** |

**Prefer columnar.** Use BLOB when a field's type cannot be a column — a `List`, a `Map`, a nested
object — or when you simply do not want to describe a layout.

Note that columnar only became the faster option once the Arrow read path landed; before that,
rebuilding an object from eight columns cost more than deserializing one blob. If Arrow is not on
your classpath, BLOB is the quicker of the two for reads.

## Describing a layout

### From a record — nothing to write

```java
public record Car(int carId, String manufacturer, String model, double price) {}

ColumnarLayout<Car> layout = ColumnarLayout.ofRecord(Car.class);
```

Components become columns in declaration order, and objects are rebuilt through the canonical
constructor. This is the least work and the least that can go wrong.

### From any class, reflectively

```java
public class Car {
    private final int carId;
    private final String manufacturer;
    // no no-arg constructor, fields are final - both fine
}

ColumnarLayout<Car> layout = ColumnarLayout.reflective(Car.class);
```

Every non-static, non-transient field becomes a column. Objects are allocated without calling a
constructor — the same technique deserialization libraries use — and fields are set reflectively.
Records are delegated to `ofRecord`, since their fields cannot be written.

### Explicitly, when you want control

```java
ColumnarLayout<Car> layout = ColumnarLayout.builder(Car.class)
        .column("carId", Integer.class, Car::getCarId)
        .column("manufacturer", String.class, Car::getManufacturer)
        .column("priceInCents", Long.class, car -> Math.round(car.getPrice() * 100))
        .rowFactory(values -> new Car(
                (Integer) values[0], (String) values[1], ((Long) values[2]) / 100.0))
        .build();
```

Use this to rename columns, to store a derived or narrowed value, or to skip fields you do not
need. The `rowFactory` receives the column values in the order the columns were added.

A layout is validated when it is built, not at the first insert: an unsupported column type throws
straight away with the offending type named.

## Supported types

| Java | DuckDB column |
|---|---|
| `String`, `CharSequence`, `Character` | `VARCHAR` |
| `Boolean` | `BOOLEAN` |
| `Byte` / `Short` / `Integer` / `Long` | `TINYINT` / `SMALLINT` / `INTEGER` / `BIGINT` |
| `Float`, `Double` | `FLOAT`, `DOUBLE` |
| `BigInteger` | `HUGEINT` |
| `BigDecimal` | `DECIMAL(38,10)` |
| `UUID` | `UUID` |
| `byte[]` | `BLOB` |
| any `enum` | `INTEGER` (the ordinal) |
| `LocalDate`, `java.sql.Date` | `DATE` |
| `LocalTime`, `java.sql.Time` | `TIME` |
| `LocalDateTime`, `Instant`, `java.util.Date`, `Timestamp` | `TIMESTAMP` |
| `OffsetDateTime` | `TIMESTAMP WITH TIME ZONE` |

Primitives are boxed automatically, so `int` and `Integer` are both fine.

Types are chosen to preserve Java's `Comparable` ordering, so a range query pushed into SQL returns
exactly what an on-heap index would. Two consequences follow from that, and both can bite:

**Enums are stored as their ordinal**, because Java orders enums by ordinal rather than by name.
`between(Car.COLOUR, RED, BLUE)` therefore means what it means in Java. The cost is that the stored
data depends on the declaration order of your enum constants: insert a new constant in the middle
and previously written data now decodes to the wrong value. Add constants at the end.

**`OffsetDateTime` keeps its instant, not its offset.** A `TIMESTAMP WITH TIME ZONE` column stores
a point in time, so a value written as `+02:00` comes back in the JVM's own zone. It is the same
instant; it is not the same object. Compare with `.toInstant()` if that matters.

## The primary key

Every collection needs a `SimpleAttribute` which uniquely identifies an object. It becomes the
table's primary key, which is what makes lookups and joins fast.

```java
public static final SimpleAttribute<Car, Integer> CAR_ID =
        new SimpleAttribute<>(Car.class, Integer.class, "carId") {
            @Override
            public Integer getValue(Car car, QueryOptions queryOptions) {
                return car.carId();
            }
        };
```

The key must never be null; an object whose key attribute returns null is rejected with a
`NullPointerException` naming the attribute. This requirement comes from CQEngine, not quackjvm —
any non-heap CQEngine persistence needs one.

## Where the data lives

```java
DuckDBPersistence.onPrimaryKey(Car.CAR_ID);                           // in memory (default)
DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, new File("c.duckdb"));  // a file
DuckDBPersistence.onPrimaryKeyInTempFile(Car.CAR_ID);                 // temp file, deleted on exit
```

**In memory does not mean on the heap.** The data lives in DuckDB's own native memory, so it is
bounded by DuckDB's `memory_limit` rather than by `-Xmx`, and the garbage collector never sees it.
See [Tuning](tuning.md#set-a-memory-limit) — this is the setting people most often miss.

A file-backed collection uses *less* resident memory than an in-memory one, because DuckDB can
evict pages it is able to re-read from disk.

## Table names

A collection of `Car` is stored in a table called `cq_car`, and its indexes in
`cqidx_car_<attribute>`. Two collections of the same type in one database need distinct names:

```java
database.collection(Car.CAR_ID).name("archivedCars").build();   // table cq_archivedCars
```

`database.describe()` prints every table and column, and `database.table(collection)` gives you the
name programmatically. Both are covered in [Aggregates and projections](aggregates.md).
