# quackjvm examples

Standalone, copy-paste-runnable examples for the two quackjvm modules. Each file has a `main`
method, a header comment with the exact command to run it, and its real output at the bottom.

The `Core` ones need only `quackjvm-core` - and the three function examples need nothing of ours at
all beyond the DuckDB 1.5 driver it brings in. The `CqEngine` ones are for the CQEngine plugin.

## Setup

Install the two artifacts into your local Maven repository, from the repository root:

```
mvn -pl quackjvm-core,quackjvm-cqengine,quackjvm-dashboard install -DskipTests
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
| [`CoreTableFunction.java`](src/main/java/CoreTableFunction.java) | A plain `java.util.List` exposed as a table function, queried with SQL and joined against 200,000 stored rows with no load step - and live, so changing the list changes the next query. |
| [`CoreTableFunctionStreaming.java`](src/main/java/CoreTableFunctionStreaming.java) | A paged "remote" feed pulled one page at a time only as DuckDB asks for rows. Named parameters, projection pushdown, and the catch: a bare `LIMIT` stops the fetching, a `WHERE` in front of it does not. |
| [`CoreScalarFunction.java`](src/main/java/CoreScalarFunction.java) | Java functions called from SQL, in three forms, and what a call into the JVM costs per row against a built-in expression - about 4x on one thread, 15x on ten. |
| [`CoreConcurrency.java`](src/main/java/CoreConcurrency.java) | Many threads, one connection each. Eight dashboard users over five million rows with `threads` at the default, at half the cores, and behind a semaphore; read-modify-write transactions that conflict, roll back and retry without losing a cent; `SparseTable` writes racing `optimize()`. |

### CQEngine plugin

| file | what it shows |
|---|---|
| [`CqEngineSwap.java`](src/main/java/CqEngineSwap.java) | The drop-in swap: an on-heap `ConcurrentIndexedCollection` and a `DuckDBPersistence` one, same query code, with the Java heap each costs. |
| [`CqEngineIndexes.java`](src/main/java/CqEngineIndexes.java) | `DuckDBIndex` on a String, an enum and a double, then `equal`, `between` and compound `and`/`or`/`not` queries against them. |
| [`CqEngineJoins.java`](src/main/java/CqEngineJoins.java) | Two collections in one `DuckDBDatabase`: `existsIn` pushed into a SQL semi-join, `database.join(...)` for matched pairs, and `database.sql(...)` for an aggregate across both. |
| [`CqEngineBulkWriter.java`](src/main/java/CqEngineBulkWriter.java) | `DuckDBBulkWriter` streaming half a million objects in, with the indexes populated as they pass. |
| [`CqEngineMaterialization.java`](src/main/java/CqEngineMaterialization.java) | A windowed panel over five million rows precomputed into a table, refreshed fifteen times under four concurrent readers without one failed read, and a rollup kept current by folding in new rows. |
| [`CqEngineConcurrency.java`](src/main/java/CqEngineConcurrency.java) | Readers unaffected by writers; two collections in one database writing in parallel; two writers on the same keys with `serializeWrites` on and off; and a `QueryOptions` shared between threads, refused instead of deadlocking. |
| [`DashboardDemo.java`](src/main/java/DashboardDemo.java) | The dashboard watching an application that chokes a different way every half minute - write-lock queueing, CPU contention from twenty users refreshing pivots over five million sales and a sparse wide view, SQL with values pasted in - so you can watch the diagnosis change. Needs `quackjvm-dashboard`. |

## Notes

- The timings printed by these examples include JIT warm-up and one-off setup. They show the shape
  of the thing, not benchmark numbers; the repository's JMH suite is for that.
- Anything holding a database connection - a `ResultSet` from a persisted collection, a `Stream`
  from `join(...)` or `sql(...)` - must be closed. Every example uses try-with-resources.
- The function examples use DuckDB's own table- and scalar-function API directly. It is new in
  DuckDB 1.5 and still changing between releases, which is why quackjvm does not wrap it yet.
