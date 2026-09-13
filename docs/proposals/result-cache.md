# Proposal: a result cache for repeated panels

**Status:** proposed, not implemented. Prompted by the dashboard measurements in
[Aggregates and projections](../aggregates.md#many-people-the-same-panels).

## The observation

A dashboard asks **the same questions over and over**. Ten people looking at the same board run the
same group-by and the same pivot, seconds apart, against data that has not changed. Today every one
of those is a full columnar scan.

Measured on 2,000,000 sales with four panels, quackjvm saturates at **1,200–1,470 panels/s**
whatever `threads` is set to — that ceiling is the machine's cores and memory bandwidth, not
anything in the code. No tuning gets past it, because every request does the work again.

A cache does not have to get past it. It has to avoid the work.

| | |
|---|---|
| a panel today | 1.3 ms at one user, 8.1 ms at sixteen |
| the same panel from a map | a few microseconds |

The interesting number is not the speedup on a hit — that is obvious — it is that **throughput
stops being bounded by DuckDB at all** for the cached fraction. A board where eight of ten panels
are shared and unchanged would serve most requests without touching the engine.

## The shape

```java
DuckDBDatabase db = DuckDBDatabase.builder()
        .file(file)
        .resultCache(Duration.ofSeconds(5), 200)   // ttl, max entries; default off
        .build();

// Unchanged. Served from the cache when an identical query ran inside the TTL.
List<MakeStats> stats = db.query("SELECT make, count(*), avg(price) FROM sale GROUP BY 1")
                          .records(MakeStats.class);
```

Keyed on the SQL string plus its bound parameters. Only whole materialised results are cacheable —
`scalar`, `list`, `records`, `count` — never `stream()` or `forEachRow`, which hand back a cursor.

## What has to be decided

1. **Staleness is the whole design.** A TTL is the honest, simple answer: results may be up to
   *ttl* old, you choose how old, and nothing is ever silently wrong beyond that bound. The
   alternative — invalidating on write — sounds better and is much harder: quackjvm would have to
   know which tables a SQL string touches to invalidate the right entries, and DuckDB will happily
   parse SQL we did not generate. A crude version (any write to this database clears the whole
   cache) is cheap and correct, and for a read-mostly dashboard almost as good. **I would ship
   TTL plus clear-on-write, and not attempt per-table invalidation.**

2. **Cached results must be immutable.** `records()` returns a `List`; handing the same list to two
   callers means one can mutate what the other sees. Wrap in `List.copyOf` on the way in.

3. **Memory.** This is a library whose selling point is not using heap. A cache of results is heap,
   bounded by entry count rather than bytes, and a `records()` result can be large. Cap entries,
   default the whole thing **off**, and say plainly in the javadoc that this trades heap for
   latency — the opposite of the rest of the project.

4. **It belongs in core, not the plugin.** `Rows` is where every materialised read goes and it
   already takes a connection; the cache sits in front of it. The CQEngine plugin gets it for free
   through `database.query(...)`, and `retrieve()` is deliberately *not* cached — a collection that
   returns stale objects from `retrieve` would violate what `IndexedCollection` promises.

## Alternatives considered

**Let the application cache it.** Entirely reasonable, and for many teams the right answer — a
`Caffeine` cache around the call site is ten lines and they control the invalidation. The argument
for putting it in the library is that the key (SQL plus parameters) is exactly what we already
have, and that getting it right once is better than every caller inventing it. **This is the main
argument against the proposal and should be weighed honestly before building it.**

**Rely on DuckDB.** DuckDB has no result cache. Its buffer pool means the *data* is warm, which is
already reflected in the 1.3 ms — there is no further win to be had there.

**Materialised views.** DuckDB supports them, and for a fixed dashboard they are strictly better
than a cache: computed once, queryable, no staleness policy to argue about. Worth documenting as
the answer for panels that are known in advance. A cache is for the case where the queries are not
known ahead of time.

## Recommendation

**Measure the hit rate before building anything.** The whole case rests on a dashboard asking
repeated identical questions, and that is an assumption about someone else's application, not
something measured here. The benchmark deliberately randomises panel parameters, so it does not
answer it either.

The cheap first step is to document **materialised views** for known panels, which needs no code at
all and is better than a cache where it applies. Build the cache only if real usage shows a high
rate of identical, parameter-for-parameter repeated queries.
