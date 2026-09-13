# Materializations

A materialization is a query precomputed into a DuckDB table. Requests are served from the answer
rather than from the data.

Measured on **10,000,000 sales**, a panel that ranks the top makes by revenue within each region —
a `GROUP BY` feeding a windowed `rank()`:

| | |
|---|---|
| the panel, against the base table | 25.0 ms |
| **from the materialization** (18 rows) | **0.8 ms — 32x** |
| sliced a way it was never asked for | 1.1 ms |
| refresh | 22.6 ms |

Results are identical — the materialization is the panel's own output.

## Using one

```java
DuckDBDatabase.ManagedMaterialization topMakes = database.materialize("top_makes")
        .as("SELECT region, make, sum(price) AS revenue,"
          + " rank() OVER (PARTITION BY region ORDER BY sum(price) DESC) AS rank"
          + " FROM sale GROUP BY 1, 2")
        .build();                      // builds the table if it is not there already

// It is an ordinary table. Query it like one.
List<Top> top = database.query("SELECT * FROM top_makes WHERE rank <= 3").records(Top.class);
```

`build()` creates the table if absent and leaves it alone if present, however stale — building is
not refreshing. The name you give it is the name you query.

| | |
|---|---|
| `refresh()` | rebuild it now, atomically |
| `refreshIfOlderThan(Duration)` | rebuild only if it has aged past that |
| `isOlderThan(Duration)`, `getBuiltAt()` | when this JVM last built it |
| `rowCount()`, `exists()` | |
| `drop()` | remove the table and forget it |

Without CQEngine, the same thing takes a connection per call, like `TableWriter`:

```java
Materialization topMakes = new Materialization("top_makes", sql);
topMakes.createIfAbsent(connection);
topMakes.refresh(connection);
```

## Refreshing does not interrupt readers

This is the part worth having in a library rather than writing yourself.

The obvious refresh is `DROP TABLE` then `CREATE TABLE AS`, which leaves a window in which the
table does not exist. Measured with four threads reading across fifteen refreshes:

| refresh style | reader failures |
|---|---|
| `DROP` then `CREATE TABLE AS` | **1,280** — `Catalog Error: Table with name ... does not exist!` |
| what `refresh()` does | **0** |

`refresh()` builds the replacement alongside under another name and swaps the two inside one
transaction. DuckDB's catalog is transactional, so a reader's statement resolves either the old
table or the new one, never the gap between them:

```sql
CREATE OR REPLACE TABLE top_makes__quackjvm_next AS SELECT ...;
BEGIN;
  DROP TABLE top_makes;
  ALTER TABLE top_makes__quackjvm_next RENAME TO top_makes;
COMMIT;
```

It is safe to refresh while readers are reading — that is the point. It is not safe to refresh the
same materialization from two threads at once.

## Two kinds, and they complement each other

**Materialize the panel** when you know the panel. Smallest result, biggest speedup, answers that
one question.

**Materialize a dimensional roll-up** when you do not. Keep every dimension a panel might group or
filter by, with **additive measures only** — counts and sums, never averages — and derive the rest
at query time as `sum(total)/sum(n)`. You cannot average an average.

```java
database.materialize("sales_rollup")
        .as("SELECT region, make, colour, year, count(*) AS n, sum(price) AS total"
          + " FROM sale GROUP BY 1, 2, 3, 4")
        .build();
```

On 10,000,000 rows that roll-up is 18,000 rows, 0.18% of the base table, and it answers questions
it was never designed for — a colour × year breakdown nobody anticipated runs in 0.9 ms against
21.9 ms on the base table.

| on 10M rows | build | the panel it was built for | a question it never saw |
|---|---|---|---|
| base table | – | 39.6 ms | 21.9 ms |
| the panel's result (30 rows) | 39.8 ms | **0.4 ms** | cannot answer it |
| dimensional roll-up (18,000 rows) | 55.2 ms | 2.1 ms | **0.9 ms** |

## Why not a cache, and why not a view

**A cached `List`** sits on the Java heap this library exists to keep empty, and it is opaque —
you can hand it back for the exact query it answers and nothing else. A materialization is a
columnar, compressed, off-heap table you can still filter, join and aggregate further.

**A `VIEW` is not a materialization.** DuckDB has no `CREATE MATERIALIZED VIEW` — it is a parser
error — and a plain `CREATE VIEW` stores the query, not its result, re-running it every time. On
4,000,000 rows: 5.6 ms for the aggregate against the base table, 5.7 ms through a view, 0.2 ms
against a real materialization.

## Keeping it in step

Refreshing is a full rebuild, which on ten million rows is 22–55 ms. For append-only data with
additive measures you can do better by folding in only the new rows — aggregate the delta and
append it, since the panels already sum over the table:

```sql
INSERT INTO sales_rollup
SELECT region, make, colour, year, count(*), sum(price)
FROM sale WHERE saleId >= ? GROUP BY 1, 2, 3, 4;
```

3.5 ms against 13.8 ms for a rebuild, with an identical result. There is no API for this yet; run
it with `database.query(...)` and keep your own watermark.

The trade is the same as any precomputation: **a materialization is as old as its last refresh.**
The difference from a cache is that a stale materialization still answers every question it covers,
slightly behind, where a stale cache entry is simply wrong for the one query it holds.

## What it costs

- **Storage.** It is a real table. A roll-up grouped on something high-cardinality — a model, an
  id — will be as large as the data. Watch `rowCount()`.
- **Non-additive measures cannot be rolled up.** A median or an exact `count(DISTINCT)` needs the
  base table, or a full rebuild of a materialization that computes it directly.
- **It does not redirect anything.** Queries name the materialization. Rewriting a query against
  the base table to target one automatically is
  [proposed but not built](proposals/materializations.md#should-it-be-seamless).
