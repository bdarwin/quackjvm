# quackjvm examples

Standalone, copy-paste-runnable examples for the two quackjvm modules. Each file has a `main`
method, a header comment with the exact command to run it, and its real output at the bottom.

The `Core` ones need only `quackjvm-core` - and the three function examples need nothing of ours at
all beyond the DuckDB driver it brings in. The `Agent` ones need `quackjvm-agent`, and the `CqEngine`
ones are for the CQEngine plugin.

## Setup

Install the two artifacts into your local Maven repository, from the repository root:

```
mvn -pl quackjvm-core,quackjvm-cqengine,quackjvm-dashboard,quackjvm-agent install -DskipTests
```

Then write the runtime classpath to `target/classpath.txt`, which is what the run commands read:

```
cd examples
mvn -q generate-resources
```

## Running one example

```
java --enable-native-access=ALL-UNNAMED \
     -cp "$(cat target/classpath.txt)" \
     src/main/java/CoreColumnarRecords.java
```

Java 17 or later. `--enable-native-access=ALL-UNNAMED` silences the warning DuckDB's native library
otherwise produces on recent JDKs; on JDK 17 to 21 it is unnecessary and can be dropped.

## Running all of them

```
cd examples
./run-all.sh
```

## The examples

### Core - no CQEngine

| file | what it shows |
|---|---|
| [`CoreColumnarRecords.java`](src/main/java/CoreColumnarRecords.java) | Shred Java records into typed DuckDB columns with `ColumnarLayout`, write them with `TableWriter`, then query them with `SqlQuery`/`SqlRow` and rebuild the records from the rows. |
| [`CoreBulkLoad.java`](src/main/java/CoreBulkLoad.java) | Bulk-load a million rows from a lazy iterator into a DuckDB file, reporting the elapsed time and the resulting file size. |
| [`CoreMeasures.java`](src/main/java/CoreMeasures.java) | Values identified by several fields, stored sparsely - two million of them over two thousand records and nine thousand distinct keys - then read back grouped or pivoted by any field: the axis as columns, the axis as rows, one record, one group, units converted into one and added up, or kept apart. Every answer in 7-10 ms. Then written to Parquet in 131 ms, asked the same question straight from the file in 13 ms, and read back into another database in 93 ms. |
| [`CoreMeasureLifecycle.java`](src/main/java/CoreMeasureLifecycle.java) | The whole life of a measure: add under a day label, archive that day to one self-contained Parquet file, move it into a store of your own, then ask the files where they lie or read days back into a database that knows nothing about the measure - and do it after the measure has grown a field. Ends with a measure that archives itself when it passes a size. |
| [`AgentVectorSearch.java`](src/main/java/AgentVectorSearch.java) | The rows whose vector is nearest a given one, by cosine, distance or inner product, with a filter - on built-in array functions, so no extension and no network. Exact top-10: 19.0 ms over 10,000 x 768, 35.3 ms over 1,000,000 x 128. |
| [`CoreNestedTypes.java`](src/main/java/CoreNestedTypes.java) | Columns that hold more than one value - LIST, STRUCT, MAP and FLOAT[n] - written from a record's own types and read back, nulls and empties included, with SQL reaching inside them. No Arrow: since 1.5.5 the appender takes nested values, at 59 MB/s for FLOAT[768]. |
| [`AgentUdfs.java`](src/main/java/AgentUdfs.java) | A Java method called from SQL: one, two and three arguments, nulls decided rather than assumed, and what a call into Java costs - 8.8 ns a row unboxed against 0.7 ns for the SQL expression, over two million rows. |
| [`AgentLiveTables.java`](src/main/java/AgentLiveTables.java) | An ordinary `List<Car>` queried and joined as a table with no load step, still live as the list changes, with only the columns a query asks for read from the objects - and what that costs against loading the same objects first (1,000,000 objects: 44.6 ms to join live, 784 ms to load). |
| [`AgentCatalog.java`](src/main/java/AgentCatalog.java) | What is in the database and what it means: tables and columns with their types, descriptions and units - stored as DuckDB comments, so plain SQL can read them - then `SUMMARIZE` with a row count and example rows, and what each costs (describe 2 ms, profile 279 ms at ten million rows). |
| [`AgentGuardedQueries.java`](src/main/java/AgentGuardedQueries.java) | SQL from somewhere you do not control, run against a database with real data in it: what the parser allows, what it refuses and in what words, the row cap and the timeout, and what the check costs (0.61 ms). Shows the driver dropping a table from a `prepareStatement` call that was never executed - which is why the check never prepares anything. |
| [`CoreRefreshes.java`](src/main/java/CoreRefreshes.java) | Publishing a measure over and over: a full set, an increment that replaces the records it names, and a removal - each written as what it moved rather than as an overwrite, so the history survives and the state is always the sum of the contributions. Republishing the same refresh id applies it once. Ends with 200 refreshes of 100 records on disk: 7.29 ms each against 2.72 for a plain replace that keeps nothing. |
| [`CoreMeasureHistory.java`](src/main/java/CoreMeasureHistory.java) | Two databases: one holding current state, one holding every contribution ever made. Refreshes land in the live database with their contributions in an outbox; a shipper moves them in bulk - 22 ms for 50,000, 0.7 ms when there is nothing waiting - and the live database stays 2,316 KB while the history grows. Ends by forgetting old refreshes in the live ledger without touching the history. |
| [`CoreMeasureTimeline.java`](src/main/java/CoreMeasureTimeline.java) | A measure over two days: what it held at any point, a new full set as a baseline, a refresh dated in the past refused, then export to Parquet, a day compacted into one file, the history pruned back to the last full set - and the pruned point still answered from the files. Ends by throwing the live database away and rebuilding it from the history in 6 ms. |
| [`CoreMeasureParquet.java`](src/main/java/CoreMeasureParquet.java) | The file side of a measure: what the history exports and what a file holds, read three ways - with plain SQL and no quackjvm, including the five-line statement that turns contributions into a state at a point; over HTTP where the file lies, fetching only byte ranges; and into a database that has never heard of the measure, which reads the definition out of the file. |
| [`CoreMeasureAuditTrail.java`](src/main/java/CoreMeasureAuditTrail.java) | Reading the history as history: one record hour by hour, who changed what and by how much, what moved between two points, and which refreshes touched a record - all plain queries over the two history tables, because nothing is ever overwritten. |
| [`CoreTableFunction.java`](src/main/java/CoreTableFunction.java) | A plain `java.util.List` exposed as a table function, queried with SQL and joined against 200,000 stored rows with no load step - and live, so changing the list changes the next query. |
| [`CoreTableFunctionStreaming.java`](src/main/java/CoreTableFunctionStreaming.java) | A paged "remote" feed pulled one page at a time only as DuckDB asks for rows. Named parameters, projection pushdown, and the catch: a bare `LIMIT` stops the fetching, and on DuckDB 2.0 so does a `WHERE` in front of it - unless the filter rarely matches, since it never reaches the function. |
| [`CoreScalarFunction.java`](src/main/java/CoreScalarFunction.java) | Java functions called from SQL, in three forms, and what a call into the JVM costs per row against a built-in expression - about 11x on one thread, 13-16x on ten. |
| [`CoreConcurrency.java`](src/main/java/CoreConcurrency.java) | Many threads, one connection each. Eight dashboard users over five million rows with `threads` at the default, at half the cores, and behind a semaphore; read-modify-write transactions that conflict, roll back and retry without losing a cent; `SparseTable` writes racing `optimize()`. |
| [`CoreEntryPoint.java`](src/main/java/CoreEntryPoint.java) | All of it through `Quack`, with no connection in sight: 100,000 records written and asked questions of, a table and a measure written in one transaction, a failed transaction that leaves nothing of either behind, a measure shipped and read as of each point, and 8 threads at once with a quick query answering in 0 ms while a slow one runs. |
| [`AgentEntryPoint.java`](src/main/java/AgentEntryPoint.java) | The agent features through `Quack`: a live table and a Java function registered once on the database, the catalog, and a sandbox that refuses a write and a file. |
| [`CoreConnectionFacts.java`](src/main/java/CoreConnectionFacts.java) | What one database shares between its connections and what each keeps: an attached database, a Java function and a setting are seen everywhere; a temp table and a transaction are not, so one thread's rollback on a shared connection loses another thread's write. Opening a connection costs 4.4 us. The facts behind the [one entry point](../docs/proposals/one-entry-point.md) proposal. |

