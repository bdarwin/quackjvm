# Benchmarks outside the build

What is here is not a module of the root `pom.xml`: `mvn install` and CI never build or run it. Each
folder has its own `pom.xml` and a script that runs it.

## `questdb/`: the time-series comparison

The harness behind [docs/questdb.md](../docs/questdb.md). It puts quackjvm (DuckDB) next to
QuestDB, CQEngine on the heap and hand-written Java arrays, on the same seeded data: 10 million
readings, 1 million probes, at 10, 1,000 and 100,000 devices. The questions are the as-of join
(totals, and every row back to Java), the hourly average, the latest reading per device and the
last hour.

```bash
brew install questdb                         # once; the script starts and stops it
benchmarks/questdb/run.sh                    # quackjvm as this checkout builds it
benchmarks/questdb/run.sh -v 1.5.0           # a released version, from Maven Central
benchmarks/questdb/run.sh -d 1000 -e duck    # one size, one engine
```

The script:

- builds `quackjvm-core` from this checkout (`mvn package`, not installed into `~/.m2`), or with
  `-v` downloads the released jar from Maven Central and checks its checksum;
- runs one engine per JVM, one at a time; QuestDB is started for its own run only, with its default
  configuration and a fresh data folder under `questdb/target/qdb`, and stopped afterwards;
- waits before each engine until the 1-minute load average is below 5 (`-l` to change it), and
  records the figure;
- writes `questdb/results/<version>-<date>.txt` with every line printed, and
  `questdb/results/<version>-<date>.md` with the tables in the shape of docs/questdb.md and a check
  that every engine returned the same row count and sum.

With the defaults it takes about 45 minutes, most of it QuestDB's unhinted as-of join and
CQEngine. `run.sh -h` lists the options. `bench.Report` can be run on several results files at
once; a later line for the same engine, size and question replaces an earlier one, so one engine
can be rerun and appended.

The results files of past runs are kept in `questdb/results/`, so a release can be compared with
the one before.
