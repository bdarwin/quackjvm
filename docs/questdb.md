# Compared with QuestDB

Time-series work comes down to a few questions: what was each device's last value at a given
moment, what did it average per hour, what is its latest value, what happened in the last hour.
The first of those is the **as-of join**, and it is the one most engines find hard. This page puts
quackjvm (in-process, built on DuckDB) next to QuestDB 10.1.0, a database built for time series, and next to
the two things a Java program would otherwise use: CQEngine on the heap, and arrays written by hand.

QuestDB was picked because it is a time-series database whose benchmarks may be published. kdb+'s
licence forbids that.

Measured on 2026-11-05 with quackjvm 1.5.0 and 1.6.1, both on DuckDB 2.0 (`duckdb_jdbc`
2.0.0.0). The harness is in
this repository and is rerun before each release; see [How to reproduce](#how-to-reproduce).

## The short answer

- **On the as-of join itself, quackjvm and a hinted QuestDB are level.** 202 ms against 173 at 10
  devices, 258 against 214 at 1,000, 295 against 350 at 100,000.
- **Returning a million rows to Java, quackjvm 1.6.1 is now the faster of the two**, when the rows are read
  as records: 281-368 ms against QuestDB's 495-823. Read value by value through `forEachRow`, it is
  1.4-2.2x slower than QuestDB; see [Reading many rows](#reading-many-rows-where-the-time-goes).
- **Most of what looked like quackjvm being slow was the JDBC driver turning text into Java
  strings.** At 100,000 devices the hourly average takes DuckDB itself 98 ms and reading its 2.36 million
  rows through JDBC another two seconds; read as records through Arrow, the whole question takes
  282 ms against QuestDB's 1,396. "Last hour" is 13 ms against 45.
- **"Latest per device" is QuestDB's**: 0.5 ms against 9 at 10 devices, 1.7 against 10.5 at 1,000.
  At 100,000 devices it is 47 ms against 82, and against 21 if the table is kept sorted by device
  (see [Latest per device](#latest-per-device)).
- **QuestDB's default as-of join falls over with many distinct keys.** 8.8 s at 1,000 devices, more
  than its 60 s query limit at 100,000. One hint fixes it, `/*+ asof_dense(p r) */` or
  `asof_linear`. You have to know to add it.
- **quackjvm needs no tuning for this, runs inside the JVM, and keeps the data in a third to a tenth
  of the disk.** No server to run, no hint to know.
- **CQEngine is not built for this.** At 1,000 devices its as-of join is about 900x slower than
  quackjvm's (extrapolated, see below), on 4 GB of heap against a 44 MB file, and it gets worse with
  more devices.
- **Hand-written arrays are the floor**, about 2-4x faster than either database on the as-of join.
  They also have no query language, no persistence and no concurrency.

## What was measured

Two tables, the same seeded data (seed 42) for every engine:

- `readings(device, ts, value)`: N rows evenly spaced over one day, each given a device at random.
- `probes(device, ts)`: M random times in the same day, each with a random device.

Five questions:

| | the question |
|---|---|
| **as-of totals** | for every probe, the device's last reading at or before it; count and sum them |
| **as-of rows back** | the same, but every probe's row returned to Java |
| **hourly average** | the average value per device per hour |
| **latest per device** | each device's newest reading |
| **last hour** | the average per device over the last hour of the day |

Every engine returned the same row count and the same sum for every question.

The engines:

- **quackjvm** 1.5.0 and 1.6.1: a file opened with `Quack.open`, loaded with DuckDB's
  appender, then `CHECKPOINT`. `device` is `VARCHAR`. Queried with `db.query(sql)`, rows read with
  `forEachRow`, and a second time with `forEachRecord` into a record, which quackjvm reads through
  Arrow (Arrow 18.1.0 on the classpath, `--add-opens=java.base/java.nio=ALL-UNNAMED`).
- **QuestDB 10.1.0**: installed with Homebrew and run natively, default configuration, a fresh data
  folder each run. WAL tables partitioned by day, `device` a `SYMBOL`. Loaded over ILP-over-HTTP
  (`questdb-client` 1.3.11); the load time includes waiting until the WAL is applied and every row
  is visible. Queried over PG wire (PostgreSQL JDBC 42.7.7) on localhost.
- **CQEngine 3.6.0 on the heap**: a `ConcurrentIndexedCollection` with a `HashIndex` on device and a
  `NavigableIndex` on time. The as-of lookup is `retrieve(and(equal(DEVICE, d), lessThanOrEqualTo(TS, t)))`
  ordered newest first, using index ordering, taking the first.
- **Java arrays**: a sorted array of times and values per device, and a binary search.

Times are the median of warm runs, in milliseconds: 9 runs for quackjvm and the arrays, 5 for
QuestDB, 3 for CQEngine. Each engine ran alone, in a JVM of its own: QuestDB was started for its
own run and stopped afterwards. Before each engine the harness waited until the 1-minute load
average was below 5; it was 4.2-4.9. One machine: Apple M1 Max, 10 cores, 32 GB, macOS, Java 25.

## 10 million readings, 1 million probes, 1,000 devices

| | as-of totals | as-of rows back | rows back, `ORDER BY p.ts` | hourly average | latest per device | last hour | load 11M rows | memory / disk |
|---|---|---|---|---|---|---|---|---|
| _CQEngine on heap_ | _2,287 per 10,000 probes_ | – | – | _1,953_ | _240_ | _56.7_ | _9.2 s_ | _3,991 MB heap_ |
| Java arrays | 61.8 | – | – | 13.1 | 0.0 | 1.0 | 0.1 s | 153 MB heap |
| quackjvm 1.5.0, `forEachRow` | 258 | 2,574 | 1,154 | 60.9 | 11.5 | 2.7 | 5.5 s | 44 MB file |
| quackjvm 1.6.1, `forEachRow` | 254 | 1,116 | 1,128 | 53.8 | 11.2 | 2.4 | 4.4 s | 44 MB file |
| quackjvm 1.6.1, `forEachRecord` | | 332 | 341 | 36.0 | 10.5 | 1.7 | | |
| QuestDB, `asof_linear` hint | 214 (`asof_dense` 234) | 567 | 556 | 112 | 1.7 | 1.9 | 6.0 s | 414 MB server RSS, 319 MB disk |
| QuestDB, no hint | 8,801 | | | | | | | |

CQEngine was run on the first 10,000 probes only. A million would take about 229 s at that rate;
that figure is extrapolated, not measured. The arrays' load is from arrays already in memory, so it
is not comparable with the others.

The rows-back columns for QuestDB use `asof_dense`, the hint this page started with; its
`ORDER BY p.ts` costs it nothing, as its as-of result is already in time order.

## How the number of devices changes it

The as-of totals, 10 million readings and 1 million probes:

| devices | _CQEngine on heap_ | Java arrays | quackjvm | QuestDB, best hint | QuestDB, no hint |
|---|---|---|---|---|---|
| 10 | _39.6 per 10,000 probes_ | 75.6 | 202 | 173 (`asof_linear`; `asof_dense` 191) | 275 |
| 1,000 | _2,287 per 10,000 probes_ | 61.8 | 258 | 214 (`asof_linear`; `asof_dense` 234) | 8,801 |
| 100,000 | _1,206 per 1,000 probes_ | 77.5 | 295 | 350 (`asof_dense`; `asof_linear` 372) | over 60 s: stopped by QuestDB's query limit |

quackjvm's time stays between 202 and 295 ms whatever the number of devices. So does QuestDB's with
a hint. Without it, QuestDB goes from 275 ms to more than a minute as the devices go from 10 to
100,000. Which hint is faster changes with the data: `asof_linear` at 10 and 1,000 devices,
`asof_dense` at 100,000.

CQEngine goes the other way. With index ordering it walks the time index back from each probe,
passing other devices' readings, so the cost grows with the number of devices: about 20x quackjvm at
10 devices, about 900x at 1,000 (both extrapolated from the probes measured). Its 100,000-device
figure flatters it: the first 1,000 probes all fall in the first two minutes of the day, and only
50 of them have an earlier reading, so most of the walks are short. Its "latest per device" at
100,000 devices had not finished after ten minutes when first tried, and is not run.

The other questions at the two ends. The quackjvm columns are 1.6.1; 1.5.0 is in brackets
where it differs by more than 10%:

| | 10 devices: _CQEngine_ | 10: quackjvm, `forEachRow` | 10: quackjvm, `forEachRecord` | 10: QuestDB | 100,000 devices: _CQEngine_ | 100,000: quackjvm, `forEachRow` | 100,000: quackjvm, `forEachRecord` | 100,000: QuestDB |
|---|---|---|---|---|---|---|---|---|
| as-of rows back | – | 1,076 (1,940) | 281 (1,200) | 495 | – | 1,162 (2,538) | 368 (1,788) | 823 |
| rows back, `ORDER BY p.ts` | – | 1,075 | 285 (326) | 495 | – | 1,169 | 383 | 798 |
| hourly average | _1,608_ | 29.3 (36.2) | 29.6 (35.5) | 34.9 | _3,698_ | 2,139 | 282 (414) | 1,396 |
| latest per device | _0.1_ | 9.0 | 9.2 (10.6) | 0.5 | _not run_ | 160 (220) | 82 (128) | 46.7 |
| last hour | _58.4_ | 1.4 | 1.6 (1.8) | 1.4 | _105_ | 90.8 | 13.3 (16.3) | 45.4 |
| load 11M rows | _9.2 s_ | 4.7 s | | 5.3 s | _11.6 s_ | 5.0 s | | 9.1 s |
| memory / disk | _4,086 MB heap_ | 31 MB file | | 420 MB RSS, 317 MB disk | _4,027 MB heap_ | 82 MB file | | 903 MB RSS, 334 MB disk |

At 100,000 devices the hourly average returns 2.36 million groups. The Java arrays, for scale: 10.0,
0.0 and 1.1 ms for the hourly average, latest and last hour at 10 devices; 39.2, 0.8 and 7.2 at
100,000.

**Disk.** quackjvm's file is 31 MB at 10 devices, 44 MB at 1,000 and 82 MB at 100,000. QuestDB's two
tables took 317-334 MB just after the load, WAL included. On the first run, a few minutes after the
load, once QuestDB had cleaned up its WAL, they took 224-257 MB. That is between a tenth and a
third of the disk.

## Reading many rows: where the time goes

Returning rows to Java is where most of quackjvm's time went, and almost none of it is the query. The
as-of join returning 100,000 rows (1 million readings, 1,000 devices), with each read path:

| How the rows are read | quackjvm 1.5.0 | quackjvm 1.6.1 |
|---|---|---|
| DuckDB alone: the count and sum of the result, inside DuckDB | 35 | |
| plain JDBC `Statement`, every column read | 124 | |
| prepared JDBC statement, every column read | 270 | |
| `db.query(sql).forEachRow`, as the harness reads | 271 | 119 |
| the same, with `ORDER BY p.ts` | 120 | 121 |
| `db.query(sql).forEachRecord` (Arrow) | 187 | 41 |
| the same, with `ORDER BY p.ts` | 43 | 44 |
| Arrow export from a plain statement, by hand | 40 | |
| `GuardedQuery` (unprepared, every value boxed into a list) | 128 | |
| _CQEngine on heap: the same as-of join, every probe answered one by one (2026-11-03)_ | _22,298_ | |

Two costs, neither in quackjvm's own code:

- **The JDBC driver builds every `VARCHAR` value as a Java string, in native code, as it fetches.**
  That is about 0.86 µs a value: selecting `r.value` alone read in 32 ms; adding `p.ts` made it 33;
  adding `p.device` made it 118. Every column of a fetched chunk is converted whether it is read or
  not. Arrow hands over the column's bytes, and the strings are made in Java: 40 ms for all three
  columns.
- **DuckDB runs some queries far slower prepared**: an ASOF join returning many rows with no
  `ORDER BY`. 1.5.0 prepared every query, which is how it checks that SQL only reads. 1.6.1
  runs a `SELECT` with no parameters unprepared: it returns rows whatever it says, so there
  is nothing to check. A query with `?` parameters is still prepared; for a large ASOF result with
  parameters, add an `ORDER BY` (see
  [Tuning](tuning.md#a-large-result-read-prepared-add-an-order-by)).

So **read a large result with `forEachRecord`, `records` or `forEachValue`** when Arrow is on the
classpath: they read through Arrow, and `forEachRow` reads through JDBC. At full size, a million
rows of the as-of join took 1,116 ms through `forEachRow` and 332 through `forEachRecord`, at 1,000
devices; QuestDB took 567. Small results gain nothing either way: 1,000 rows of "latest" took
11.2 ms and 10.5.

Skipping the prepare also helps small queries: `db.query("SELECT 1")` took 76 µs, against 147 µs in
1.5.0 (1,000 calls, median of 5).

## Latest per device

`LATEST ON` is QuestDB's clearest lead. QuestDB keeps each table in time order and reads it from
the newest end; DuckDB's query aggregates over every row. Other forms of the query, timed inside
DuckDB (the count and sum of the result, no rows returned), and read with `forEachRecord`, on
quackjvm 1.6.1, 10 million readings:

| | 1,000 devices: in DuckDB | 1,000: read as records | 100,000: in DuckDB | 100,000: read as records |
|---|---|---|---|---|
| `arg_max(value, ts) ... GROUP BY device` | 8.9 | 11.3 | 71.6 | 84.7 |
| `QUALIFY row_number() OVER (PARTITION BY device ORDER BY ts DESC) = 1` | | | 74.2 | 89.0 |
| `device` an `ENUM` | 8.6 | 10.9 | 47.4 | 58.5 |
| the table sorted by `device, ts` | 9.0 | 13.8 | **10.0** | **20.8** |
| _CQEngine on heap, newest reading per device through its indexes_ | | _240_ | | _not run_ |
| _QuestDB `LATEST ON`, for comparison_ | | _1.7_ | | _46.7_ |

With 100,000 devices, a table kept in device order answers in a seventh of the time: the
aggregate meets each device's rows together. Rewriting the 10 million rows that way took 376 ms
(`CREATE TABLE ... AS SELECT * FROM readings ORDER BY device, ts`, then `CHECKPOINT`). It is no help
with 1,000 devices, where the query is a scan of every row either way, and it costs the other
questions there: "last hour" went from 1.5 ms to 4.4, since the rows of the last hour are no longer
together. At 100,000 devices "last hour" was 6.4 ms against 6.8, and the hourly average 98 against
48.5. `DISTINCT ON (device) ... ORDER BY device, ts DESC` was slower than `arg_max` (147 ms against
78 at 100,000 devices, on 1.5.0).

So with many devices, keep the table sorted by device if "latest" matters most, and sort again
after large loads. With few devices, 9-11 ms is a scan of 10 million rows, and only something that
keeps the latest row as it is written (a table updated on each write) would match QuestDB.

## Since the first run

This page was first measured on 2026-11-03 with quackjvm 1.3.0-SNAPSHOT, on the same machine,
DuckDB 2.0 and QuestDB 10.1.0. The query path of `db.query` did not change between 1.3.0 and 1.5.0,
and 1.5.0's figures are within 10% of the first run's, as are CQEngine's and QuestDB's (the arrays'
within a millisecond), except:

| | 2026-11-03 | 2026-11-05 | |
|---|---|---|---|
| _CQEngine, as-of totals per 10,000 probes, 1,000 devices_ | _2,662_ | _2,287_ | _-14%_ |
| _CQEngine, last hour, 10 devices_ | _431_ | _58.4_ | _the first run had a 7.5 s outlier_ |
| quackjvm, hourly average, 1,000 devices | 56 | 60.9 | +9%, 1.5.0 |
| QuestDB, no hint, 1,000 devices | 11,098 | 8,801 | -21% |
| QuestDB, hourly average, 1,000 devices | 83 | 112 | +35% |
| QuestDB, latest per device, 1,000 devices | 1.0 | 1.7 | |
| QuestDB, last hour, 1,000 devices | 1.5 | 1.9 | |
| QuestDB, `asof_dense`, 100,000 devices | 396 | 350 | -12% |
| QuestDB, `asof_linear`, 100,000 devices | 459 | 372 | -19% |
| QuestDB, load, 100,000 devices | 8.0-8.5 s | 9.1 s | |
| QuestDB, as-of rows back, 100,000 devices | 1,057 | 823 | -22% |
| QuestDB, server RSS, 100,000 devices | 1,152 MB | 903 MB | |
| QuestDB, `asof_linear`, 10 and 1,000 devices | not run at 10; 244 at 1,000 | 173; 214 | now its best hint there |

Nothing changed in QuestDB, CQEngine, their configuration or the data, so those differences are
run to run: this run started QuestDB on a fresh data folder for each size and waited for a quiet machine
before each engine; the first was run in separate sessions with the load average at 3-7. What did
change is quackjvm, in 1.6.1: running a `SELECT` with no parameters unprepared halved "rows back"
through `forEachRow` (2,574 to 1,116 ms at 1,000 devices) and made it a fifth through
`forEachRecord` (1,776 to 332), and took "latest" at 100,000 devices from 220 to 160 ms.

## The SQL

The queries differ in spelling, not in what they ask.

**As-of join.** QuestDB joins on the designated timestamp implicitly and keeps every probe, with
nulls where there is no earlier reading. DuckDB names the inequality, and needs `ASOF LEFT JOIN` to
keep the probes with no match. `count(r.value)` counts only the matches, so the totals agree.

=== "quackjvm"

    ```sql
    -- as-of totals
    SELECT count(r.value), sum(r.value)
    FROM probes p ASOF JOIN readings r ON p.device = r.device AND p.ts >= r.ts

    -- as-of rows back
    SELECT p.device, p.ts, r.value
    FROM probes p ASOF LEFT JOIN readings r ON p.device = r.device AND p.ts >= r.ts
    ```

=== "QuestDB"

    ```sql
    -- as-of totals; without a hint this is the 8.8 s query
    SELECT /*+ asof_dense(p r) */ count(r.value), sum(r.value)
    FROM probes p ASOF JOIN readings r ON (device)

    -- as-of rows back; ORDER BY p.ts changes nothing
    SELECT /*+ asof_dense(p r) */ p.device, p.ts, r.value
    FROM probes p ASOF JOIN readings r ON (device)
    ORDER BY p.ts
    ```

**Hourly average, latest per device, last hour.**

=== "quackjvm"

    ```sql
    SELECT device, time_bucket(INTERVAL 1 HOUR, ts) b, avg(value) a FROM readings GROUP BY ALL

    SELECT device, arg_max(value, ts) v, max(ts) t FROM readings GROUP BY device

    SELECT device, avg(value) FROM readings
    WHERE ts >= make_timestamp(1767308400000000) GROUP BY device
    ```

=== "QuestDB"

    ```sql
    SELECT device, ts, avg(value) a FROM readings SAMPLE BY 1h

    SELECT device, value, ts FROM readings LATEST ON ts PARTITION BY device

    SELECT device, avg(value) FROM readings WHERE ts >= '2026-01-01T23:00:00Z'
    ```

The records read with `forEachRecord` line up with the selected columns, for example
`record ProbeRow(String device, LocalDateTime ts, Double value)` for the as-of rows.

## What to take from it

**Use QuestDB** when the data is time series first, it already runs as a server, and "latest" with
few keys matters most. Add a hint to every as-of join with more than a handful of keys, and check
the plan.

**Use quackjvm** when the data belongs to a Java program and should stay in it. There is no server
to run or monitor, no network between the program and its data, and no hint to find: the as-of
join stays between 202 and 295 ms from 10 devices to 100,000. The same data takes a third to a
tenth of the disk. Read large results with `forEachRecord` or `records`. The rest of DuckDB's SQL
comes with it: window functions, joins across tables, Parquet, the [measures](measures.md) this
library adds.

**Do not use CQEngine for as-of joins.** It answers one probe at a time through two indexes, and
keeps every reading as an object: 4 GB of heap for what quackjvm keeps in a 44 MB file.

**Write the arrays yourself** only when this one question is the whole program and it must be as
fast as the machine allows.

## Caveats

- **One machine.** Apple M1 Max, 10 cores, 32 GB, macOS, not otherwise idle: the harness waited for
  a load average below 5, and other programs ran between and during the runs. Other hardware will
  move the numbers.
- **Server against in-process.** QuestDB answers over PG wire on localhost; quackjvm answers inside
  the JVM. That is the real difference between deploying the two, not a flaw in the test. QuestDB's
  times include the wire.
- **Memory is not like for like.** QuestDB's figure is the server's resident memory. quackjvm's
  is its file (DuckDB's buffer pool held as much). The heap figures are the JVM's.
- **Reading rows is not like for like.** quackjvm's rows come back value by value
  through `forEachRow` or as records through Arrow; QuestDB's through PostgreSQL JDBC's
  `ResultSet`, one column read per row.
- **Default configurations.** Neither database was tuned beyond the join hint.
- **Synthetic, uniform data.** Readings evenly spaced, devices uniform at random. Skewed keys or
  late, out-of-order readings may change the picture, and favour different hints.
- **Warm runs.** Each figure is the median after a first run. Cold reads from disk were not measured.
- **1.6.1 was measured just before its release**, built from commit `80762c1`; nothing in
  `quackjvm-core` changed between that commit and the release. 1.5.0's figures are from the jar on
  Maven Central.

## How to reproduce

The harness is `benchmarks/questdb/` in this repository, outside the build: a single Java program
and a script that runs it. For each engine it generates the data from seed 42, loads it, runs each
question once to warm up and then a set number of times, and prints the median with a row count
and sum to check against the other engines.

```bash
brew install questdb                    # once
benchmarks/questdb/run.sh               # quackjvm as this checkout builds it, every engine
benchmarks/questdb/run.sh -v 1.5.0      # a released version, from Maven Central
```

The script starts and stops QuestDB, waits for a quiet machine before each engine, and writes
`benchmarks/questdb/results/<version>-<date>.txt` and `.md`; the `.md` has the tables above and the
check that every engine agreed. The figures on this page are from `1.5.0-2026-11-05` and
`1.5.0-local-80762c1-2026-11-05` there. See `benchmarks/README.md`.

- Generate `readings`: row `i` at `start + i * day / N`, device `random.nextInt(D)`, value
  `round(random.nextDouble() * 10,000) / 100`. Then `probes`: M times `start + random.nextDouble() * day`,
  sorted, each with device `random.nextInt(D)`. Start is 2026-01-01T00:00:00Z; devices are named
  `d00000`, `d00001`, ...
- quackjvm: `Quack.open(file)`, create the tables, append with `DuckDBAppender`, `CHECKPOINT`, then
  the queries above through `db.query(sql)`.
- QuestDB: start it with its defaults, create the tables over PG wire, send rows with
  `Sender.fromConfig("http::addr=localhost:9000;auto_flush_rows=100000;")`, wait until
  `SELECT count()` shows every row, then the queries above.
- CQEngine: the first 10,000 probes (1,000 at 100,000 devices), and no "latest" at 100,000 devices.

```sql
-- QuestDB tables
CREATE TABLE readings (device SYMBOL CAPACITY 1024, value DOUBLE, ts TIMESTAMP)
    TIMESTAMP(ts) PARTITION BY DAY WAL;
CREATE TABLE probes (device SYMBOL CAPACITY 1024, ts TIMESTAMP)
    TIMESTAMP(ts) PARTITION BY DAY WAL;

-- quackjvm tables
CREATE TABLE readings (device VARCHAR, ts TIMESTAMP, value DOUBLE);
CREATE TABLE probes (device VARCHAR, ts TIMESTAMP);
```

That is for 1,000 devices. The harness sets the `SYMBOL` capacity to twice the largest power of two
not above the device count, and at least 256.

The read-path and query-form tables come from probes run separately with the same data, not from
the harness: the read paths at 1 million readings, 100,000 probes and 1,000 devices, the forms of
"latest" at 10 million readings.
