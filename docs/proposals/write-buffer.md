# Proposal: buffered writing

**Status:** proposed, not implemented.

Two places a write buffer could live, and they are not equally hard. This proposes building it in
`quackjvm-core` first, where the contract is explicit and the risk is low, and only then deciding
whether the CQEngine plugin should have an implicit one on top.

## Where things stand

Four changes took a single CQEngine `add()` from 3.49 ms to 716 µs, and lowering the Appender
threshold took a batched write from 345 µs per object to 6.9 µs. What is left in a single `add()`
is three DuckDB statements and essentially nothing else:

| | per `add()` |
|---|---|
| `DELETE FROM cq_car WHERE objectKey IN (?)` | 200 µs |
| `INSERT INTO cq_car ...` | 116 µs |
| `commit` | 92 µs |
| everything else — pooling, binding, our own code | 10 µs |
| **plus, per index** | **~90 µs** |

Our own overhead is about 2% of the call. **There is nothing left to tune.** DuckDB charges roughly
100–200 µs for any statement, and a keyed write needs several. The only remaining lever is to stop
issuing one set of statements per object.

## The opportunity

Writing objects in batches is already dramatically cheaper, through the ordinary `addAll` path.
Measured on a columnar file collection with three indexes, 200,000 objects already stored:

| objects per write | per object | vs. one at a time |
|---|---|---|
| 1 | 919 µs | – |
| 10 | 395 µs | 2.3x |
| 100 | **44 µs** | 21x |
| 1,000 | **6.9 µs** | 133x |
| 10,000 | **2.0 µs** | 460x |

For comparison, an on-heap CQEngine `add()` is **4.6 µs**. A batch of a thousand is already
comparable to the heap, and this is measured through production code, not a prototype.

The gap between the first row and the fourth is pure packaging. An application that writes objects
one at a time pays 919 µs; the same objects written a thousand at a time cost 6.9 µs each. **The
proposal is to close that gap for callers who cannot batch themselves.**

## Why core comes first

The expensive part of a buffer is not the buffering. It is making queries see what has not been
written yet.

In the **CQEngine plugin** that is a hard problem. `IndexedCollection` promises that what you added
is there, so every path that can observe the data has to flush first — `retrieve()`, `size()`,
`database.sql()`, `database.query()`, joins from another collection, `getBytesUsed()`. Miss one and
the collection silently returns stale results. That is the whole risk of this proposal.

In **`quackjvm-core` that problem does not exist.** There is no query engine promising anything: the
caller writes their own SQL and therefore knows when they need the data to be there. The contract
is one line — *rows are visible after `flush()`* — and it is the caller's to keep.

So core gets the buffer, with an explicit `flush()`. If the plugin ever wants an implicit one, it
becomes a thin wrapper that adds only the barrier policy.

---

# Part 1 — core: an explicit `ObjectWriter`

## Should core expose `commit()`?

**No.** Core already gives the caller full control, and adding the method would take some away.

Core never calls `commit()`, `setAutoCommit()` or `rollback()` anywhere — only the connection pool
does, and that is the CQEngine side. `TableWriter` takes a `Connection` the caller owns and leaves
its transaction state alone, so this already works and always has:

```java
connection.setAutoCommit(false);
writer.write(connection, batch1, true);
writer.write(connection, batch2, true);
connection.commit();              // the caller's, as it should be
```

A `TableWriter.commit()` would wrap a method the caller already has, and would imply that we manage
the transaction when we deliberately do not. Core already has one ownership subtlety — `SqlQuery`
and `Rows` take ownership of the connection they are given and close it — and a `commit()` pointing
the other way would make that harder to reason about, not easier.

**What is missing is documentation, not API:** core should say plainly that it never touches
transaction state, so transactions belong to the caller.

## Should core expose `flush()`? Yes

Half of it exists already. `TableWriter.openAppender(connection)` returns an `AppenderHandle` with
`appendRow(Object[])`, `flush()` and `close()`. What is missing is an object-level writer — the
piece `ColumnarLayout` is asking for, since it already knows how to turn an object into a row.

```java
try (ObjectWriter<Reading> writer = ObjectWriter.into(connection, "reading", layout)
        .buffer(1000)
        .build()) {

    while (source.hasNext()) {
        writer.add(toReading(source.next()));
    }
    writer.flush();       // visible to SQL from here
}                         // close() flushes too
```

`add` buffers; `flush` writes the buffer through the path measured above; `close` flushes. The
buffer also flushes itself when it fills, so a caller who streams a million objects and never calls
`flush` still uses bounded memory.

This gets core to roughly 7 µs per object with **no semantic risk**, because nothing is implicit.

## The one trap to design around

`flush()` and `commit()` are different things, and conflating them loses data:

