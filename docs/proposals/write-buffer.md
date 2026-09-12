# Proposal: an optional write buffer

**Status:** proposed, not implemented.

## Where things stand

Four changes took a single `add()` from 3.49 ms to 716 µs, and lowering the Appender threshold took
a batched write from 345 µs per object to 6.9 µs. What is left in a single `add()` is three DuckDB
statements and essentially nothing else:

| | per `add()` |
|---|---|
| `DELETE FROM cq_car WHERE objectKey IN (?)` | 200 µs |
| `INSERT INTO cq_car ...` | 116 µs |
| `commit` | 92 µs |
| everything else — pooling, binding, our own code | 10 µs |
| **plus, per index** | **~90 µs** |

Our own overhead is now about 2% of the call. **There is nothing left to tune.** DuckDB charges
roughly 100–200 µs for any statement, and a single-object write needs at least a few. The only
remaining lever is to stop issuing one set of statements per object.

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

The gap between the first row and the fourth is pure packaging. An application that adds objects
one at a time pays 919 µs; the same objects written a thousand at a time cost 6.9 µs each. **The
proposal is to close that gap for callers who cannot batch themselves.**

## The proposal

An opt-in write buffer on the collection.

```java
IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
        .columnarLayout(ColumnarLayout.ofRecord(Car.class))
        .writeBuffer(1000)          // default 0 - today's behaviour, unchanged
        .build();
```

`add`, `addAll` and `update` append to an on-heap buffer under the existing write lock. The buffer
is flushed — as one ordinary batched write, the path already measured above — when any of:

1. it reaches its configured size;
2. a **read** arrives that could observe the buffered objects (the read barrier);
3. `flush()` is called explicitly;
4. the collection or its database is closed.

Nothing else in the write path changes. A flush *is* an `addAll`, so it inherits the Appender path,
the set-based delete, the index writes and the idempotency already in place.

### Expected result

| workload | today | with a 1,000-object buffer |
|---|---|---|
| a loop of `add()` calls | 919 µs each | **~7 µs each** |
| add-then-read, alternating | 1.45 ms per pair | ~1.45 ms per pair (no change) |
| read-heavy service, occasional writes | 919 µs per write | ~919 µs per write (no change) |

**The worst case is not a regression**, which is the property that makes this safe to offer. When
every write is followed by a read, the barrier flushes a buffer of one and the cost is what it is
today. The buffer only ever helps, and it helps most exactly where the current numbers are worst.

## What it costs, stated plainly

This changes semantics, which is why it must be opt-in and why the default stays 0.

1. **Durability.** Today `add()` returns after the row is committed. With a buffer it returns
   before, and a crash loses everything unflushed. This is the real trade and it belongs in the
   first line of the documentation, not a footnote.

2. **`add()`'s return value.** `Set.add` reports whether the collection changed. The buffer cannot
   know that without a lookup, and a lookup costs ~200 µs — most of what the buffer saves. The
   honest options are to return `true` always and document it, or to consult a heap-side key filter
   (below). I would return `true` and document it; almost nothing reads it.

3. **Visibility outside the collection.** Anything reading the database on another connection —
   `database.sql()`, a second collection joining against this one, an external tool opening the
   file — will not see buffered objects. The read barrier therefore has to cover `database.sql()`,
   `query()` and the join paths, not only `retrieve()`. Getting that wrong is the most likely way
   to ship a correctness bug here.

4. **Heap.** Up to `writeBuffer` objects live on the heap, which is the thing this project exists
   to avoid. At 1,000 objects it is a few hundred kilobytes and bounded, but it stops being true
   that the collection's heap cost is independent of its contents.

## Alternatives considered

**A heap-side key filter to skip the `DELETE`.** The delete is the most expensive statement in an
`add` (200 µs of 478) and exists only because we cannot tell whether the key is already stored. A
hash set of primary keys on the heap would answer that in nanoseconds and let new keys go straight
to an insert. It needs no buffering and changes no durability semantics — but it costs heap
proportional to the collection (~16 MB per million `int` keys), which inverts the premise of the
project. Worth offering separately as its own opt-in; not worth making default.

**Try a plain `INSERT`, fall back to replace on conflict.** Measured and **rejected**: a plain
insert is 5x cheaper, but inside a transaction a constraint violation aborts the whole transaction,
and the subsequent `commit()` then reports success while silently discarding every other write in
the request.

**A background flusher thread.** Would remove the size-threshold latency spike, but makes
visibility non-deterministic and adds a thread to a library that currently has none. A size
threshold plus a read barrier gets almost all the benefit and is explainable in two sentences.

**A buffer that queries can see,** by keeping buffered objects in a small on-heap index and
unioning results. The only design with no read barrier at all, but it means running CQEngine's
query engine over two stores and reconciling ordering, deduplication and `existsIn` across them.
Large amount of work, large surface for subtle wrongness. Not now.

## Plan

1. `writeBuffer(int)` on `DuckDBPersistence.Builder` and `DuckDBDatabase.CollectionBuilder`,
   defaulting to 0.
2. Buffer and flush inside `DuckDBPersistence`, under the write lock it already holds.
3. Read barrier: flush for read requests in `openRequestScopeResources`, and in
   `DuckDBDatabase.query`/`sql`/`join`.
4. Tests first, and specifically the ones that catch a missed barrier: read-after-write through
   every entry point, a join against a collection with a dirty buffer, `size()`, `getBytesUsed()`,
   and a second collection in the same database.
5. Document durability at the top of the tuning page and in the builder's javadoc.

## Recommendation

Worth doing, but **only after** the read-barrier surface is enumerated properly — that is where the
risk lives, not in the buffering itself.

If the appetite is smaller, note that most of this win is already available with no new API and no
semantic change: calling `addAll` with a hundred objects instead of `add` a hundred times is a 21x
improvement and costs nothing but a paragraph of documentation, which
[Writing data](../writing.md) now has.
