# Catalog

What is in the database, and what it means - for anything that has to decide what to query before it
can query it.

!!! note "In quackjvm-agent"
    This page is part of the `quackjvm-agent` module, not core: add `io.github.bdarwin:quackjvm-agent`
    beside `quackjvm-core`. `Catalog.of(quack)` borrows a connection per call.

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

## Example queries

```java
for (QueryExamples.Example example : catalog.examples("log")) {
    System.out.println("-- " + example.title() + "\n" + example.sql());
}
```

These are written from the columns alone, so nothing is read from the table. For a log with
columns `line`, `time`, `level`, `thread`, `logger` and `message`, the examples are:

```sql
SELECT count(*) AS rows FROM log
SELECT * FROM log LIMIT 10
SUMMARIZE log
SELECT date_trunc('hour', time) AS hour, count(*) AS rows FROM log WHERE time IS NOT NULL GROUP BY 1 ORDER BY 1
SELECT * FROM log WHERE message ILIKE '%error%' LIMIT 50
SELECT level, count(*) AS lines FROM log WHERE level IS NOT NULL GROUP BY 1 ORDER BY 2 DESC
SELECT date_trunc('minute', time) AS minute, count(*) AS errors FROM log WHERE upper(level) = 'ERROR' GROUP BY 1 ORDER BY 1
SELECT regexp_replace(message, '[0-9]+', '#', 'g') AS error, count(*) AS times FROM log WHERE upper(level) = 'ERROR' GROUP BY 1 ORDER BY 2 DESC LIMIT 20
SELECT logger, count(*) FILTER (WHERE upper(level) = 'ERROR') AS errors, count(*) FILTER (WHERE upper(level) IN ('WARN', 'WARNING')) AS warnings FROM log WHERE upper(level) = 'ERROR' OR upper(level) IN ('WARN', 'WARNING') GROUP BY 1 ORDER BY 2 DESC, 3 DESC LIMIT 20
SELECT line, time, message, CAST(regexp_extract(message, '([0-9]+(\.[0-9]+)?) ?ms\b', 1) AS DOUBLE) AS ms FROM log WHERE regexp_matches(message, '[0-9]+(\.[0-9]+)? ?ms\b') ORDER BY ms DESC LIMIT 20
```

Which examples a table gets:

| Example | When |
|---|---|
| A count, the first rows, `SUMMARIZE` | always. Wider than 20 columns: the first 10 columns only, as `SUMMARIZE` of all 1,502 columns of a 5,000-row table took 2.5 s |
| The range of a number | a number that measures something (not one that counts rows: see below) |
| Median, 95th and 99th percentiles (`quantile_cont`), and the rows with the highest value | a number that measures something: not one named `line`, `id`, `seq`, `..._id` and so on |
| Count, mean and highest by a group column | a text column with few values (see below) |
| Rows per hour (per month for a date), with the mean of the measure if there is one | a timestamp or date column. Rows without a time are left out rather than shown as a group of their own |
| A text search (`ILIKE '%error%'`) | a text column, preferring one named `message`, `text`, `body`... |
| Columns found by name (`FROM (DESCRIBE t) WHERE column_name ILIKE ...`) | wider than 20 columns |
| A family's mean; its mean per group and per minute; its total in each row (`list_sum([*COLUMNS(...)])`); the rows with its highest value (`greatest(*COLUMNS(...))`); each column's mean and highest, highest mean first (`UNPIVOT`, then grouped) |  three or more numbered columns of the same name and kind, like `cpu_0001` to `cpu_1500`. `UNPIVOT` only when they all have the same type |
| Lines per level, errors per minute, the most frequent errors with numbers blanked, errors and warnings by logger, durations written in the messages (`... 345 ms`) slowest first | a log: a text `level` and `message`, any case. The ones needing `time` or `logger` only when those are there |
| A join, and the other table's first rows | another table with a column of the same name and kind, up to three |

**The group column.** Told how many distinct values each column holds, the examples group by the
first text column with at most 1,000:

```java
Profile profile = catalog.profile("log");                        // reads the whole table
catalog.examples("log", profile.distinctValues());               // or counts the application has
```

