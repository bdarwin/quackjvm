# One entry point: the database, not the connection (design)

> **Built, 2026-10-05,** as `io.quackjvm.core.Quack` and `io.quackjvm.core.Tx` - see
> [What was built](#what-was-built) at the end for where it differs from what follows, and
> [`CoreEntryPoint`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CoreEntryPoint.java)
> for it running. Review findings C2 and C7 were fixed first, at the lower level.

Every feature of quackjvm-core is reached through a JDBC connection the caller opens, holds and
passes in. 105 public methods take one: `exposure.create(live)`, `refresh.commit(live)`,
`Rows.of(connection, sql)`, `VectorSearch.on(connection, table, column)`, `Catalog.of(connection)`,
`LiveTables.register(connection, ...)`, `Udfs.register(connection, ...)`.

So a client carries DuckDB around and has to know things that are not its business:

- when to `duplicate()`;
- that `Rows` closes the connection it is given;
- which thread may use which connection;
- that a measure refresh joins a transaction already open on the connection, and starts one if there is none;
- that `ship()` must not run inside one.

This proposal adds one object that owns the database, and puts every feature behind it. The client
opens it, uses it, closes it, and never sees a connection unless it asks for one.

```java
try (Quack db = Quack.open("live.duckdb")) {
    db.create(exposure);
    db.commit(MeasureRefresh.at(nine, "09:00").full(exposure, batch));

    db.query("SELECT point, sum(value) FROM exposure_value GROUP BY 1").forEachRow(...);
    db.query(db.asOf(exposure, nine).rows("group", "point")).forEachRow(...);
    db.vectors("note", "embedding").topK(question, 3);
    db.catalog().tables();
}
```

Nothing that exists is removed or changed. The classes underneath keep their connection-taking
methods; the entry point calls them. It is a layer on top, not a rewrite.

## What it is built on

The design follows from what one DuckDB database shares between its connections and what each
connection keeps to itself. Measured in
[`CoreConnectionFacts`](https://github.com/bdarwin/quackjvm/blob/main/examples/src/main/java/CoreConnectionFacts.java),
every connection duplicated from one root, as quackjvm's pool opens them:

| | Result |
|---|---|
| Opening a connection: `duplicate()` and `close()` | 4.4 µs (6.6 on DuckDB 1.5.5) |
| A database attached on one connection, seen on another | yes |
| A Java function registered on a connection since closed, called on another | yes (`plus_one(41) = 42`) |
| `SET threads = 3` on one connection, read on another | yes |
| A `TEMP` table, seen from another connection | no |
| A row not yet committed, seen from another connection | no |
| 1,600 reads from 8 threads on one shared connection | 0 failed, 0 wrong |
| A quick query while a 1-second one runs, on the same connection | answered in 1,136 ms |
| The same, on a connection of its own | answered in 0 ms |
| Thread A inserts on a shared connection, thread B rolls back | A's row is gone |

Three consequences:

1. **What the database shares, the entry point does once.** These are database-wide, so the entry
   point does each on whatever connection it has at hand, and every later connection sees it:
   - attaching the history;
   - registering a Java function or a live table;
   - applying hardening.

   Nothing has to be repeated per connection, and nothing is lost when that connection goes back
   to the pool.
2. **What a connection keeps, each unit of work gets its own.** A transaction belongs to the
   connection, not the thread. One connection shared by two threads means one thread's rollback
   loses the other's write. Reads survive sharing but queue: a connection runs one query at a
   time, so a quick query waited out a slow one on the same connection. So every call borrows a
   connection and returns it, and a transaction holds one connection from start to end.
3. **Pooling is not about the cost of opening.** 4.4 µs is cheap; the pool's own comment says "tens
   of microseconds", which is wrong and should be corrected. The pool still earns its place,
   because it keeps what a connection accumulates:
   - the prepared-statement cache;
   - `TableWriter`'s temp staging table, which would otherwise be recreated on every write.

## The shape

### Opening

```java
Quack.open("live.duckdb")                 // a file; created if absent
Quack.inMemory()
Quack.on(existingDuckDBConnection)        // wraps a connection the caller owns; never closes it
Quack.builder("live.duckdb")
     .hardening(Hardening.DATABASE)       // applied once, at open, before anything else runs
     .history("history.duckdb")           // attached once; measures ship to it
     .metrics(quackMetrics)
     .open();
```

`Quack` is thread-safe. It holds the root connection for its lifetime, which keeps the database
open, and a `ConnectionPool` duplicated from it. `close()` closes the pool, then the root.

`Quack.on(...)` is for code that already has a DuckDB connection, such as an application with its
own pool, or the CQEngine plugin. The entry point duplicates from that connection and never closes it.

### Calls that borrow and return

Each call borrows a pooled connection, does its work and returns the connection, on the success
path and the failure path alike. The caller never holds one, so the ownership questions go away:

| Today | Through the entry point |
|---|---|
| `Rows.of(c.duplicate(), sql, args)` | `db.query(sql, args)` |
| `GuardedQuery.on(c, policy)` | `db.guarded(policy)` |
| `Catalog.of(c)` | `db.catalog()` |
| `VectorSearch.on(c, table, column)` | `db.vectors(table, column)` |
| `measure.create(c)`, `sparse.create(c)` | `db.create(measure)` |
| `refresh.commit(c)` | `db.commit(refresh)` |
| `measure.query()...run(c.duplicate())` | `db.query(db.asOf(measure, at)...)`, `db.query(measure.query()...)` |
| `history.ship(c, measures)` | `db.ship(measures)` |
| `history.export(c, measure, location)` | `db.export(measure, location)` |

### Registrations that outlive the call

Java functions, live tables and the attached history are database-wide, so they belong to the
database, and the entry point keeps track of them:

```java
LiveTable cars = db.live("car", carList, layout);   // registered once, visible on every connection
Udf score = db.function("score", Double.class, Double.class, x -> x * 2);
```

Each returns a handle that can be closed early, and `db.close()` closes whatever is still open.
That ties a registration's life to the database it was made on, which is the right lifetime.

This does not fix review finding C2 on its own: `LiveTableFunction.SOURCES` was keyed by function
name across the whole JVM, so two databases collided. That was fixed first, at the lower level -
each registration is now keyed by a random token its own view passes - and the entry point builds on it.

### Transactions

This is the part that has to be right. Today the caller controls atomicity by holding the
connection: `setAutoCommit(false)`, write state, commit a refresh, `commit()`. `MeasureRefresh.commit`
already joins a transaction the caller has open and starts its own otherwise. That is what makes
"live state and its contributions commit together" possible.

The entry point keeps that, with the connection held for the caller:

```java
db.inTransaction(tx -> {
    tx.execute("UPDATE item SET count = count + ? WHERE id = ?", 5, 42);
    tx.commit(MeasureRefresh.at(nine, "09:00").increment(exposure, batch));
});
// and fromTransaction(tx -> ...) for work that returns a value
```

- `inTransaction` borrows one connection, turns autocommit off and hands the work a `Tx` bound to
  that connection.
- It commits when the work returns and rolls back when it throws. Every call made through `tx` runs
  on that one connection, inside that one transaction.
- **`Tx` is not `Quack`.** It offers the calls that are correct inside a transaction (`execute`,
  `query`, `commit(refresh)`, `create`) and leaves out the ones that are not. `ship`, `export`,
  `compact` and `prune` each run their own transactions against two databases, and DuckDB allows
  writes to only one attached database per transaction. Review finding N4 is `ship()` failing
  inside a caller's transaction. Through the entry point it cannot be written: there is no
  `tx.ship`.
- **A rollback leaves no stale key ids.** Review finding C7 was a caller's rollback leaving the
  measure's key-id cache holding ids the database no longer had. It was fixed at the lower level
  first: ids are remembered only after a commit the write owns, and inside a caller's transaction -
  which includes every `Tx` - nothing is cached. So the entry point needed nothing of its own for it.
- **Nesting:** `tx.inTransaction(...)` runs the inner work in the same transaction. DuckDB has no
  savepoints, so an inner failure fails the whole transaction. That will be documented, not hidden.

### The way back down

```java
try (Connection c = db.connection()) {     // borrowed; close() returns it to the pool
    ... anything DuckDB can do that quackjvm does not wrap ...
}
```

Nobody should have to choose between the entry point and the full power of DuckDB. The borrowed
connection is a pooled one. Closing it returns it to the pool, with anything left uncommitted rolled
back, which is what `ConnectionPool.release` already does.

## What it settles from the review

| Finding | Through the entry point |
|---|---|
| M4: `Rows.of` closes the caller's connection | The caller has no connection to lose. |
| N4: `ship()` inside a caller's transaction fails | `Tx` has no `ship`. |
| N6: `MeasureQuery.run` leaks its connection when it throws early | The entry point returns the connection in `finally`. |
| C7: a caller's rollback poisons the key cache | Fixed at the lower level first (`c2428e3`); nothing is cached inside a `Tx`. |
| C2: live tables leak across databases | Fixed at the lower level first (`2b6fac4`); the entry point closes its live tables with the database. |

These are the findings that come from the same confusion: who owns a connection, and who owns a
transaction. The entry point answers both in one place.

## What stays as it is

- **Every connection-taking method stays public.** `api-overview.md` will present them as the lower
  level: for embedding into an existing pool, for the CQEngine plugin, and for tests.
- **The CQEngine plugin is unchanged.** It has its own pool, request scopes and write lock. It could
  take a `Quack` later; nothing here requires it.
- **No new dependency.** One public class in `io.quackjvm.core`, one `Tx`, one handle type. The
  root package is empty today.

## What was built

Commits `2b6fac4` (C2), `c2428e3` (C7) and `fbe20dc` (the entry point), then the docs. 22 tests in
`QuackTest`; 451 in all, green.

The open questions, answered:

1. **The name** is `Quack`.
2. **Sandboxes:** `Quack.sandbox()` returns a second `Quack`, a separate in-memory database.
3. **Read-only use** is not built; nothing needed it.
4. **Examples:** one new one, `CoreEntryPoint`. The others still show the lower level, which they teach.

Where it differs from the design above:

- **Helpers that keep a connection for life get one that borrows per statement.** `GuardedQuery`,
  `Catalog` and `VectorSearch` hold the connection they are given. Handing them one shared connection
  would queue every query behind every other - measured above. So they get a connection whose every
  `prepareStatement` borrows from the pool and whose statement's `close()` gives it back. It refuses
  to start a transaction, since one statement per connection cannot hold one.
- **Writing objects is on the entry point too:** `createTable(name, layout)` and
  `write(name, layout, objects)`, the latter in one transaction so a replace is all or nothing.
  `Tx` has both. Without them, the first thing most callers do would have needed a connection.
- **Transactions are `inTransaction(tx -> ...)` and `fromTransaction(tx -> ...)`**, not one method.
  Overloading one name on a void and a value-returning lambda is ambiguous to the Java compiler for
  a lambda like `tx -> tx.execute(...)`.
- **Measure queries go through `db.query(measureQuery)`.** `db.asOf(measure, at)` returns the query,
  bound to the history; there is no second, bound copy of `MeasureQuery`'s builder.
- **What is not wrapped:** a measure's count and size methods, `SparseTable` reads, `compact`,
  `prune`, `rebuild`, the null-passing and primitive `Udfs` forms. Each is a line through
  `db.connection()`; add them when someone needs them.

## Open questions (as proposed)

1. **The name.** `Quack` is short and matches the project. `QuackDb` says what it is. `Database`
   collides with half the JVM.
2. **Sandboxes.** `Hardening.sandbox()` opens a separate in-memory database, because hardening is
   database-wide and cannot be undone. Should that be `Quack.sandbox()`, returning a second `Quack`?
   I think yes. It makes "the sandbox is another database" visible in the type.
3. **Read-only use.** Should `Quack.open(path, readOnly)` exist, for a process that only reads a
   file another process writes? DuckDB supports it. Nothing here needs it yet.
4. **How far the examples move.** Proposal: the examples written for 1.2 move to the entry point.
   The older ones that teach the lower level stay as they are, and each says which level it shows.

## The work (as proposed)

- `Quack`, `Tx` and the registration handle: some 400 lines, all of it delegation to classes that
  exist.
- Tests in `quackjvm-cqengine/src/test`, one per row of the two tables above. Each case runs through
  the entry point and is checked against the same case on the lower level:
  - borrow and return on failure;
  - commit and rollback;
  - `Tx` refusing what it should;
  - registrations closed with the database;
  - two `Quack`s in one JVM.
- `api-overview.md`, the README quick start, and an example: `CoreEntryPoint`, the same day of
  measures and queries as `CoreRefreshes`, with no connection in sight.
- Correct the pool's comment: opening a connection costs 6.6 µs, not tens of microseconds.

The review's critical findings C1–C7 are separate work and not blocked by this. Doing C2 and C7
first would let the entry point build on fixed ground rather than around known holes.