| | what it does |
|---|---|
| **flush** | appended rows become visible **inside your transaction** |
| **commit** | they become durable and visible to **other connections** |

With autocommit on — the default — a flush is effectively enough, because each statement commits.
With autocommit off, a caller who flushes and forgets to commit loses everything. If both verbs are
exposed, that distinction belongs in the first paragraph of the javadoc, not buried in it.

---

# Part 2 — the CQEngine plugin: an implicit buffer

Only worth doing after core's version exists and has been used.

```java
IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .writeBuffer(1000)          // default 0 - today's behaviour, unchanged
        .build();
```

`add`, `addAll` and `update` append to an on-heap buffer under the existing write lock, flushed
when: it reaches its configured size; a **read** arrives that could observe the buffered objects;
`flush()` is called; or the collection or its database is closed.

### Expected result

| workload | today | with a 1,000-object buffer |
|---|---|---|
| a loop of `add()` calls | 919 µs each | **~7 µs each** |
| add-then-read, alternating | 1.45 ms per pair | ~1.45 ms per pair (no change) |
| read-heavy service, occasional writes | 919 µs per write | ~919 µs per write (no change) |

**The worst case is not a regression**, which is the property that makes this safe to offer. When
every write is followed by a read, the barrier flushes a buffer of one and the cost is what it is
today.

### What it costs, stated plainly

1. **Durability.** Today `add()` returns after the row is committed. With a buffer it returns
   before, and a crash loses everything unflushed. This is the real trade and it belongs in the
   first line of the documentation.

2. **`add()`'s return value.** `Set.add` reports whether the collection changed. The buffer cannot
   know that without a lookup, and a lookup costs ~200 µs — most of what the buffer saves. Return
   `true` always and document it; almost nothing reads it.

3. **Visibility outside the collection.** `database.sql()`, a second collection joining against
   this one, an external tool opening the file — none see buffered objects. The read barrier has to
   cover every one of those paths, not only `retrieve()`. This is where the bug will be.

4. **Heap.** Up to `writeBuffer` objects live on the heap, which is the thing this project exists
   to avoid. Bounded and small, but the collection's heap cost stops being independent of its
   contents.

---

## Alternatives considered

**A heap-side key filter to skip the `DELETE`.** The delete is the most expensive statement in an
`add` (200 µs of 478) and exists only because we cannot tell whether the key is already stored. A
hash set of primary keys on the heap would answer that in nanoseconds and let new keys go straight
to an insert. No buffering, no durability change — but it costs heap proportional to the collection
(~16 MB per million `int` keys), which inverts the premise of the project. Worth offering
separately as its own opt-in; not worth making default.

**Try a plain `INSERT`, fall back to replace on conflict.** Measured and **rejected**: a plain
insert is 5x cheaper, but inside a transaction a constraint violation aborts the whole transaction,
and the subsequent `commit()` then reports success while silently discarding every other write in
the request.

**A background flusher thread.** Would remove the size-threshold latency spike, but makes
visibility non-deterministic and adds a thread to a library that currently has none.

**A plugin buffer that queries can see,** by keeping buffered objects in a small on-heap index and
unioning results. The only design with no read barrier at all, but it means running CQEngine's
query engine over two stores and reconciling ordering, deduplication and `existsIn` across them.
Large amount of work, large surface for subtle wrongness. Not now.

## Plan

**Core, first:**

1. Document that core never touches transaction state — transactions are the caller's. No
   `commit()` method.
2. `ObjectWriter<O>` over `ColumnarLayout` and `TableWriter`: `add`, `addAll`, `flush`, `close`,
   with a configurable buffer that also flushes when full.
3. Javadoc leading with the flush-versus-commit distinction.
4. Tests: visibility before and after flush, flush-on-full, flush-on-close, autocommit on and off,
   and a writer whose caller never flushes at all.

**Plugin, only if core's version proves itself:**

5. `writeBuffer(int)` on the builders, defaulting to 0.
6. **Enumerate every read barrier before writing the buffer**, and write those tests first: read
   after write through each entry point, a join against a collection with a dirty buffer, `size()`,
   `getBytesUsed()`, and a second collection in the same database.
7. Buffer and flush inside `DuckDBPersistence`, under the write lock it already holds.
8. Document durability at the top of the tuning page and in the builder's javadoc.

## Recommendation

**Do core's `ObjectWriter`.** It is most of the benefit, it carries no semantic risk, it is the
natural home for the buffer, and it fills a real hole — core can shred an object into columns and
rebuild it, but has no object-level way to write a stream of them.

**Hold the plugin's implicit buffer** until the read-barrier surface has been enumerated. That is
where the risk lives, not in the buffering.

And note that most of this win needs no new API at all: calling `addAll` with a hundred objects
instead of `add` a hundred times is 21x, costs nothing, and is already documented in
[Writing data](../writing.md).