### CQEngine plugin

| file | what it shows |
|---|---|
| [`CqEngineSwap.java`](src/main/java/CqEngineSwap.java) | The drop-in swap: an on-heap `ConcurrentIndexedCollection` and a `DuckDBPersistence` one, same query code, with the Java heap each costs. |
| [`CqEngineIndexes.java`](src/main/java/CqEngineIndexes.java) | `DuckDBIndex` on a String, an enum and a double, then `equal`, `between` and compound `and`/`or`/`not` queries against them. |
| [`CqEngineJoins.java`](src/main/java/CqEngineJoins.java) | Two collections in one `DuckDBDatabase`: `existsIn` pushed into a SQL semi-join, `database.join(...)` for matched pairs, and `database.sql(...)` for an aggregate across both. |
| [`CqEngineBulkWriter.java`](src/main/java/CqEngineBulkWriter.java) | `DuckDBBulkWriter` streaming half a million objects in, with the indexes populated as they pass. |
| [`CqEngineMaterialization.java`](src/main/java/CqEngineMaterialization.java) | A windowed panel over five million rows precomputed into a table, refreshed fifteen times under four concurrent readers without one failed read, and a rollup kept current by folding in new rows. |
| [`PivotRollups.java`](src/main/java/PivotRollups.java) | Twenty users on a pivot dashboard over five million sales - PIVOT, months as columns, a join, a window - served from the base table and from one rollup per panel. Checks every panel returns the same rows both ways (28 of 28), then measures: 41 panels/s at a 477 ms median against 159 panels/s with the rolled-up panels at 1.5-2.5 ms, and rollups refreshed under the load without a failed read. |
| [`CqEngineConcurrency.java`](src/main/java/CqEngineConcurrency.java) | Readers unaffected by writers; two collections in one database writing in parallel; two writers on the same keys with `serializeWrites` on and off; and a `QueryOptions` shared between threads, refused instead of deadlocking. |
| [`DashboardDemo.java`](src/main/java/DashboardDemo.java) | The dashboard watching an application that chokes a different way every half minute - write-lock queueing, CPU contention from twenty users refreshing pivots over five million sales and a sparse wide view, SQL with values pasted in - so you can watch the diagnosis change. Needs `quackjvm-dashboard`. |

## Notes

- The timings printed by these examples include JIT warm-up and one-off setup. They show the shape
  of the thing, not benchmark numbers; the repository's JMH suite is for that.
- Anything holding a database connection - a `ResultSet` from a persisted collection, a `Stream`
  from `join(...)` or `sql(...)` - must be closed. Every example uses try-with-resources.
- The function examples use DuckDB's own table- and scalar-function API directly. It is new in
  DuckDB 1.5 and still changing between releases, which is why quackjvm does not wrap it yet.
