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

## Refreshes: publishing the same measure over and over

A refresh is a point on a publisher's timeline - a timestamp it chooses, and an id that makes it
itself. One refresh can cover several measures, and says of each whether it is everything the
publisher has or only what moved.

```java
MeasureRefresh.at(nineOClock, "run-2026-09-30T09:00")
        .full(exposure, everythingExposureHas)
        .increment(sensitivity, whatMoved)
        .remove(coverage, 42)
        .commit(connection);
```

- A record that appears in a refresh **replaces itself entirely**, whichever kind it came in: a key
  it held and the refresh does not is gone.
- A **full** set says more - the measure holds exactly what the refresh names, and records it does
  not name are gone. An empty full set empties the measure.
- An **increment** leaves records it does not name alone.
- The kind is **per measure**, so a refresh that leaves a measure out leaves that measure untouched.
  One kind for the whole refresh would empty it by omission.
- `remove(...)` cancels what a record held. It leaves the state, not the history.

### Contributions

Nothing is overwritten and nothing is deleted. A change is written as cancelling the old value and
adding the new, a removal as cancelling everything the record held, into
`<measure>_contribution (refresh_id, record_id, key_id, value)`. The measure's own tables hold the
state that results, and **the state is always the sum of the contributions** - which is what lets
history be archived and cut back without the working tables growing.

| what a refresh does | what it contributes |
|---|---|
| a value arrives at 10 | `+10` |
| it moves to 12 | `+2` |
| it does not move | nothing at all |
| its key is left out of a later refresh | `-12` |
| its record is removed | `-12` |

### The timestamp, and the id

The timestamp is yours: it marks the point the data belongs to, not the moment of writing, so two
refreshes may share one - a correction of the same point, or several measures refreshed together.
Because of that it cannot identify a refresh, and the id can: committing the same id twice applies it
once and returns `false`, so a publisher that commits and misses the acknowledgement can simply
publish again. Refreshes that share a timestamp are ordered by the sequence they committed in.

Every measure in one refresh must have been `writtenBy` the same publisher: a timeline belongs to
whoever is publishing it. The ledger of refreshes is one table, `measure_refresh`, shared by every
measure in the database.

### What it costs

Measured on 100 records of 25 values, published 200 times to a database on disk: 4.05 ms a refresh
against 2.04 ms to replace the same records outright and keep no history, and 2,316 KB against
1,292 KB on disk. The state stays 2,500 values however long it runs; the contributions reach 51,999
rows - 2,500 from the first refresh, then only what moved. Resolving the 250 keys used to take 2.1 ms
of that until they were remembered: dictionary ids never change, so after the first refresh a
publisher writing the same keys does no SQL for them at all. See `examples/src/main/java/CoreRefreshes.java`.

## Two databases: state here, history there

A refresh writes the new state **and** its contributions into the live database in one transaction,
the contributions being an outbox. A shipper moves them into a second database - the history - in
bulk, on whatever schedule suits, and clears the outbox.

```java
MeasureHistory history = MeasureHistory.at("/var/lib/quack/history.duckdb");
history.create(live, exposure, coverage);

MeasureRefresh.at(now, "run-1").increment(exposure, batch).commit(live);   // lands in live
history.ship(live, exposure, coverage);                                   // later, in bulk
```

The live database then holds only current state and stays the same size however long it runs, while
history grows in a file of its own that can be exported and pruned on its own schedule.

### Why two, rather than one transaction into both

DuckDB refuses it:

> *Attempting to write to database "history" in a transaction that has already modified database
> "live" - a single transaction can only write to a single attached database.*

So shipping is two transactions: one writes the history, the next clears what it took. That is safe in
both directions.

- **Nothing is lost.** Until it is shipped, a contribution sits in a committed table in the live
  database. A crash before shipping loses nothing; the next shipment takes it.
- **Nothing is counted twice.** A refresh's entry in the history's ledger is what says its
  contributions are there, written in the same transaction as they are. A crash between the two
  transactions leaves shipped contributions in the outbox, and the next shipment finds their entry and
  passes over them.
- **Shipping one measure does not strand another.** Only the measures being shipped get their ledger
  entries, so a refresh that covered three measures can be shipped one at a time.

### What the history holds

Contributions arrive with their key fields beside them rather than a dictionary id, since ids are
local to the database that minted them - so several services, each with its own live database, can
ship into one history and their rows line up. It is also the shape a Parquet file wants, so exporting
will be a copy rather than a join.

### Forgetting refreshes in the live ledger

The live ledger is what makes committing an id twice harmless, and a publisher that missed an
acknowledgement republishes within seconds, not days. At a refresh every few milliseconds it would
otherwise be the one thing in the live database that grows for ever:

