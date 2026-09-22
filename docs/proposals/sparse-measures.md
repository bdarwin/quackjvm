# Sparse measures: composite keys, time, and archiving (proposal)

`SparseTable` as it stands identifies each value by one flat name, and offers one wide view with a
column per name. That fits a few hundred names. The workload it is meant for is different:

- Thousands of records, each carrying hundreds to thousands of values.
- Each value identified by several coordinates - a few text parts plus one point along an axis.
- Thousands of distinct keys across the table, so "a column per key" is thousands of columns.
- One record can hold two values for the same key, in different units.
- Values change over time, sometimes as a complete re-publish, sometimes as only what changed.
- Values can disappear, not only change.
- History has to be kept, but not in the live table forever.

This proposal reshapes `SparseTable` around that. Everything here was measured first; the numbers
are at the end.

## Defining a measure

One table per measure. The parts are fixed when it is defined.

```java
SparseTable measures = SparseTable.named("measures")
        .withKey("a", "b", "c")     // text parts identifying a series
        .withAxis("point")          // one text part, laid out as columns
        .withUnit("unit")           // part of the identity; never summed across
        .appendMode();              // or replaceMode(), the default
```

- **Every part is text**, numbers included. The columns are fixed, so nothing is gained by typing them.
- **The axis** is the part that becomes columns. Each axis value may carry a sort position; without
  one, columns come out in text order, which is already right for dates written `2026-10-29`.
- **The unit** is part of what identifies a value, because one record can hold the same key in two
  units. It carries one rule, below.
- **Records** are identified by a unique id and nothing else. Anything else known about a record
  belongs in your own table; every query can hand back its SQL to join against.

### Never add different units

A total across two units is a wrong number that looks right. So `totals()` refuses to run unless the
unit is either grouped by or filtered to a single value:

```java
measures.totals().rows("a", "unit").columns("point")     // fine: grouped by unit
measures.totals().where("unit", "U1").rows("a")          // fine: one unit
measures.totals().rows("a").columns("point")             // refused
```

## Storage

```
measures_key                                          measures_value
id | a | b | c | point      | unit | point_order      record_id | [ts] | key_id | value | kind
---+---+---+---+------------+------+------------      ----------+------+--------+-------+------
 0 | x | y | z | 2026-10-29 | U1   |          1              42 | ...  |      0 |  1.25 | ...
 1 | x | y | z | 2026-10-29 | U2   |          1              42 | ...  |      1 |  0.97 | ...
```

The dictionary gains a column per key part; it stays small (9,000 rows in the measured case), which
is why parsing coordinates out of a flat name costs almost nothing today - and why doing it properly
costs nothing either. The values table keeps its narrow shape: the `ts` and `kind` columns exist only
in append mode.

## Writing

| mode | call | meaning |
|---|---|---|
| replace | `replace(batch)` | these records' values become exactly this. No history. |
| append | `publishFull(batch, at)` | the record's complete set of values at that timestamp |
| append | `publishChanges(batch, at)` | only the values that changed at that timestamp |

A change can also be a removal. `batch.record(42).remove("x", "y", "z", "2026-10-29", "U1")` writes a
marker row, so that reading as of a later time knows the value is gone rather than unchanged.

## Reading

Every question has the same shape: which parts become rows, the axis (or time) becomes the columns,
filters, and a point in time.

```java
measures.view().records(42).asOf(t).rows("a", "b", "c").columns("point")
measures.totals().asOf(t).where("unit", "U1").rows("a").columns("point")
measures.totals().between(t1, t2).rows("a").columns("time")
measures.totals().change(t1, t2).rows("a").columns("point")
measures.totals().rows("a").columns("point").sql()        // to join your own tables to
```

**As of a timestamp** means: take the record's latest full publish at or before it, apply every
change after that up to it, keep the newest value for each key, and drop the keys whose newest row is
a removal.

**Totals compute per key first.** Adding up 10.2M values per key gives 9,000 rows, and only then is
the dictionary joined and the columns laid out: 20 ms against 227 ms for the obvious order. This is
the single biggest change and it is in the generated SQL, not in the storage.

**Rollups**, optional, for many readers at once: one total per key, answering in about 1 ms, rebuilt
in about 40 ms, or kept current as records are replaced at about 11 ms a record.

## What is built

Keys with named fields, the unit rule, declared orders, grouping and pivoting on any field,
conversion through a table of factors, and writing a measure to Parquet and reading it back - see
`MeasureTable` and `examples/src/main/java/CoreMeasures.java`. Time, and archiving by time, are not.

## Archiving

Once a full publish exists at time T, everything before it can leave the live table without changing
any answer at or after T.

```java
measures.export(connection, directory);          // built: dictionary, values, definition
measures.query().from(directory).rows("a")       // built: ask the files, without reading them back
measures.importFrom(connection, directory);      // built: read them back, matching keys by field
measures.archive(before, directory);             // not built: the same, for writes older than a time
```

Reading an archived time means reading those files, which the builder can do on request. The live
database file does not shrink when rows are deleted; DuckDB reuses the space.

## Measured

On this machine (10 cores, DuckDB 1.5.5), before building anything:

| | |
|---|---|
| totals by two parts, axis as 30 columns, 10.2M values: join first | 227 ms |
| the same, totalling per key first | **20 ms** |
| the same, from a per-key rollup (9,000 rows, built in 15 ms) | 7 ms |
| one record, axis as columns | 7-12 ms |
| keeping the rollup current per replaced record (1,000 values) | 10.7 ms; matched a rebuild to 5e-11 after 200 replaces |
| 39M value rows over 200 timestamps, full publish every 20th | 238 MB |
| one record as of a timestamp (full + changes on top) | 5.2 ms |
| totals across 2,000 records as of a timestamp | 42 ms |
| flushing 23.4M rows to Parquet, partitioned by day | 382 ms, 27 MB (this data compresses unusually well) |
| one record as of an archived timestamp, from Parquet | 40 ms, or 12 ms reading only that day |
| live and archived together, latest per key | 290 ms |

## What it costs to build

- `SparseBatch` takes key parts rather than one name, and gains removals.
- `SparseTable` gains the definition (parts, axis, unit, mode), the dictionary columns, the write
  modes, and the query builder that generates all the SQL above.
- The flat-name API released in 1.1.0's notes but not yet in a release goes away. A flat name is the
  same thing as a key with one part, so a table defined `withKey("name")` behaves as before.

## Open

- **Whether `between(...)` should put time in the rows or the columns by default.** Columns reads
  better for a handful of timestamps, rows for hundreds.
- **Whether removals need to be visible in a view** - shown as an empty cell, which they will be, or
  distinguishable from "never had a value".
- **Whether the rollup should be maintained automatically** or left for the caller to refresh.
