# quackjvm examples

Standalone, copy-paste-runnable examples for the two quackjvm modules. Each file has a `main`
method, a header comment with the exact command to run it, and its real output at the bottom.

The first two need only `quackjvm-core` and have nothing to do with CQEngine. The rest are for the
CQEngine plugin.

## Setup

Install the two artifacts into your local Maven repository, from the repository root:

```
mvn -pl quackjvm-core,quackjvm-cqengine install -DskipTests
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

### CQEngine plugin

| file | what it shows |
|---|---|
| [`CqEngineSwap.java`](src/main/java/CqEngineSwap.java) | The drop-in swap: an on-heap `ConcurrentIndexedCollection` and a `DuckDBPersistence` one, same query code, with the Java heap each costs. |
| [`CqEngineIndexes.java`](src/main/java/CqEngineIndexes.java) | `DuckDBIndex` on a String, an enum and a double, then `equal`, `between` and compound `and`/`or`/`not` queries against them. |
| [`CqEngineJoins.java`](src/main/java/CqEngineJoins.java) | Two collections in one `DuckDBDatabase`: `existsIn` pushed into a SQL semi-join, `database.join(...)` for matched pairs, and `database.sql(...)` for an aggregate across both. |
| [`CqEngineBulkWriter.java`](src/main/java/CqEngineBulkWriter.java) | `DuckDBBulkWriter` streaming half a million objects in, with the indexes populated as they pass. |

## Notes

- The timings printed by these examples include JIT warm-up and one-off setup. They show the shape
  of the thing, not benchmark numbers; the repository's JMH suite is for that.
- Anything holding a database connection - a `ResultSet` from a persisted collection, a `Stream`
  from `join(...)` or `sql(...)` - must be closed. Every example uses try-with-resources.
