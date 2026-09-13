# Pre-aggregation vs. a result cache

**Status:** measured. Recommendation: **pre-aggregate; do not build a result cache into the
library.** Prompted by the dashboard numbers in
[Aggregates and projections](../aggregates.md#many-people-the-same-panels).

## First, a correction

An earlier draft of this page recommended materialised views. **DuckDB 1.4.1 does not have them.**

```
CREATE MATERIALIZED VIEW mv AS SELECT ...
  -> Parser Error: syntax error at or near "MATERIALIZED"
```

A plain `CREATE VIEW` is a live view: it re-runs the query against the base table every time and
caches nothing. The only way to precompute in DuckDB is `CREATE TABLE AS SELECT` — a real table you
keep in step yourself.

Which invites the fair objection: **that is just a cache in a different place, with the same
staleness problem.** It is worth taking seriously, so this page measures the difference rather than
asserting it.

## The setup

2,000,000 sales; a dashboard's four panels. The pre-aggregate carries every dimension a panel might
group or filter by, with **additive measures only** — counts and sums, never averages:

```sql
CREATE TABLE agg AS
SELECT region, make, colour, year, count(*) AS n, sum(price) AS total
FROM sale GROUP BY 1,2,3,4;
```

**3,000 rows — 0.2% of the base table — built in 17 ms.** Averages are derived at query time as
`sum(total)/sum(n)`, which is why the measures have to be additive: you cannot average an average.

## What it buys

| panel | base table | pre-aggregate | |
|---|---|---|---|
| group by make: count and average price | 6.3 ms | **1.31 ms** | 5x |
| pivot: colour across make | 10.1 ms | **1.84 ms** | 5x |
| revenue by year for one make | 2.7 ms | **0.64 ms** | 4x |
| headline numbers for one region | 2.3 ms | **0.44 ms** | 5x |
| **a slice nobody precomputed** — region × year, APAC only | 2.6 ms | **0.52 ms** | 5x |

Note the last row. **That query was never anticipated, and the pre-aggregate still answers it 5x
faster.** A result cache would have missed it completely and scanned all two million rows. This is
the real difference between the two, and it is not a matter of degree: a cache can only answer
questions it has already been asked, parameter for parameter, while a pre-aggregate answers any
question that can be derived from its grain.

**The honest limit is that 5x, not 100x.** Once the table is 3,000 rows the query no longer costs
anything — what is left is DuckDB's fixed ~0.5 ms per statement, which no amount of precomputation
removes. A cache hit *would* beat that, because it issues no statement at all.

## Staleness: the part the objection is really about

Both go stale. They do not go stale the same way.

**A pre-aggregate can be incrementally maintained; a cached result cannot.** For additive measures,
folding in new rows is just aggregating the new rows and appending the delta — panels already sum
over the table, so duplicate group rows are simply summed too:

```sql
INSERT INTO agg
SELECT region, make, colour, year, count(*), sum(price)
FROM sale WHERE saleId >= :watermark GROUP BY 1,2,3,4;
```

Measured with 50,000 new sales arriving:

| | |
|---|---|
| full rebuild | 13.8 ms |
| **fold in the delta** | **3.5 ms** |
| result identical to a full rebuild | **yes** — verified group by group |

A cache entry has no equivalent. You cannot partially update "the answer to that query"; you can
only throw it away and pay the full price on the next request.

The other asymmetry: **a stale pre-aggregate still answers every question**, just slightly behind.
A stale cache entry is simply wrong for the one query it holds.

## What it cannot do

Non-additive measures. A pre-aggregate can carry `count`, `sum`, `min` and `max`, and averages
derived from sum and count. It cannot carry a median or an exact distinct count, because those
cannot be combined from partial groups:

| | base table |
|---|---|
| median price | 0.4 ms — no pre-aggregate equivalent |
| exact distinct models | 0.4 ms — no pre-aggregate equivalent |

In practice this matters less than it sounds: both are already fast, because they are the queries
DuckDB is built for. `approx_count_distinct` *is* mergeable via HyperLogLog if you need distinct
counts at scale.

Incremental maintenance also assumes **append-only** data. Deletes and updates break the fold-in,
because a delta can add to a group but not remove from it. A mutable base table means either
compensating negative deltas or a periodic full rebuild — which at 17 ms is not much of a hardship.

## The two are not alternatives

- **A pre-aggregate reduces the work**: 2,000,000 rows become 3,000. It composes, it is
  incrementally maintainable, and it lives in DuckDB rather than on the Java heap.
- **A cache eliminates the work** for a question already asked, and does nothing for any other.

They stack, and the pre-aggregate is the one to do first, because it needs no library support at
all — it is a `CREATE TABLE AS SELECT` and a `WHERE id >= :watermark`.

## Recommendation

**Do not build a result cache into quackjvm.**

1. **Pre-aggregate first.** It is 4–5x, it composes, it is incrementally maintainable, it stays off
   the heap, and it requires nothing from this library beyond the SQL you already have. Document
   the pattern, including the additive-measures rule and the watermark refresh.
2. **If a cache is still wanted after that, it belongs at the call site.** The remaining upside is
   DuckDB's ~0.5 ms statement floor. Ten lines of Caffeine around the call give the same benefit,
   and the application is the only thing that actually knows when its data changed — which is the
   entire difficulty. A library-level TTL would be guessing on the caller's behalf.
3. **Never cache `retrieve()`.** A CQEngine `IndexedCollection` promising objects it no longer
   contains is a correctness bug, not a tuning option. Whatever happens on the SQL side, the object
   API stays live.

The measurement that would change this recommendation is a real dashboard showing a high rate of
**parameter-for-parameter identical** queries. The benchmark here deliberately varies the
parameters, because that is what a dashboard with filters does.

Reproduce: `io.quackjvm.cqengine.bench.PreAggregateBenchmark`.
