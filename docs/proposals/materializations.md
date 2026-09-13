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

## Should it be seamless?

Automatically rewriting a query against the base table into one against a materialization is what
Oracle calls materialised-view rewrite and ClickHouse calls projections. My first answer was that it
is a research problem, because deciding whether one SQL statement can be answered from another is
query containment, and we would have to parse SQL we did not generate.

**That was wrong about the hard part. DuckDB exposes its own parser, in both directions:**

```sql
SELECT json_serialize_sql('SELECT make, count(*) AS n, sum(price) AS total
                           FROM sale WHERE region = ''R0'' GROUP BY 1');
-- {"statements":[{"node":{"type":"SELECT_NODE","select_list":[...],
--   "from_table":{"table_name":"sale"},"where_clause":{...},"group_expressions":[...]}}]}

SELECT json_deserialize_sql(json_serialize_sql('SELECT 1'));   -- back to SQL, and it runs
```

The AST hands over exactly the four things a match needs: **`table_name`**, **`select_list`** with
each **`function_name`**, **`group_expressions`**, and **`where_clause`**. So we never write a
parser — the engine that will execute the query is the same one that parses it for us.

That makes a restricted rewrite tractable. A query is rewritable against materialization *M* when:

1. it reads exactly the base table of *M*;
2. every grouping expression is one of *M*'s dimensions;
3. the `WHERE` clause references only *M*'s dimensions;
4. every aggregate is derivable — `count(*)` → `sum(n)`, `sum(x)` → `sum(total_x)`, `min`/`max`
   directly, `avg(x)` → `sum(total_x)/sum(n)`.

Anything else — a median, an exact `count(DISTINCT)`, a filter on a column *M* does not carry, a
join, a window over the base grain — **falls through to the original query untouched**. That
fall-through is the whole safety argument: a bug in matching costs performance, never correctness.

### The condition I would put on it

**Seamless is only safe if it is verifiable.** Because both queries are available, quackjvm can run
them both and compare:

```java
DuckDBDatabase db = DuckDBDatabase.builder()
        .materializationRewrite(Rewrite.VERIFY)   // OFF | ON | VERIFY
        .build();
```

`VERIFY` runs the rewritten query *and* the original and fails loudly if they disagree — slow, and
exactly what you want in a test suite or a staging soak. You turn it on against your own panels,
prove the rewrite on your own data, then switch to `ON` in production. An optimisation that silently
changes results is unacceptable; one you can prove on your own queries first is a different
proposition.

### The real risk, which is not correctness

`json_serialize_sql` is an internal debugging facility, not a stable public API. **Its shape can
change between DuckDB releases**, and quackjvm pins a DuckDB version but users override that. So:
treat any unexpected AST shape as "not rewritable" and fall through; never throw. The failure mode
of a version bump must be that the rewrite quietly stops happening, not that queries break.

### So: staged

1. **Routing by name first.** One word at the call site, no parsing, all of the speedup. A
   dashboard's panels are written once and run millions of times, so naming them is cheap.
2. **Then the restricted rewrite, `OFF` by default, with `VERIFY`** for adoption.
3. **Never a general rewrite.** Containment over arbitrary SQL is not worth attempting here.

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
