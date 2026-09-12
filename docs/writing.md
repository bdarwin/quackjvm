# Writing data

There are four ways to get objects in, and the difference between the slowest and the fastest is
about 400x per object. Picking the right one matters more here than anywhere else in quackjvm.

| | per object | use when |
|---|---|---|
| `add(one)` | ~937 µs | genuinely one object at a time |
| `addAll(batch)` | ~4.0 µs | you have a collection in hand |
| `addAll` with `BULK_IMPORT` | ~3.0 µs | the objects are all new |
| `DuckDBBulkWriter` | **~2.3 µs** | objects arrive as a stream |

## `add` — one object

```java
cars.add(new Car(1, "Ford", "Focus", BLUE, 15_000.0));
```

This works, and it is the slowest thing in the library: around **937 µs**, against 4.6 µs for an
on-heap collection. Every single-object write is its own set of statements and its own commit
against a columnar store, which is the workload columnar stores are worst at.

Essentially all of what remains is DuckDB's own per-statement cost — a delete at 200 µs, an insert
at 116 µs, a commit at 92 µs, and the same again for each index. There is no tuning below that
floor; the only way past it is to write more than one object per statement, which is what the rest
of this page is about.

If your application adds objects one at a time in a hot loop, batch them yourself — even batches of
a hundred change the picture completely.

## `addAll` — a batch

```java
cars.addAll(listOfCars);
```

Any `addAll` of more than **16** objects (`appenderThreshold`) is automatically streamed through
DuckDB's Appender rather than inserted row by row, in chunks of `stagingChunkRows` (131,072 by
default) so that a large load does not need memory proportional to the batch.

`addAll` is idempotent: an object whose primary key already exists replaces the stored one. That
costs a delete-before-insert, which is where the next option comes in.

**Batch size is the single biggest lever on write throughput.** Measured on a columnar file
collection with three indexes and 200,000 objects already stored:

| objects per `addAll` | per object |
|---|---|
| 1 | 919 µs |
| 10 | 395 µs |
| 100 | **44 µs** |
| 1,000 | **6.9 µs** |
| 10,000 | **2.0 µs** |

The cliff between 10 and 100 is the Appender taking over from prepared statements. Below the
threshold each row is its own statement; above it, rows are streamed into a staging table for
almost nothing and moved across in one set-based statement. A batch of a thousand is faster per
object than adding to an on-heap CQEngine collection.

If your objects arrive one at a time, **collect them and write them in batches**. A hundred at a
time already gets you 95% of the available win.

## `BULK_IMPORT` — a batch you know is new

If none of the objects are already stored, tell it so and the delete pass is skipped:

```java
QueryOptions options = new QueryOptions();
FlagsEnabled.forQueryOptions(options).add(DuckDBFlags.BULK_IMPORT);
cars.update(Collections.emptyList(), millionsOfCars, options);
```

`DuckDBFlags.BULK_IMPORT` has the same flag value as CQEngine's `SQLiteIndexFlags.BULK_IMPORT`, so
existing code using that flag already gets this behaviour.

If a primary key *is* already present, the write fails on the primary key constraint rather than
silently replacing. That is the point of the flag — it is an assertion, not a hint.

## `DuckDBBulkWriter` — a stream

`addAll` needs the whole batch in memory as a collection first. When the data is a stream — a file
being parsed, a database cursor, a queue — a bulk writer keeps one DuckDB Appender open per table
and pushes rows straight in:

```java
try (DuckDBPersistence<Event, Long> persistence = DuckDBPersistence.builder(Event.EVENT_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Event.class))
        .memoryLimit("256MB")
        .build()) {

    IndexedCollection<Event> events = new DuckDBIndexedCollection<>(persistence);

    // Indexes must exist before the writer opens: it appends to the index tables it
    // finds at that moment.
    events.addIndex(DuckDBIndex.onAttribute(Event.SOURCE));
    events.addIndex(DuckDBIndex.onAttribute(Event.LEVEL));

    try (DuckDBBulkWriter<Event> writer = persistence.bulkWriter();
         Stream<Event> incoming = parseLogFile(path)) {

        incoming.forEach(writer::add);
        writer.flush();
        System.out.printf("Writer accepted %,d objects%n", writer.getObjectsWritten());
    }   // close() flushes too

    // The indexes were populated as the objects streamed past, so queries work immediately.
    try (ResultSet<Event> slow = events.retrieve(
            and(equal(Event.LEVEL, "ERROR"), greaterThan(Event.LATENCY, 900.0)))) {
        System.out.println(slow.size() + " slow errors");
    }
}
```

Loading a million objects this way took **2.3 s**, against 4.1 s for `addAll` with `BULK_IMPORT`,
and produced a **30 MB** file against 36 MB — appending straight into the target tables compresses
into better row groups than staging rows and copying them. Memory stays bounded no matter how many
objects pass through.

`writer.addAll(iterable)` takes a batch, and `getObjectsWritten()` reports the running total.

### Four things it trades away

It is a loading tool, not a general write path.

- **The objects must be new.** Rows are appended, not merged, so an object whose primary key is
  already stored fails the primary key constraint at the next flush. Use `collection.update(...)`
  to replace existing objects.
- **The collection is inconsistent until you flush.** DuckDB makes appended rows visible in batches
  as its buffers fill, and each table flushes independently, so a query running during a session
  can see an object that is not yet in every index. `flush()` and `close()` make it consistent, and
  `flush()` is also where a duplicate key is reported.
- **Add your indexes before opening the writer**, so that it writes them too. An index added
  afterwards is still built correctly, but from the object table rather than by the writer.
- **One thread.** A writer is not thread-safe and holds the persistence's write lock for its
  lifetime, so other writers wait. Readers are never blocked.

The runnable version is [`examples/CqEngineBulkWriter.java`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CqEngineBulkWriter.java).

## Removing and updating

Ordinary CQEngine, unchanged:

```java
cars.remove(car);
cars.removeAll(someCars);
cars.update(objectsToRemove, objectsToAdd);
cars.clear();
```

`remove` matches on the **primary key**, not on object equality, so removing an object with the
same key but different fields still removes the stored row.

Deleted rows leave space behind in the file. `compact()` reclaims it — see
[Tuning](tuning.md#storage-management).

## After loading

Two calls are worth making once a bulk load finishes:

```java
persistence.optimize();   // sort the index tables so DuckDB can skip blocks
persistence.compact();    // CHECKPOINT: flush and reclaim space
```

`optimize()` is the more valuable of the two: it makes counting flat with respect to collection
size and cuts range queries by about a third. What it does and what it costs is in
[Tuning](tuning.md#optimize-after-a-bulk-load).

## Concurrency

- **Reads** never block and never lock. DuckDB's MVCC gives each request a consistent snapshot.
- **Writes** are serialised against each other by default, because two DuckDB transactions writing
  the same table at once cause one to fail with a conflict error. Turn this off with
  `.serializeWrites(false)` if your application coordinates its own writes.
- **One JVM process per database file.** DuckDB does not support several processes writing the same
  file. All connections are duplicated from a single open database.