```java
history.forgetShippedRefreshes(live, Duration.ofHours(1));
```

Only refreshes already in the history are forgotten, and the history keeps all of them.

### What it costs

Measured on 1,000 records of 50 values - a full set then 20 increments each moving a tenth of the
records, shipping after every refresh: 18 ms to ship the first 50,000 contributions, single digits for
the 5,000 of an increment, and 0.7 ms when nothing is waiting, because the shipper asks the outbox
what it holds before looking anywhere else. The live database settled at 1,804 KB holding 50,000
values, the history at 1,292 KB holding 149,999 contributions. See
`examples/src/main/java/CoreMeasureHistory.java`.

## Reading as of a point in time

```java
measures.query().asOf(nineOClock, history).rows("group").columns("point").run(connection);
```

One rule: **the state at T is the sum of the contributions from the last full set at or before T, up
to T.** A full set is a baseline - it contributes the values themselves rather than what they moved
by - so reading needs nothing older than the last one, and everything older can be thrown away.

- A point **between two refreshes** reads as the earlier one.
- Keys whose contributions cancel out are left out, as a key with no value would be. `showZeros()`
  keeps them.
- It reads the history **and whatever is still in the outbox**, so a point a moment ago answers the
  same whether the shipper has run or not, and a contribution in both is counted once.
- Every field is still rows or columns, and the unit rule still holds.

### What an as-of read costs

Measured on a history of 1,050,000 contributions - 1,000 records of 50 values, a full set then 200
increments each moving a tenth - on a busy machine, so the shape rather than the digits:

| | |
|---|---|
| a whole panel from the live state | 22-31 ms |
| the same as of a point | 68-96 ms |
| one record from the live state | 29-34 ms |
| one record as of a point | 35-47 ms |

So **read the live state for now, and as-of for history**: summing a million contributions costs about
three times reading the state that was kept for exactly that reason. A point in the middle of the
history costs a little less than the newest one, since there is less to add up.

Carrying each refresh's sequence number on every contribution, so that a read could skip row groups by
range, was built and measured: 63 ms against 67 for an older point, no better for the newest, 8 bytes
a row and 13% more history on disk. Declined.

### A timeline only moves forward

A refresh dated before the last one that publisher gave that measure is refused. A contribution says
how much a value moved from what the measure held *when it was written*; letting an older point in
afterwards would add that movement to a state it was never measured against, and a read in between
would be wrong. A correction is published at a later point - which is the truth of it: the correction
happened now. Two refreshes may share a point, and are then ordered by the sequence they committed in.

A high-water mark per publisher per measure is kept for this, and is never trimmed - one row each.

## Export, compact, prune

Three separate calls on separate schedules, because downstream wants files all day while the history
can only be cut back once a later full set exists.

```java
history.export(live, exposure, "s3://bucket/lake");   // a file per day of refresh, nothing removed
history.compact(live, exposure, lake, "2026-09-28");  // a finished day's files become one
history.prune(live, exposure);                        // drop what is exported and before the last full set
```

- **export** writes contributions it has not written before: a row per contribution with its key
  fields, the refresh it belongs to, when that was, who wrote it, and the measure's definition in the
  file's Parquet metadata. Anything that reads Parquet reads the rows.
- **compact** folds a day into one file and deletes the originals, so it needs a local path - compact
  there and upload. Only finished days, so a consumer reading files as they appear is undisturbed.
- **prune** drops exported contributions from before the last full set, then checkpoints so the space
  is released. Anything not exported is never dropped.

### The files answer as of a point too

Because each row carries its refresh, an exported file reads as of a point exactly as the history
does:

```java
measures.query().asOf(monday, history).from(mondaysFiles).rows("group").columns("point");
```

A point the history has pruned is **refused rather than answered wrong**, and the message says where
it has gone. Pass the files alongside and it answers again; a refresh in both is counted once.

### Reading the files, and reading the history as history

`examples/src/main/java/CoreMeasureParquet.java` is the file side on its own: what a file holds, the
five-line SQL that turns contributions into a state at a point - so a reader with nothing but DuckDB
and the files gets the same answers - the same file read over HTTP by byte range, and a database that
has never heard of the measure reading its definition out of the file's metadata.

`examples/src/main/java/CoreMeasureAuditTrail.java` reads the history as history: one record hour by
hour, who changed what and by how much, what moved between two points, and which refreshes touched a
record - plain queries over the two history tables.

## Rebuilding the live database

The history is the record; the live database is a cache of the current state.

```java
history.rebuild(live, exposure);              // as it stands now
history.rebuild(live, exposure, monday);      // or as it stood then
```

Refused while anything is still in the outbox, since that would be thrown away - ship it first.

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
