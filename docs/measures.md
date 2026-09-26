# Measures

A measure holds values that each belong to a record and are identified by several fields. Thousands
of records, each carrying hundreds or thousands of values; thousands of distinct keys across the
table; and questions that come from any direction - totals by one field with another laid out as
columns, one record in full, one group filtered out.

```java
MeasureTable measures = MeasureTable.named("measures")
        .fields("group", "sub", "point", "unit")
        .unit("unit")                       // never added across without converting
        .order("point", "1d", "1m", "1y", "5y", "10y", "30y")
        .writtenBy("svc-a")
        .build();
measures.create(connection);

measures.append(connection, measures.batch()
        .record(42).put(1.25, "g3", "s2", "5y", "U1")
        .build(), "2026-09-20");
```

## Any field as rows, any field as columns

```java
measures.query().rows("group").columns("point").where("unit", "U1").run(connection.duplicate());
```
```
   group       1d       1m       1y       5y      10y      30y
      g0   1852.5   1852.5   1852.5   1852.5   1860.0   1860.0
```

The same data, turned round:

```java
measures.query().rows("point").columns("sub").where("unit", "U1")
```

Also `records(...)`, `parts(...)`, `writtenBy(...)`, `latestPerKey()`, and `sql(connection)` to hand
back the SQL so you can join your own tables to it.

**Why it is quick.** A query totals per key first and joins the dictionary afterwards. On ten
million values that is 20 ms against 227 ms for the obvious order, because the join and the columns
then apply to thousands of rows rather than millions.

**Units are never added together.** A total across two units is a wrong number that looks right, so
a query must put the unit in the rows or the columns, narrow to one, or convert:

```java
measures.query().rows("group").columns("point").convertTo("U1", "rate")
```

`rate` is an ordinary table of your own - from unit, to unit, factor. A unit with no factor is left
out rather than counted wrong.

## Parts, and who wrote what

Every write carries a part - a label, usually a day - and records who wrote it and when. The extra
two columns measured at 1.7% of the file.

```java
measures.append(connection, batch, "2026-09-20");
measures.parts(connection);          // [Part[2026-09-20, 6000], Part[2026-09-21, 6000]]
```

With several services writing one measure, give each its own `writtenBy`. They write separate files
and separate rows, and the reader decides what that means:

| what you want | how | measured on 2M values |
|---|---|---|
| add it all up (right when writers don't overlap) | the default | 4.5 ms |
| one writer only | `writtenBy("svc-a")` | 6.5 ms |
| newest wins | `latestPerKey()` | 33.8 ms |

## Archiving a part

```java
String file = measures.archive(connection, "/archive", "2026-09-20");
// /archive/measure=measures/part=2026-09-20/svc-a-1790408633887.parquet
```

One self-contained Parquet file. Those rows leave the live tables, and DuckDB is checkpointed so the
space is actually released - without that the measure still looks as large as before, measured at
8,448 KB against 5,888 KB after.

**What is in the file.** A row per value with its key fields beside it - `record_id`, one column per
field, `value`, `part`, `written_by`, `written_at` - and the measure's definition in the file's
Parquet metadata under `quackjvm_measure`:

```json
{"measure":"measures","fields":["group","sub","point","unit"],"unit":"unit",
 "orders":{"point":["1d","1m","1y","5y","10y","30y"]},"part":"2026-09-20",
 "written_by":"svc-a","written_at":"2026-09-26T10:15:00Z","version":1}
```

So the file is readable by anything - pandas, Spark, Trino, DuckDB - with no knowledge of quackjvm,
and still enough for quackjvm to rebuild the tables from it alone. The path holds
`measure=.../part=...` so a lake can prune by them; quackjvm's own reads turn that inference off, or
the file's own `part` column would be read back from the path as a date.

## Getting it back

```java
// ask the files where they lie - one day or many, local, s3:// or http://
measures.query().from(sept20, sept21).rows("group").columns("point").where("unit", "U1");

// or read them back, into a database that knows nothing about the measure
MeasureTable restored = MeasureTable.restore(connection, sept20, sept21);
```

- Keys are matched by their fields, not by the ids they had when archived, so files can arrive where
  the dictionary was built in another order or where other records already live.
- The parts a file holds are replaced rather than added to, so restoring twice leaves the same thing.
- `MeasureTable.describedBy(connection, file)` reads the definition alone.

**Measured** on two million values: 33 ms to archive, 8 ms to answer a question from the files, 30 ms
to read them back into an empty database.

**S3 and the like.** Anywhere DuckDB can read works, once its `httpfs` extension is loaded and a
secret configured - `archive(connection, "s3://bucket/measures", day)` writes there, and
`query().from("s3://bucket/measures/measure=m/part=2026-09-2*/*.parquet")` reads it. Only the byte
ranges needed are fetched: the file's footer, then the columns the query uses.

## When the fields change

A field added later needs to say what older values read as:

```java
MeasureTable grown = MeasureTable.named("measures")
        .fields("group", "sub", "point", "unit")
        .field("kind", "n/a")            // added later
        .unit("unit")
        .build();
grown.migrate(connection);               // rebuilds the dictionary, keeping every value
```

- Files written before the field existed take the default, so old and new days answer one query
  together.
- Dropping a field is refused: values that differed only by it would silently be added together.
- `migrate` does nothing when the tables already agree with the definition.

## Writing only what changed

```java
measures.appendChanges(connection, batch, "2026-09-20");
```

Compares the whole batch against what is stored and writes only the differences. Measured on two
thousand records of a thousand values each, 5% of them changed: 36 ms to work out the difference,
and 95% fewer rows written - 105 KB of Parquet instead of 2,083 KB. It reads before it writes, so it
suits one writer per record.

## Archiving itself

```java
MeasureTable.named("measures")
        .fields("group", "point")
        .archiveWhenLargerThan(1024L * 1024 * 1024)
        .archiveTo("s3://bucket/measures")
        .onArchive((part, file) -> store.put(part, Files.readAllBytes(Path.of(file))))
        .build();
```

After a write, if the measure's own tables are over the limit, its oldest parts are archived one at a
time until it is back under. The handler is called with each file, on the thread that did the write,
after that write has committed - so the file can be moved into a store of your own. The newest part
is never archived, since that is what writers are still adding to. Checking the size costs 1-2 ms and
happens at most once every 50,000 values written.

`sizeOnDisk(connection)` reports the same number, if you would rather decide for yourself.
