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

## Example queries

`session.examples(name)` and `Catalog.examples(table)` write ready-made queries from a table's
columns, without reading the table. See [Catalog](catalog.md#example-queries).
