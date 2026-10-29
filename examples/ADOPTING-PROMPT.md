# Prompt: adopt quackjvm 1.1.0 in an existing codebase

Paste everything below the line into a new session opened in the codebase that is adopting
quackjvm. Run **Phase 1** with a strong model (Opus 5.5 or Fable 5.1); it ends by writing a plan
file and stops. Run **Phase 2** with Sonnet 5.5, in a fresh session, pointing it at that file.

---

You are refactoring this codebase to use **quackjvm 1.1.0**, a Java 17 library for DuckDB, in place
of hand-written DuckDB/JDBC code. The code here was written by hand and differently from quackjvm,
so this is a refactor, not a version bump: every change must **preserve behaviour**. When quackjvm
does not fit a piece of code, leave that code alone - it is a valid outcome.

## What quackjvm is (verified against 1.1.0)

Maven Central, group `io.github.bdarwin`, version `1.1.0`:

| artifact | use it for |
|---|---|
| `quackjvm-core` | `Quack` (owns the database and its connections), `Rows` (typed SQL results), `ColumnarLayout` (Java objects to typed columns and back), `TableWriter` (bulk loading), `ConnectionPool`, measures and sparse tables, metrics. Depends only on `org.duckdb:duckdb_jdbc`. |
| `quackjvm-agent` | Only if needed: guarded read-only SQL (`GuardedQuery`, `QueryPolicy`, `Hardening`), `Catalog`, live tables (`LiveTables`), Java functions in SQL (`Udfs`), `VectorSearch`. Packages `io.quackjvm.agent.*`. |
| `quackjvm-cqengine` | Only if this codebase uses CQEngine. |

`io.quackjvm.core.Quack`, the entry point:
- Open: `Quack.open(path)`, `Quack.inMemory()`, `Quack.on(existingDuckDBConnection)` (works on duplicates; never closes the one given), `Quack.builder(path).settings(properties).history(location).metrics(m).connections(idle).open()`.
- Read: `db.query(sql, params...)` returns `Rows`: `.scalar(Class)`, `.scalarOptional`, `.list(Class)`, `.records(RecordClass)` (columns matched to record components by position), `.forEachRow(SqlRow -> ...)`, `.stream()`, `.count()`.
- Write: `db.execute(sql, params...)`, `db.createTable(name, layout)`, `db.write(name, layout, objects)` (replaces by the layout's first column, in one transaction).
- Transactions: `db.inTransaction(tx -> ...)` / `db.fromTransaction(tx -> ...)`: one connection, committed on return, rolled back on throw, **not retried** on conflict. `Tx` has `query`, `execute`, `write`, `createTable`, `connection()` (closing it does nothing; commit/rollback/autocommit on it are refused).
- Down to JDBC: `db.connection()` (a pooled connection of your own until closed; closing gives it back with anything uncommitted rolled back); `db.sharedConnection()` (borrows per statement, thread-safe, cannot hold a transaction).
- Database-wide registrations: `db.register(root -> ...)`; an `AutoCloseable` result is closed with the database.
- `Quack` is thread-safe and meant to be shared; every call borrows a connection and returns it, on failure as well.

`ColumnarLayout.ofRecord(MyRecord.class)`, `.reflective(MyClass.class)`, or `.builder(Class)...build()`.

## Facts that change behaviour - check each one against this codebase

1. **Driver version.** quackjvm 1.1.0 needs `duckdb_jdbc` **1.5.5 or later** and ships with 2.0.0.0. With 1.4.x on the classpath it compiles and fails at **run time** (`NoSuchMethodError`). Check `mvn dependency:tree` (or Gradle's equivalent) resolves 1.5.5+.
2. **Database files are one-way.** A file *created* by DuckDB 2.0 cannot be opened by DuckDB 1.x. Files from 1.4/1.5 open and are written under 2.0 and stay readable by their own version. Find every other reader of these files (services, tools, backups) before upgrading.
3. **A DuckDB connection runs one query at a time.** Code that shares one connection between threads queues (a quick query waited 1,136 ms behind a slow one). `Quack` gives each call its own; a transaction belongs to one connection, so shared connections also let one thread roll back another's work.
4. **Never `prepareStatement` SQL you did not write**: with this driver, preparing `"DROP TABLE t; SELECT 1"` executes the DROP. Untrusted SQL goes through `GuardedQuery` (quackjvm-agent).
5. **Point lookups are a sequential scan** in DuckDB even on a primary key (~200-400 µs on a million rows); an index does not change it. If this code does many single-row lookups in a loop, keep that path or batch it - do not "fix" it with an index.
6. **`null` query parameters bind as SQL NULL** in quackjvm.
7. **Arrow is optional.** With `arrow-vector`, `arrow-c-data`, `arrow-memory-unsafe` on the classpath and `--add-opens=java.base/java.nio=ALL-UNNAMED` on the JVM, wide reads take a columnar path; without the flag they silently fall back to JDBC rows. Do not add Arrow unless reads are a measured cost.
8. **Process memory after a bulk load** stays high (hundreds of MB) after the appender path: allocator retention, not a leak, and `memory_limit` does not lower it. Do not chase it as a regression introduced by the refactor.

## Rules for every change

- Preserve behaviour: same results, same nulls, same types (dates, decimals, enums, blobs), same transaction boundaries, same error surfaced to callers. If quackjvm would change any of these, say so in the plan and either keep the old code or get a decision.
- Before replacing any piece that the existing tests do not cover, add a test that pins its current behaviour, and see it pass against the **old** code first.
- One small step per change; the build and the whole test suite pass after each. No step mixes a refactor with a behaviour change.
- Do not change this codebase's own public API unless the plan says so.
- No new dependencies beyond the quackjvm artifacts named in the plan.
- Do not delete hand-written code that quackjvm does not replace.
- Never print credentials, tokens or connection strings with secrets in them.

## Phase 1 - survey and plan (strong model; read only)

Do not change any source file in this phase.

1. Find every place that touches DuckDB or JDBC: connection creation and lifetime, pooling,
   threading, transactions, SQL building, parameter binding, result mapping, bulk inserts, schema
   creation, file paths, settings (`threads`, `memory_limit`, extensions), and tests that cover them.
2. For each, write: what it does today (with file:line), the quackjvm piece that would replace it
   or "keep as is", what behaviour could change (from the facts above), and the test that pins it.
3. Order the steps so the codebase builds and passes after each: dependency and driver check first,
   then pinning tests, then one replacement at a time, riskiest last (connections, transactions,
   concurrency).
4. Mark each step **mechanical** (renames, version bumps, a call swapped for an identical one) or
   **judgement** (anything touching connections, transactions, threads, type mapping, or error
   handling).
5. Write it all to `QUACKJVM-MIGRATION.md` at the repository root: a short summary, the inventory
   table, the facts above that apply here, the open questions for a human, and the numbered steps
   with their before/after and the command that proves each. Then stop and report.

## Phase 2 - execute the plan (Sonnet 5.5)

Read `QUACKJVM-MIGRATION.md`. Then, for each step in order:

1. Do exactly that step. If reality differs from the plan (the code is not where it says, or the
   change would alter behaviour), stop and report instead of improvising.
2. Run the build and the whole test suite. If anything fails, fix it within the step or revert
   the step; never move on red.
3. Commit the step on its own, with a message naming the step number.
4. After every **judgement** step, stop and summarise what changed and why it preserves behaviour,
   so a human or a stronger model can review it before you continue.

When all steps are done, report: steps completed, steps skipped and why, tests added, anything in
the plan's open questions that is still open, and any behaviour that differs (which should be none).