Told nothing, they go by the name (`level`, `severity`, `host`, `status`, `kind`, `type`...), else
the first text column not named like free text. A column the counts leave out is judged by its name.

- **Families of numbered columns** are left out of the other examples, so a 1,500-column table
  doesn't get 1,500 range queries. `COLUMNS('^cpu_[0-9]+$')` must pick exactly the family: a
  pattern that would catch another column means no family.
- **Quoting.** Names that need quotes get them, a quote in a family's name is doubled in the
  pattern, and a dot is escaped so it doesn't catch a similar column.
- **Every example runs as written.** The tests run each one through `GuardedQuery` in a hardened
  database, on an empty table and on a full one: a 5,000-line log, a 1,504-column table, quoted
  and reserved names, every kind of column (numbers, dates, time zones, blobs, lists, structs).
  Where Java can work out the answer - lines per level, percentiles, a row's total over 1,500
  columns, the slowest duration - the tests check it.

## Which columns a query reads

A column cache filled on demand - one table per column of a file too wide to load whole - has to know
which columns a query needs before running it. `QueryColumns` asks DuckDB to plan the query and reads
the answer off the plan:

```java
try (QueryColumns planner = QueryColumns.of(quack)) {
    QueryColumns.Read read = planner.read("SELECT host, sum(metric_7) FROM t WHERE metric_9 > 3 GROUP BY host");
    read.columns();     // {t=[metric_9, host, metric_7]}
}
```

A query that calls a Java function can be planned once the function is registered on the planner's
database too: `planner.register(c -> Udfs.register(c, "label_time", String.class, Long.class, fn))`.
It is kept across `refresh()`, and is not called, since planning reads no rows.

The plan's table scans say what they read. Planning happens in a database of its own - in memory,
with no file access, holding empty copies of the source's tables and views - so the data is never
touched and the query never runs. Three of DuckDB's optimizers are off there, because each hides a
column: two see the copies are empty and drop the scans, and filter pushdown moves a filter's column
out of the scan's list. Turned back on, four of the tests fail for exactly that.

Measured on DuckDB 2.0.0:

| query | columns read |
|---|---|
| `SELECT host, sum(metric_7) FROM t WHERE metric_9 > 3 GROUP BY host` | metric_9, host, metric_7 |
| `SELECT count(*) FROM t` | none - the table is counted, not read |
| `SELECT * EXCLUDE (host) FROM t` | all but host |
| `SELECT COLUMNS('metric_1.?') FROM t` | metric_1, metric_10 ... metric_19 |
| `SELECT a.host FROM t a JOIN t b ON a.line = b.line + 1 WHERE b.metric_3 = 1` | line, host, metric_3 - both scans |
| `SELECT "odd, ""name" FROM t ORDER BY metric_5 LIMIT 3` | `odd, "name`, metric_5 |
| a view over `t` | the view's columns of `t`, its filter's included |
| `DESCRIBE t` | nothing scanned |

**It says when it cannot tell**, and the answer then is every column: more than one statement; a
file, which the planner cannot reach; a table outside `main`; and any `PIVOT`. DuckDB rewrites a pivot
into several statements and plans only the last, and on 2.0.0 the plan of `PIVOT t ON host USING
sum(metric_8)` names every column except `host` and `metric_8` - the two it needs. Measured, so a
pivot is never answered.

On a table of 6,000 columns, the planner is made in 82 ms, a query reading three of them is answered
in 3 ms, and `SELECT *` in 158 ms.

## What it costs

Measured on this machine, DuckDB 2.0.0:

| | |
|---|---|
| `describe` - the schema only | 2 ms, whatever the table holds |
| `profile` of 200,000 rows x 4 columns | 42 ms |
| `profile` of 1,000,000 rows x 4 columns | 36 ms |
| `profile` of 10,000,000 rows x 4 columns | 279 ms |

`SUMMARIZE` is a full scan, so `profile` grows with the table while `describe` does not. At 279 ms for
ten million rows there is nothing here worth caching: **a cached variant tied to a materialization was
considered and left out**, because the honest advice is cheaper than a cache - describe whenever you
like, profile when something actually has to decide.

See `examples/src/main/java/AgentCatalog.java` for all of it, with its real output.
