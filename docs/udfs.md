# Java functions in SQL

A Java method called from SQL, without writing a callback by hand.

```java
Udfs.register(connection, "region_of", String.class, String.class, Countries::regionOf);

Rows.of(connection, "SELECT region_of(country), count(*) FROM sale GROUP BY 1");
```

The arguments are, in order: the connection, the name SQL will use, the return type, the argument
types, and the method. One, two or three arguments.

```java
Udfs.register(connection, "with_tax", Double.class, Double.class, Double.class,
        (price, rate) -> price * (1 + rate));

Udfs.register(connection, "clamp", Double.class, Double.class, Double.class, Double.class,
        (value, low, high) -> Math.min(Math.max(value, low), high));
```

## Nulls, decided rather than assumed

`register` **never calls the method with a null**: a row with a null argument is null in the result, and
the method is not called. That is what SQL expects of most functions, and it means a method that cannot
take a null does not have to say so.

`registerNullable` calls it with whatever is there, nulls included, and takes whatever it returns:

```java
Udfs.registerNullable(connection, "or_unknown", String.class, String.class,
        text -> text == null ? "UNKNOWN" : text);
```

Either way, a method may **return** null and the result is null.

## Types

Everything `DuckDBTypes` maps, except `UUID`, `byte[]` and `LocalTime`, which DuckDB's Java vectors have
no accessor for in 1.5.5 - refused when the function is registered, with a message saying to use a
`String`. A `Character` crosses as VARCHAR, an `Instant` as TIMESTAMP, an enum as its ordinal, exactly
as they are stored in a table.

## What it costs

Measured on 2,000,000 rows, `price * 1.2`:

| | | |
|---|---|---|
| built-in SQL expression | 0.9 ms | 0.5 ns/row |
| `Udfs.registerDouble` (no boxing) | 14.1 ms | 7.1 ns/row |
| `Udfs.register` (boxed) | 24.1 ms | 12.0 ns/row |

The same as writing the callbacks by hand - see `CoreScalarFunction` - so the wrapper is free. What the
numbers say is about UDFs, not about the wrapper: **a call into Java is fifteen to twenty-five times a
built-in expression**, so a UDF is for what SQL cannot say - a lookup that lives in Java, a rule nobody
wants to maintain twice - rather than for arithmetic SQL already has.

For simple arithmetic over a numeric column there are primitive forms that do not box:

```java
Udfs.registerDouble(connection, "vat", (double price) -> price * 1.2);
Udfs.registerInt(connection, "twice", value -> value * 2);
Udfs.registerLong(connection, "half", value -> value / 2);
```

## Taking one away

There is nothing to close: DuckDB 1.5.5 has no way to unregister a function. Registering the same name
again replaces what it does, which the tests rely on.

See `examples/src/main/java/CoreUdfs.java` for all of it, with its real output.
