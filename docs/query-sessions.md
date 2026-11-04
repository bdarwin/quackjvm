# Query sessions

A `QuerySession` is one hardened, in-memory database that an application fills with tables under
names. It adds and drops tables while its user opens and closes files, and runs
[guarded queries](guarding-queries.md) that can join them.

```java
try (QuerySession session = QuerySession.open(
        Hardening.options().tempFolder(temp),            // folders come in as files are registered
        QueryPolicy.readOnly())) {

    String readings = session.register(Path.of("/data/readings-2026.csv"));   // "readings_2026"
    session.register(Path.of("/data/devices.csv"));                     // "devices"
    session.alias("t", readings);                                           // the one on screen, as t

    GuardedResult r = session.run("SELECT * FROM t JOIN devices USING (id)",
            CancelSignal.from(stopButton::isPressed));

    session.names();                    // [readings_2026, devices], for "Other open tables: ..."
    session.examples("t");              // ready-made queries, a join to devices among them
    session.unregister("devices");    // a query that names it now fails with DuckDB's message
}
```

## What a table is

Each registration is a view, so nothing is copied:

| Registered with | Read from |
|---|---|
| `register(Path)` | the file, with the reader its extension calls for: `read_parquet` for `.parquet`; `read_json` for `.json`, `.jsonl` and `.ndjson`; `read_csv` for everything else. Compressed files work too, such as `.csv.gz`. |
| `register(Path, sql)` | any SQL, named after the file |
| `register(name, sql)` | any SQL, under that name |
| `register(name, () -> rows, layout)` | Java objects, read where they lie: a [live table](live-tables.md) |
| `register(name, fileTable)` or `register(fileTable)` | a [file table](file-tables.md): a big file parsed by the application, kept on disk ([below](#file-tables)) |

A table registered under a name already in use replaces the old one. If the new one can't be
made, the old one stays. For example, a file that can't be read is refused with DuckDB's reason,
and the old table is still there.

`alias("t", name)` gives a registered table a second, fixed name. Calling it again moves the alias.
The alias goes when its table is unregistered.

## Names

`QuerySession.tableName(path, taken)` names a file's table. It is public so an application can
show the names in its UI:

| File | Table |
|---|---|
| `Readings 2026-Q4.tsv` | `readings_2026_q4` |
| `readings-2026.csv` | `readings_2026` |
| `events.csv.gz` | `events` |
| `Café menu.csv` | `cafe_menu` |
| `2026.csv` | `table_2026` |
| `order.csv` | `table_order` |
| `readings.csv`, with `readings` taken | `readings_2` |
| `t.csv` | `t_2` |

The rules:
- The name is lower case, with accents dropped, and every run of other characters becomes one `_`.
- A name that starts with a digit gets `table_` in front. So does a name that is one of DuckDB's
  reserved words, or one that can't name a table without quotes, such as `order` or `join`.
- Clashes get `_2`, `_3` and so on, compared without case, as DuckDB compares names.
- `t` and `log` are never given out, so an application can use them as aliases.
- Every name it gives can be written in SQL without quotes. A test checks the word list against
  DuckDB's `duckdb_keywords()`, so a DuckDB upgrade that adds a word gets noticed.

## Folders

The database opens with `Hardening.Options.properties()`, so it starts hardened and its settings
are locked: no network, no extensions, and no files outside the allowed folders.

DuckDB lets folders in only before the settings are locked. So when a file is registered from a
folder SQL can't read yet, or `allowFolders` adds one, the session opens a new database with the
wider list. It then remakes every registration and alias in it, under the same names, and closes
the old database.

- This took 32–37 ms with 50 CSV files registered.
- A folder that is in or below an allowed one changes nothing.
- If any registration can't be remade (say its file has gone), the call throws and the session
  stays exactly as it was.

The new database is hardened the same way: tests check that a URL and a folder outside the list
are still refused after a reopen.

## Stopping a query

`CancelSignal.from(BooleanSupplier)` turns a flag the application already has into a cancel. The
flag might be a Stop button, or an agent's tool call that was abandoned.

- The supplier is asked once before anything runs, so a flag that is already set stops the run
  before the query is parsed.
- While the query runs, the supplier is asked every 50 ms, or at another interval with
  `CancelSignal.polling(stop, interval)`.
- One daemon thread does the polling for every signal, and only while a query is running.
- Measured over 25 runs of a query that would take hours: it stopped 0–59 ms after the flag turned
  (median 31 ms). DuckDB itself stops within a millisecond of a cancel, so the interval is what
  sets the delay.

## Threads

`run` can be called from several threads at once, and each query borrows its own connection.

A change to the registrations waits for the queries that are running, and queries that start after
it see the change. A test runs six threads of queries while a table is registered and unregistered
40 times and the session reopens four times. Every query sees either the table or DuckDB's "does
not exist", and never anything else.

## Result windows

A grid that shows 50 rows of a query that returned two million can't take them all at once.
`session.window` runs the query once, keeps the whole result, and hands it out a window at a time:

```java
try (ResultWindow window = session.window(sql, CancelSignal.from(stop::get))) {
    long total = window.rowCount();
    List<Object[]> visible = window.rows(1_500_000, 100);
    window.sort("level", true);       // re-sorted without running the query again
    window.unsort();                  // back to the query's own order
}
```

- **Checks and limits.** The query is checked as `run` checks it, and the time limit and the
  cancel signal apply. The row and byte caps don't, because nothing is handed over at once.
- **Where it's kept.** The result goes into a table of an in-memory database attached to the
  session's, named `quackjvm_windows`. So it isn't among the session's tables or example joins.
  What doesn't fit in memory spills into the session's temp folder.
- **Reading.** A window finds its rows by position, and never reruns the query.
- **Sorting.** A sort keeps the query's order for rows that tie, and puts nulls last either way.

Measured on two million rows, with another program busy on 4–5 cores:

| | Time |
|---|---|
| A window of 100 rows at row 1,500,000 | 0.70 ms |
| The same, after a sort | 0.65 ms |
| A sort by a text column | 98 ms |

The stored result belongs to the session's database. So when the session reopens its database to
let in a folder, the window goes with the old one: reading it then throws, saying to make it again.
`isReadable()` tells you ahead of time.

## File tables

A [`FileTable`](file-tables.md) keeps a file the application parses - a log being written to, a
CSV too wide to load - as a table on disk. Registered in a session, it joins the session's other
tables, and `window` can read it:

```java
FileTable log = FileTables.builder(path, columns, LineSource.of(path), parser)
        .cacheDir(cache).roomLimit(5L << 30).version("viewer-3").open();

session.register("app_log", log);           // or register(log): a name from tableName(path)
session.alias("log", "app_log");

log.update(signal, progress);               // queries see each batch once it is stored
session.run("SELECT * FROM log JOIN devices USING (device_id)", signal);
session.window("SELECT * FROM log WHERE level = 'ERROR'", signal);
session.unregister("app_log");              // the table stays open, still the caller's to close
```

**How.** The table's cache file is attached to the session's database, so a join reads it where it
lies. A cache can be attached to one database at a time, so the table moves into the session's,
and back to one of its own when it is unregistered or the session closes. While it is registered,
it is queried through the session: `log.query(...)` says so. A table kept in memory has nothing to
attach, so its rows are read from the file again when it moves.

**Hardening.** The session lets DuckDB use exactly the cache's database, its write-ahead log and
its checkpoint file (`allowed_paths`), and nothing else of its folder: other caches, `.used` files
and other files there are refused. Without the checkpoint file, DuckDB 2.0 can't checkpoint the
attached database and invalidates it. DuckDB fixes the list when the database is locked, so
registering a file table opens the session's database again, as letting in a folder does, and
keeps every table. A test checks the refusals after registering, after the session reopens and
after the file is rotated: another cache, a `.used` file, a CSV and a glob of the cache folder, a
folder not allowed and a URL; and `ATTACH`, `DETACH`, `USE` and writes to the table are refused by
the guard.

**Growing.** `update` takes the table's lock a batch at a time, so a query storing lazy columns,
or the session reopening, goes between two batches. In a test, six threads ran joins while another
appended 100,000 lines in ten steps, then rotated the file, while the session reopened twice:
295–336 joins, and every one saw whole batches (its row count equal to its last line number) and
the join's matches, with no failures.

**Lazy columns.** A query reading lazy columns of a registered table stores them first, as the
table's own `query` does. The session plans the query over empty copies of all its tables, the
file table's with all its columns. In a test joining a 6,000-column table with a CSV, the join
stored the two columns it read, and a later query reading one more stored one more. `whyRejected`
says when a query would add more than `maxNewColumnsPerQuery`, or when its columns can't be told (a
`PIVOT`). `describe` and `examples` list all the table's columns, not only those stored.
[Computed columns](file-tables.md#columns-the-application-computes) added to the table while it
is registered are planned over too, and stored by the first query that reads them; a test joins
one with another table and checks its totals against Java's. `describe` counts the rows stored,
leaving out rows the [parser left out](file-tables.md#rows-that-arent-rows).

**Functions.** A file table's [Java functions](file-tables.md#java-functions) can be called in the
session's queries, and a cancel stops them as it does on the table: 3–4 ms after `cancel()`, in a
function taking 1 ms a row.

**Measured** on a 3,000,000-line log (276 MB):

| | |
|---|---|
| Joined with a 10,000-row CSV from another folder | 12–18 ms the first time, 10–11 ms the second; the same rows as the join over `read_csv` of the log |
| `window` of 2,000,000 of its rows, `rows(1_500_000, 100)` | median 1.05 ms |

A window keeps the result it had: lines added afterwards don't change it.

**Copies.** A table using a copy of its cache (`.2`...) because another process holds the first
works the same: a test holds the cache in a second JVM and joins the copy.

**Closing.** A file table closed while registered lets go of the session first. A query naming it,
or an alias of it, then fails saying the table was closed, and `unregister` takes it away.
`session.close()` doesn't close file tables: they go back to databases of their own, and work alone
again.

## Example queries

`session.examples(name)` and `Catalog.examples(table)` write ready-made queries from a table's
columns, without reading the table. `session.examples(name, session.profile(name).distinctValues())`
reads it once to choose a group column with few values. See [Catalog](catalog.md#example-queries).
