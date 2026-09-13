# Proposal: managed materializations

**Status:** proposed, measured, **recommended**. This supersedes the result-cache idea in
[Pre-aggregation vs. a result cache](result-cache.md).

## The idea

Precompute an expensive query into a DuckDB table, and serve requests from that table instead of
the base data. Two shapes of it, and they are complements rather than alternatives.

Measured on **10,000,000 rows**, with a panel expensive enough to be worth the trouble — top five
models by revenue within each region, which is a `GROUP BY` feeding a windowed `rank()`:

| | build | serve the panel | a question it never saw |
|---|---|---|---|
| base table | – | 39.6 ms | 21.9 ms |
| **the panel's result**, materialised (30 rows) | 39.8 ms | **0.4 ms — 99x** | cannot answer it |
| **a dimensional pre-aggregate** (18,000 rows) | 55.2 ms | 2.1 ms — 19x | **0.9 ms — 24x** |

The rewritten panel returns **identical results** — verified row by row against the base table, zero
differences.

Two things worth noticing:

**The narrow one is five times better at the job it was built for.** 0.4 ms against 2.1 ms, because
30 rows is less than 18,000. If you know the panel, materialise the panel.

**It is still a table, so it is still queryable.** Filtering the materialised result a different way
(`WHERE region = 'R2' ORDER BY revenue DESC`) also costs 0.4 ms. That is the difference between
this and caching the answer on the Java heap: a cached `List` is opaque and sits in the heap this
project exists to keep empty, while a materialised table is columnar, compressed, off-heap, and can
still be sliced.

## What actually needs library support

Not the precomputation — that is `CREATE TABLE AS SELECT` and needs nothing from us. **Refreshing it
without breaking readers does.**

The obvious refresh is `DROP TABLE` then `CREATE TABLE AS`. With four threads reading while fifteen
refreshes ran:

| refresh style | reader failures |
|---|---|
| `DROP` then `CREATE TABLE AS` | **1,280** — `Catalog Error: Table with name mv does not exist!` |
| build aside, then swap in one transaction | **0** (7,488 successful reads meanwhile) |

```sql
CREATE OR REPLACE TABLE mv_next AS SELECT ...;
BEGIN;  DROP TABLE mv;  ALTER TABLE mv_next RENAME TO mv;  COMMIT;
```

DuckDB's catalog is transactional, so readers see either the old table or the new one and never the
gap between them. That is a non-obvious, entirely mechanical piece of correctness, which is exactly
the sort of thing a library should own — anyone writing the obvious version ships the bug.

## The shape

```java
Materialization topModels = db.materialize("top_models")
        .as("SELECT region, make, model, sum(price) AS revenue,"
          + " rank() OVER (PARTITION BY region ORDER BY sum(price) DESC) AS rank"
          + " FROM sale GROUP BY 1,2,3")
        .build();

// Just a table. Query it like one - filtered, joined, aggregated further.
db.query("SELECT * FROM top_models WHERE region = ? AND rank <= 5", "R2").records(Row.class);

topModels.refresh();        // atomic; readers never see it missing
topModels.builtAt();        // when it was last refreshed
topModels.rowCount();
```

And for the dimensional case, where the measures are additive, the incremental refresh already
measured in [Aggregates](../aggregates.md#pre-aggregate-the-panels) — 3.5 ms to fold in 50,000 new
rows against 13.8 ms for a rebuild:

```java
db.materialize("sales_rollup")
        .as("SELECT region, make, colour, year, count(*) AS n, sum(price) AS total"
          + " FROM sale GROUP BY 1,2,3,4")
        .incrementalOn("saleId")     // fold in rows past the watermark instead of rebuilding
        .build();
```

## On "redirect queries there"

Automatically rewriting a query against the base table into one against a materialization is what
Oracle calls materialised-view rewrite and ClickHouse calls projections. **In general it is a
research problem** — deciding whether one SQL statement can be answered from another means solving
query containment, and DuckDB will happily accept SQL we did not generate and cannot parse.

The honest version is **routing by name**: you declare the materialization, and your panel queries
name it. That is one word of change at the call site and it gets essentially all of the benefit,
because a dashboard's panels are written once and run millions of times.

A narrow automatic rewrite is *possible* later — matching only queries that group by a subset of a
declared materialization's dimensions, filter only on those dimensions, and use only additive
measures — and it would let existing panels speed up without being edited. It should not be in the
first version, and it must always fall through to the base table when it cannot prove a match.

## What this costs

- **Storage.** A materialization is a real table. The dimensional one above is 0.18% of the base
  table; a badly chosen one, grouped on something high-cardinality like `model` plus `id`, would be
  as large as the data. The row count is the thing to watch.
- **Staleness.** Unchanged from any cache: it is as old as its last refresh. The difference is that
  a stale materialization still answers every question it covers, slightly behind, while a stale
  cache entry is simply wrong for the one query it holds.
- **Additive measures only, for incremental refresh.** `count`, `sum`, `min`, `max`, and averages
  derived from sum and count. A median or an exact distinct count needs a full rebuild — which at
  55 ms on ten million rows is not much of a hardship.

## Recommendation

**Build it.** It is the first thing in this thread that pays for its API surface:

1. Atomic refresh is a real correctness bug that users will otherwise ship — 1,280 failures against
   zero, measured.
2. Lifecycle (create, refresh, staleness, drop on close) is tedious and mechanical, which is what a
   library is for.
3. The speedups are large and hold at scale: 19–99x on ten million rows, and 24x on questions the
   materialization was never designed for.
4. It needs no query parsing, no heap, and no new staleness policy invented on the caller's behalf.

Start with routing by name and manual `refresh()`. Add incremental refresh for additive measures.
Leave automatic rewrite alone until there is a reason to believe it is needed.

Reproduce: `io.quackjvm.cqengine.bench.PreAggregateBenchmark` and the materialisation and swap
measurements recorded here.
