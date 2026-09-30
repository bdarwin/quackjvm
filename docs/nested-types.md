# Nested types

Columns that hold more than one value: `LIST`, `STRUCT`, `MAP`, and an `ARRAY` of fixed size.

```java
record Owner(String name, Integer age, LocalDate since) {}
record Doc(Integer id, String title, List<String> tags, Map<String, Integer> counts,
           Owner owner, float[] embedding) {}

ColumnarLayout<Doc> layout = ColumnarLayout.ofRecord(Doc.class);
new TableWriter("doc", layout.toColumnDefs(), false, 16).createTable(connection, true);
```

becomes

```
id        INTEGER
title     VARCHAR
tags      VARCHAR[]
counts    MAP(VARCHAR, INTEGER)
owner     STRUCT("name" VARCHAR, age INTEGER, since DATE)
embedding FLOAT[]
```

The record's own types are enough: a record component's generic type is not erased, so
`List<String>` says what it holds. Reading back through `Rows.records(Doc.class)` gives the lists,
maps, records and arrays again, nulls and empties included.

## Written through the appender, not through Arrow

The roadmap said this would need Arrow, because "the appender's native entry points are scalars only".
That was true of an older driver. In **1.5.5 the appender takes nested values directly** - a
`Collection` for a list, a `Map` for a map, `beginStruct`/`endStruct` for a struct, a `float[]` for a
fixed-size array - so quackjvm writes them that way, and **features that need nested types add no
dependency at all**. `CoreNestedTypes` is the evidence.

One consequence: a prepared statement cannot bind a list or a struct, so a table with nested columns is
always written through the appender, however few rows there are. `TableWriter` does that for you.

## Saying it explicitly

For a raw `List`, or for a fixed-size vector:

```java
ColumnarLayout.builder(Doc.class)
        .listColumn("tags", String.class, Doc::tags)
        .mapColumn("counts", String.class, Integer.class, Doc::counts)
        .structColumn("owner", Owner.class, Doc::owner)
        .vectorColumn("embedding", 768, Doc::embedding)     // FLOAT[768], not FLOAT[]
        .rowFactory(...)
        .build();
```

A raw `List` in a record is refused with a message pointing at `listColumn`, rather than guessed.

### Why the fixed size matters

An array's length is not part of its Java type, so a `float[]` field becomes `FLOAT[]`. DuckDB's array
functions - `array_cosine_similarity`, `array_distance` - and any index over them need `FLOAT[n]`, so a
vector column has to say its size. Writing a vector of the wrong length is refused, naming both lengths.

## One level

A list of scalars, a struct of scalars, a map of scalars, a vector of numbers. A **list of structs, or a
struct holding a list, is refused when the layout is built** - quackjvm's limit, not DuckDB's - with a
message saying to flatten it or keep it in a table of its own.

## What it costs

Writing `FLOAT[768]`, generated row by row rather than held in memory:

| rows | floats | time | rate |
|---|---|---|---|
| 20,000 | 59 MB | 0.67 s | 29,700 rows/s, 87 MB/s |
| 100,000 | 293 MB | 3.44 s | 29,000 rows/s, 85 MB/s |

Linear in the bytes. A million rows of `FLOAT[768]` is 3 GB of floats before DuckDB sees any of them,
which is why the rate is the number worth quoting rather than a total.

## Reading them back

`Rows.records(...)` handles nested columns through JDBC, which hands a list or a struct over whole.
Arrow reads columns of single values, so a record with nested columns takes the JDBC path even when
Arrow is on the classpath - which costs nothing here, because the values arrive as objects either way.

See `examples/src/main/java/CoreNestedTypes.java` for all of it, with its real output.
