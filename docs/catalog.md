# Catalog

What is in the database, and what it means - for anything that has to decide what to query before it
can query it.

```java
Catalog catalog = Catalog.of(connection);

for (TableInfo table : catalog.tables()) { ... }
TableInfo car = catalog.describe("car");
Profile profile = catalog.profile("car");
```

`TableInfo.toText()` and `Profile.toText()` render what they hold as a few lines, which is usually what
you want to hand to something that reads text:

```
car (~200,000 rows) - cars we have sold, one row each
  carId INTEGER not null - our own id for the car
  make VARCHAR - who made it
  price DOUBLE in USD - what it sold for
  mileage INTEGER in km - on the clock at sale
```

## Descriptions live in the database

As DuckDB comments, written with `COMMENT ON`. They survive in a file database, travel with the file,
and can be read with plain SQL by anything at all:

```sql
SELECT table_name, column_name, comment FROM duckdb_columns() WHERE comment IS NOT NULL;
```

Not in a registry of ours that would have to be kept in step with the schema.

### Saying it once, in the layout

```java
ColumnarLayout.builder(Car.class)
        .describingTable("cars we have sold, one row each")
        .column("carId", Integer.class, Car::carId).describing("our own id for the car")
        .column("price", Double.class, Car::price).describing("what it sold for").unit("USD")
        .rowFactory(...)
        .build();
```

`describing(...)` and `unit(...)` apply to the column just added. `TableWriter.createTable` writes them
when it creates the table, and `Catalog.apply(table, layout)` writes them onto a table that already
exists. Column definitions carry them too, without a layout:

```java
new ColumnDef("weight", Double.class).describedAs("as weighed on arrival").measuredIn("kg")
```

### How a unit is stored

A comment is one string and this needs to be two, so a column that has a unit is stored as a small JSON
object:

```
{"description":"on the clock at sale","unit":"km"}
```

A comment that is *not* one of those - written by hand, by a migration, by anything - is read as a plain
description. Nothing has to know about the convention for it to work.

## Java types

`describe(table)` fills in the Java type quackjvm maps each SQL type to. `describe(table, layout)` uses
the layout instead, matched by column name, so a column the layout does not have keeps the mapped type
rather than a guess. A type with no single Java equivalent - `VARCHAR[]`, `STRUCT(...)`, `MAP(...)` -
comes back with a null Java type and its SQL type intact; `Catalog.isMappable(sqlType)` says which is
which.

## What is actually in a table

```java
Profile profile = catalog.profile("car", 5);   // 5 example rows
```

`SUMMARIZE` does the work - min, max, approximate distinct count, mean, standard deviation, null
percentage per column - plus an exact row count and a few rows to look at.

## What it costs

Measured on this machine, DuckDB 1.5.5:

| | |
|---|---|
| `describe` - the schema only | 1 ms, whatever the table holds (1.2 ms on 5,000,000 rows) |
| `profile` of 200,000 rows x 4 columns | 32 ms |
| `profile` of 1,000,000 rows x 4 columns | 34 ms |
| `profile` of 10,000,000 rows x 4 columns | 307 ms |

`SUMMARIZE` is a full scan, so `profile` grows with the table while `describe` does not. At 307 ms for
ten million rows there is nothing here worth caching: **a cached variant tied to a materialization was
considered and left out**, because the honest advice is cheaper than a cache - describe whenever you
like, profile when something actually has to decide.

See `examples/src/main/java/CoreCatalog.java` for all of it, with its real output.
