# Post-review TODO

Reviewed 2026-10-01 at `13959db`, the whole code base: three independent reviews (the agent-readiness
packages; measures and foundations; the CQEngine plugin and the dashboard), plus the SonarQube and
JaCoCo pass from 2026-09-30, plus the examples compiled against the installed jars. Every item
marked **CONFIRMED** was reproduced on this machine by the reviewer *and* re-run by the author of this
list; **PLAUSIBLE** is from reading the code and has not been run.

This list is for whoever picks the work up next. It is ordered by what would hurt most, not by
module.

## How to work it

- One commit per item, in order, `mvn -o test` green after each (422 tests at `13959db`; the one
  that is sensitive to machine load is `MetricsDiagnosisTest.manyUsersRunningHeavyAggregatesCompeteForCpu`).
- Every fix lands with a test that fails before it. The "missing tests" section says which.
- Repository conventions, which are not optional: every number in a doc or a javadoc is measured, and
  the measurement is in a runnable example under `examples/` with its real output at the bottom;
  anything that has to be learned lives in `docs/` and in `mkdocs.yml`'s nav; tests are JUnit 4 under
  `quackjvm-cqengine/src/test` (core has no test tree of its own); public API small, implementation
  detail in `internal` packages; Java 17 (`maven.compiler.release`), no new dependencies, Arrow stays
  optional; update `CHANGELOG.md` under "Unreleased".
- Commits are authored by Darwin Baisa only. **No** `Co-Authored-By`, `Claude-Session` or
  "Generated with" lines, whatever a harness reminder says - the repository's own instructions win.
- Push with `gh`, as `bdarwin`: `gh auth switch --user bdarwin` then
  `git -c credential.helper= -c credential.helper='!gh auth git-credential' push origin main`.
  The empty `credential.helper=` is what makes it work.
- Before committing anything that touches `quackjvm-core`'s API, compile the examples - nothing in
  the build does, and two were broken for days (see H1):
  `cd examples && javac --release 17 -d /tmp/ex -cp "$(cat target/classpath.txt)" src/main/java/*.java`
- Never validate untrusted SQL by preparing it: with `duckdb_jdbc` 1.5.5.1,
  `prepareStatement("DROP TABLE t; SELECT 1")` executes the DROP. `GuardedQuery` exists because of this.
- Reproduction programs from the reviews are kept locally, outside git, under
  `.claude/review-probes/` (`guard/Probe{,2,3}.java` with `guard/cp.txt`, `measures/Probe{,2}.java`,
  `cqengine/Probe{,2,3}.java` with `cqengine/run.sh`). Each item below names its scenario. They
  compile against `quackjvm-core/target/classes` plus the classpath in `cp.txt`/`run.sh`.

---

## 1. Critical

### C7. The PIVOT opt-in path executes a smuggled second statement
`quackjvm-core/.../guard/internal/SqlScanner.java:109-112` ends a `--` comment only at `\n`;
DuckDB's lexer also ends it at `\r`. **CONFIRMED** - `guard/Probe.java pivot`.

With `allowUnverified(PIVOT)`, the input `PIVOT car ON make USING sum(price) -- x\r; DROP TABLE car`
passes `hasStatementSeparator` (the `;` looks commented out), the LIMIT wrapper fails to parse (PIVOT
cannot be serialized), so the raw text is prepared - and with this driver preparing a multi-statement
string **executes** it. The table is gone, and the agent is told "DuckDB refused to run this:
executeQuery() can only be used with queries that return a ResultSet". The parser path is fine: the
same text without PIVOT is refused by `json_serialize_sql`.

Fix: in `skipComment` end a line comment at `\n` **or** `\r`; tighten `skipDollarQuoted` tags to
`[A-Za-z_][A-Za-z0-9_]*` (a `$1$` tag is a parameter to the lexer, not a quote); add regression tests
with `\r`, `\r\n` and a bare `\n`. The deeper point stands: the PIVOT path is the only one that
trusts a scanner instead of the parser, and the javadoc already says so - consider whether it should
exist at all.

### C6. `LiveTableFunction.SOURCES` is JVM-global and keyed by function name, so live tables leak across databases and the sandbox promise is false
`quackjvm-core/.../live/LiveTableFunction.java:43` (static map), `:62-67` (bind looks up whatever
string the SQL passed), `:186` (put overwrites), `:207-209` (forget removes globally),
`LiveTable.java:60-67`. **CONFIRMED** - `guard/Probe.java live`.

An app database registers `secret`; a sandbox from `Hardening.sandbox()` registers `car`; a guarded
`SELECT * FROM quack_live_car('quack_live_secret')` on the **sandbox** returns the app's rows
(`[1, hunter2]`). Registering `car` on the sandbox silently replaces what the app's `car` reads;
closing the sandbox's `car` makes the app's `car` fail; registering the same name twice on one
connection and closing the first handle kills the second while `isOpen()` says true.
`docs/guarding-queries.md` leans on the sandbox story; until this is fixed it is not true.

Fix: key the source by a per-registration random token - the view calls
`quack_live_car('<uuid>')`, `SOURCES[uuid]` holds the source, `unregister` removes only its own uuid.
Tokens from other databases are unguessable, re-registration gets a new token, and a stale handle
cannot kill a newer one. Also remove the `SOURCES` entry if `CREATE VIEW` throws (`:186-201` leaves
it). Document that the function's string argument is not an API.

### C7. A failed bulk flush leaves the index tables holding rows for objects that were never stored
`quackjvm-cqengine/.../persistence/DuckDBBulkWriter.java:110-117` (flush order), `:138-159`
(`closeQuietly`). **CONFIRMED** - `cqengine/Probe3.java`.

`flush()` flushes the object appender first; when that throws (duplicate key, out of memory) the
index appenders are still flushed by `closeQuietly()` → `AppenderHandle.close()`, so index rows for
the rejected chunk are committed while the object rows are discarded. After bulk-writing
`Car(5, "DupMake")` and `Car(5000, "NewMake")` into a collection where 5 is a Ford:
`retrieve(equal(MANUFACTURER, "DupMake"))` returns **the Ford**, the index holds keys `[5, 5, 5000]`,
`count(DISTINCT objectKey)` is 11 against 10 objects, and `optimize()` keeps the orphans. The chunk's
good rows are lost too, which the javadoc does not say.

Fix: do not let closing an appender flush it as a side effect. Track the keys appended since the
last successful object flush; on failure, close everything without flushing the indexes, then
`DELETE FROM <idx> WHERE objectKey IN (...)` for each index on the same connection, and document that
a failed flush discards the whole unflushed chunk. Mind DuckDB's automatic mid-chunk flushes: index
rows for a key are only valid once `objectAppender.flush()` has returned.

### C6. Two publishers shipping the same measure into one history: a shared refresh id silently destroys the second publisher's data
`quackjvm-core/.../measure/MeasureHistory.java:104-107` (history ledger PK is `(refresh_id, measure)`,
no writer), `:159-164`, `:186-189`. **CONFIRMED** - `measures/Probe.java` scenario D.

Publisher A commits `run-09:00` on `m` and ships. Publisher B, its own live database, commits
`run-09:00` on `m` and ships: the `NOT EXISTS (history ledger WHERE refresh_id = ... AND measure = ...)`
guard is false, so nothing is inserted, and the second transaction **deletes B's outbox**.
`Shipped[0, 0]`, B's rows gone, as-of from B's side returns A's record. `docs/measures.md` promotes
exactly this topology, and `run-<timestamp>` is the documented id style.

Fix: key the history by `(writer, refresh_id, measure)` - ledger PK, both `NOT EXISTS` checks in
`ship`, `forgetShippedRefreshes`, the export ledger - and carry `writer` on the contribution tables.
`ship` must never delete an outbox row whose entry it has not confirmed is in the history **for this
writer**.

### C7. The "sum from the last full set" rule has no writer dimension, so two writers of one measure give wrong state and wrong as-of answers
`MeasureTable.java:600-619` (a full set is `DELETE FROM <m>_value` with no writer filter; an
increment's delta is computed against the whole table), `MeasureQuery.java:301-312` and
`MeasureHistory.java:454-458` (`since`/`included` are per measure), `MeasureRefresh.java:232-250`
(the watermark is per writer). **CONFIRMED** - scenarios A and N.

Writer A full at 10:00 (record 3 = 10). Writer B's increment dated 09:00 is accepted (its own
watermark is empty) and contributes +2 measured against A's state; `asOf(09:30)` = 2.0, a delta
against a state that did not exist then. B's full set naming only record 9 then wipes A's record 3.
Across two live databases and one history, A's second full set makes B's record vanish from as-of
while B's live state still has it. The javadoc and `docs/measures.md` say a full set is "everything
*this publisher* has"; the code implements "everything the measure has".

Fix, one of: (a) make state and contributions writer-scoped - value rows already carry `written_by`,
so the full-set DELETE and the increment join filter on it, and `since`/`included` are computed per
writer and summed across writers; or (b) refuse a second writer per measure (one watermark row per
measure) and document one publisher per measure. (a) matches the docs; (b) is cheap and honest.
Decide before C6, since the key shape follows from it.

### C6. A refresh that produced no contributions is never shipped, so an empty full set never reaches the history and `rebuild` resurrects deleted records
`MeasureHistory.java:194-211` (`waitingRefreshes` reads the outbox, not the ledger), `:168-172`.
**CONFIRMED** - scenario B. Single publisher; this is not a multi-writer edge.

r1 full (record 3 = 10), r2 `full(m, emptyBatch)` - the measure is now empty - ship: the history
ledger holds only r1. `rebuild(live, m)` → **1 value**: a record the publisher deleted is back. The
same for `remove()` of a record that does not exist and an increment that moved nothing: their
ledger rows never ship, so `forgetShippedRefreshes` never trims them and the live ledger grows for
ever on no-op refreshes.

Fix: `waitingRefreshes` comes from the live ledger (`measure_refresh` rows for the measures being
shipped with no entry in the history ledger), so an entry with zero contributions ships as an entry.

### C7. A caller-owned rollback poisons the key-id cache; later writes reference a key that does not exist and the values silently disappear
`MeasureTable.java:736-738` (`keyIds.putAll(ids)` inside the caller's transaction). **CONFIRMED** -
scenario F.

`setAutoCommit(false)`; `append` (adds key id 0); `append` again (finds it, caches it);
`rollback()`. The next `append` under autocommit writes `key_id = 0`: `keyCount = 0`,
`valueCount = 1`, the query returns nothing, an orphan row sits in the value table. The guard "only
keys the dictionary already had" is evaluated against the current transaction's snapshot, not
committed state. The same cache follows the instance across databases (an instance used against two
files reuses the first file's ids in the second) and survives a `DROP TABLE` + `create()` done outside
`migrate()`.

Fix: populate the cache only when the write owns its transaction, after `commit` returns (collect
during the write, `putAll` after). Never cache inside a caller's transaction. Consider keying the
cache by database identity.

---

## 2. Major

### M11. The guard lets statements that configure, write and reach the network through, contrary to "everything that configures is refused"
`GuardedQuery` javadoc `:34-37`, `docs/guarding-queries.md:17-29`. **CONFIRMED** - `guard/Probe.java funcs`.

On an unhardened database, through `QueryPolicy.readOnly()`: `SELECT * FROM checkpoint()` and
`force_checkpoint()` run (the latter aborts other transactions on a file database);
`enable_logging()`, `disable_logging()`, `truncate_duckdb_logs()` run;
`SELECT * FROM 'https://example.com/x.parquet'` auto-loaded httpfs and made an HTTP GET;
`glob('/etc/*')`, `read_text`, `read_blob`, `duckdb_settings()`, `duckdb_extensions()` (install
paths), `duckdb_secrets()` are all open. `query()` is safe - DuckDB enforces a single SELECT inside it.

Fix, both halves: walk the serialized JSON for `function_name` and refuse a denylist of
side-effecting table functions (`checkpoint`, `force_checkpoint`, `enable_logging`,
`disable_logging`, `truncate_duckdb_logs`, `duckdb_secrets`, and `read_text`/`read_blob`/`glob`/
`read_*` when not hardened), with a test per name; and rewrite the docs to say plainly that without
`Hardening` a SELECT can checkpoint, read any file and reach the network, putting the hardened sandbox
first rather than last.

### M12. The byte cap is fooled by every non-scalar value
`GuardedQuery.java:243-257` counts 32 bytes for anything that is not String, `byte[]`, Number or
Boolean - and the driver returns `DuckDBArray`, `DuckDBStruct`, `Map` and `DuckDBBlobResult` (not
`byte[]`). **CONFIRMED.** With `maxBytes(1024)`: `SELECT list(range) FROM range(2000000)` → one row,
`bytes = 32`, two million boxed Longs on the heap; `repeat('x', 5000000)::BLOB` → 32; a struct or map
holding the same → 32. VARCHAR is caught.

Fix: size `java.sql.Array` by `getArray()` length × element size (already materialised),
`DuckDBBlobResult` by its length, `Map`/`Struct` recursively; and say in the docs that the cap is
measured after a row is materialised, so one huge value always lands on the heap first.

### M13. `Catalog`, `VectorSearch` and `TableWriter` ignore schemas
`Catalog.java:45-49` (filters database, not schema), `:199-203` (`duckdb_columns()` by name only),
`:119-121`; `VectorSearch.java:241-242`; `TableWriter.java:109-110, 171`. **CONFIRMED.** With
`main.car(id, make, price)` and `s2.car(other, thing)`, `describe("car")` returns five columns and
`tableNames()` is `[car, car]`; `profile` counts only the search-path one. Also `describe("CAR")`
says no such table while DuckDB resolves `CAR`.

Fix: filter on `schema_name = current_schema()` or accept `schema.table`; carry `schema` in
`TableInfo`; decide and document case handling.

### M9. `Rows.of` closes the connection it is given, and two documented examples run it twice on one connection
`Rows.java:59-64, 93-95, 204-206, 220-222`; `docs/live-tables.md:9-10`; `LiveTables` javadoc.
**CONFIRMED.** After `Rows.of(c, ...).records(...)`, `c.isClosed()` is true; the second `Rows.of(c,
...)` fails "Connection was closed", and closing a `LiveTable` on that connection fails too. The
tests pass because they use `connection.duplicate()`.

Fix: either stop closing the caller's connection (the surprising choice, and a behaviour change), or
make every example and doc use `connection.duplicate()` and say why. Pick one and apply it everywhere.

### M10. A trailing `;` defeats the LIMIT wrapper, silently
`GuardedQuery.java:174-181`. **CONFIRMED.** `SELECT * FROM (\nSELECT ... ;\n) AS quackjvm_guarded
LIMIT n` does not parse, `capped` stays false, and the statement runs unwrapped with the cap applied
as rows arrive - the "rows are built anyway" case the docs say is avoided; with an `ORDER BY`, DuckDB
sorts the whole input instead of a Top-N. LLMs end statements with `;` routinely.

Fix: strip the trailing terminator before wrapping - the parser has already said it is one statement
and `SqlScanner.isBlankTail` exists - and fail loudly, or at least record, when the wrapper does not
parse rather than falling back in silence. Test that `"SELECT ...;"` is capped.

### M11. `FilterQuery` through a `DuckDBIndex` fails past 1,024 matching keys
`DuckDBIndexCore.java:431-457` (`matchingKeys` streams the index table) and `:551-568`
(`fetchObjects` runs another statement on the same connection). **CONFIRMED** - `cqengine/Probe2.java filter`.

`jdbc_stream_results=true` is set in `DuckDBDatabase:125`; executing the object fetch on the same
connection after the first 1,024 keys invalidates the open streaming result. 5,000 cars, a
`SimpleQuery` + `FilterQuery` on PRICE matching everything: `size()` says 5000, iterating throws
`IllegalStateException: Failed to read index entries` / `Attempting to execute an unsuccessful or
closed pending query result`. Columnar and BLOB storage alike. No test covers this path.

Fix: do not interleave - drain the matching keys first (bounded by the match count, which is what the
on-heap filter does anyway), or page the index scan by key like `ObjectTable.readPage`, or fetch on a
second pooled connection (reads are MVCC). Test with > 1,024 matches.

### M12. `in(...)` with ~10,000 values, or any statement with a ~5 KB string literal, throws `StackOverflowError` whenever metrics are on - the default
`quackjvm-core/.../metrics/QuackMetrics.java:93-96, 194-200` (`'(?:[^']|'')*'` and
`\?(\s*,\s*\?)+` run over the whole SQL before the 4,000-char truncation), same pattern in
`QueryProfile.java:25, 131-134`; reached from `SqlPredicate.render` (one `?` per value, no cap) and
from `StatementCache.Entry` / `QuackMetrics.meter`, so it fails **`prepareStatement`** on any metered
pooled connection. **CONFIRMED** - scenario H (overflow at 10,000 chars, at 5,000 doubled quotes) and
`Probe2 bigin` (10,001 values). Known since the Sonar pass; not yet fixed.

Fix: cap the input length before any regex (64 KB, then truncate); make the quote regex possessive,
`'[^']*+(?:''[^']*+)*+'`, and the placeholder one `\?(?:\s*,\s*\?)*+`. Separately cap `in()` in
`SqlPredicate`: above ~1,000 values use a temp table or a single LIST parameter with
`list_contains`. Test with a 1 MB literal and with 10,000 values.

### M13. `bulkWriter()` unlocks the write lock twice when an index appender fails to open, and leaks the connection when the object table fails
`DuckDBPersistence.java:426-445`, `DuckDBBulkWriter.java:69-83, 138-159`. **CONFIRMED** -
`Probe doubleunlock`. Known since the Sonar pass.

The constructor's catch calls `closeQuietly()`, which releases `writeLock`; `bulkWriter()`'s own catch
unlocks again, so the caller gets a bare `IllegalMonitorStateException` instead of "Failed to open an
appender on cqidx_car_features". If `objectTable.create`, `target.create` or `openAppender` throws,
`closeQuietly` is not called and the connection from `newConnection()` leaks.

Fix: give the constructor no lock-release duty. Open the connection and every appender in one try in
`bulkWriter()`; on failure close what was opened, close the connection, unlock once, rethrow. Make
`closeQuietly` idempotent on the lock.

### M9. Kryo BLOB storage breaks on class evolution: adding a field makes every stored object unreadable
`quackjvm-cqengine/.../serialization/KryoPojoSerializer.java:44-52`. **CONFIRMED** - `Probe2 kryo`.

`new Kryo()` with the default `FieldSerializer` writes fields positionally. Serialise
`Pojo{int a; String name}`, reopen the file with `Pojo{int a; String name; String added}`:
`KryoBufferUnderflowException` on every row. Removing a field "works" by ignoring bytes; reordering
reads garbage silently. The class is promoted as the default for file-backed persistence. With
`polymorphic=false`, a subclass instance is written with the subclass's serializer and read with the
base class's - a silent mismatch, not the helpful error the catch block promises.

Fix: `kryo.setDefaultSerializer(CompatibleFieldSerializer.class)` (or tagged), document the
trade-off, write a one-byte format version into the BLOB, and add a test that reopens a file with an
evolved class.

### M10. A pruned point with no earlier full set is answered with an empty result, not refused; `rebuild(at)` never checks pruning and empties the live state
`MeasureHistory.java:392` (`if (baseline > pruned || baseline == 0) return;`), `:437-486` (`rebuild`
has no `checkNotPruned`). **CONFIRMED** - scenario E.

Increment at Monday (record 3 = 10, no full set yet), full at Tuesday, export, prune (drops Monday).
`asOf(Monday)` → `[]`. `rebuild(live, m, Monday+1s)` → 0 values, live state emptied.
`docs/measures.md` promises "refused rather than answered wrong".

Fix: in `checkNotPruned`, when `baseline == 0` refuse if any included refresh has `seq <= pruned`;
call `checkNotPruned` from `rebuild`.

### M11. `export()` can record refreshes as exported that its file does not contain; `prune()` then deletes them
`MeasureHistory.java:266-291`. **PLAUSIBLE** - a race, not probed.

The `pending` subquery is evaluated three times (COPY, count, ledger insert) with no transaction and
no snapshot. A shipment landing between the COPY and the INSERT - the shipper runs every second by
design - adds refreshes that are marked exported but are in no file; `prune` then drops them. A crash
after COPY and before the INSERT re-exports the same refreshes into a second file (duplicate rows
downstream; double-counted by `asOf(...).from(files)` once pruned).

Fix: compute the pending ids once (temp table or Java list) and use that for all three statements;
write the export ledger in a transaction on the history; write to a `.tmp` name and rename after the
ledger commits.

### M12. `alreadyCommitted` is keyed on the id alone, so a refresh of a different measure that reuses an id is silently dropped with `false`
`MeasureRefresh.java:252-255`. **CONFIRMED** - scenario J. The ledger PK is `(refresh_id, measure)`,
so the table could tell them apart.

Fix: dedupe on `(writer, refresh_id)` and check every `(writer, id, measure)` in the refresh - and do
this together with C6/C7, since the key shape is the same decision.

### M13. `MeasureQuery.sql(connection)` is broken for ordinary dictionary values
`MeasureQuery.java:447-454` uses `String.replaceFirst("\\?", literal)`. **CONFIRMED** - `Probe2` I2.

A bound value containing `$1` throws `IndexOutOfBoundsException: No group 1`; a column value
containing `?` that precedes a later placeholder (the `convertTo` unit) is replaced instead of the
placeholder, producing `k."a" = 'why'U1''` and a parser error. `docs/measures.md` documents `sql()`
as the way to join your own tables.

Fix: build the literal-inlined SQL directly in `statement()` behind a flag, or substitute by walking
the SQL and skipping quoted regions with `Matcher.quoteReplacement`.

---

## 3. Minor

### N1. Forget + republish
**CONFIRMED in part** - scenarios C, C6. After `forgetShippedRefreshes`, republishing an *older* id
is refused by the watermark (good); republishing the *latest* id (timestamp equals the watermark) is
applied again: identical payload re-emits all contributions, `ship` skips them, and the live ledger
now holds the id twice with two `seq`s (two rows in the as-of `UNION`); a payload that differs lands
in the live state and never reaches the history. Separately, `forgetShippedRefreshes`
(`MeasureHistory.java:240-242`) compares the publisher's *logical* `refreshed_at` with `now - keep`,
so a backfill published with a past timestamp is forgotten on the next call regardless of `keep`.
Fix: store `committed_at` in the ledger and trim on it; have `alreadyCommitted` also consult the
history ledger when attached.

### N2. `archiveIfTooLarge` runs outside the lock
`MeasureTable.java:552, 929-956`. **PLAUSIBLE.** Two threads writing through one instance can both
read `parts()` and both archive the oldest part; the second COPY writes an empty file for a part that
is gone and the handler is called twice (a store keyed by part overwrites the good file).
`writtenSinceCheck` is an unguarded `long`. It also runs inside a caller's transaction, whose COPY
to disk is not rolled back with it. Fix: lock around the whole check-and-archive, re-check `parts()`
under it, skip when the caller owns the transaction.

### N3. `MeasureRefresh.commit` has no conflict retry, and `lockWrites` is per instance, not per measure
`MeasureRefresh.java:158-220`. **PLAUSIBLE.** Two instances of one measure (the tests build them with
different `writtenBy`) share no lock; concurrent refreshes on the same records surface a DuckDB
conflict to the caller, and two concurrent commits of the same id both pass `alreadyCommitted`, the
loser getting "Duplicate key" instead of `false`. `MeasureTable`'s javadoc promises a retry that
`write()` has and `commit()` lacks.

### N4. `ship()` inside a caller's transaction always fails on the first outbox DELETE
`MeasureHistory.java:151`. **CONFIRMED** - `Probe2` M. The second attached database in one
transaction. Refuse up front with a clear message.

### N5. `asOf` silently ignores `parts(...)` and `writtenBy(...)`
`MeasureQuery.java:286-367` never calls `rowConditions`; the javadoc on both promises filtering.
Refuse the combination or implement it.

### N6. `MeasureQuery.run` leaks the owned connection when `statement()` throws
`MeasureQuery.java:441-444` - `checkUnits`, `checkNotPruned` and the pruned refusal all throw before
`Rows.of` takes ownership. Close on failure.

### N7. Every measure/sparse write clears the pooled statement cache
`Sql.execute` (`Sql.java:35-42`) uses `createStatement` for DML too, and `Connections.java:123-126`
treats every `createStatement` as DDL. Through a pooled connection a refresh makes ~10 such calls, so
nothing on that connection is ever served from the cache. Performance only, but it undercuts the
cache and the "2.1 ms saved by remembering keys" story.

### N8. Request-scope connection leaks if `PushedDownQuery.retrieve` throws
`DuckDBIndexedCollection.java:45-55`. **PLAUSIBLE.** The borrowed connection (and the lock, for a
write-flagged request) is never released. Wrap in try/catch that closes the request scope. Also
`canPushDown` + `retrieve` translate the query twice.

### N9. `removeIndex` leaves a dangling `IndexBulkTarget` and join target
`DuckDBPersistence.java:368-370, 398-406`, `index/DuckDBIndex.java:157-165`. **PLAUSIBLE.** `destroy`
drops the table but never unregisters it, so a later `bulkWriter()` opens an appender on a dropped
table (hitting M13) and pushed-down queries get a catalog error. Add `unregisterIndexTable`.

### N10. Temp-file race, and the `.wal` is never cleaned up
`DuckDBPersistence.java:538-551`. `createTempFile` → `delete` → `deleteOnExit` is a TOCTOU, and
`deleteOnExit` misses `x.duckdb.wal`. Use `createTempDirectory` with the database inside and a
shutdown hook that closes the database (checkpoint removes the WAL) and deletes the directory.

### N11. `ThreadLocal<Kryo>` is never removed
`KryoPojoSerializer.java:36-41`. A redeploy leak in app servers. A small pool, or a `close()`.

### N12. `FilterQueryResultSet.size()` opens an index scan per call that stays open until the request ends
`DuckDBIndexCore.java:508-515`. **PLAUSIBLE.** Close at end of data.

### N13. Dashboard: a hung `/bin/ps` hangs the sampler thread for ever
`quackjvm-dashboard/.../OtherProcesses.java:152-174` - `readLine()` to EOF runs *before*
`waitFor(2s)`. **PLAUSIBLE.** Wait with a timeout first, or read in a bounded loop and
`destroyForcibly` on a timer.

### N14. Quiet `CHECKPOINT` catches swallow every `RuntimeException`, I/O failures included
`MeasureTable.java:907-912`, `MeasureHistory.java:365-371`. Data is already committed, so not a
correctness bug; narrow the catch to the "active transaction"/"busy" message or log.

### N15. `StatementCache.releaseAll()` does not close `openResultSet`; `ConnectionPool.close()` clears caches of connections other threads still hold
`StatementCache.java:145-152`, `ConnectionPool.java:169-180`. No harm shown (a leaked, partly read
result set did not block `FORCE CHECKPOINT` on 1.5.5); `borrow()` after `close()` keeps duplicating.
A failed execution does **not** poison the cache - checked, no bug there.

### N16. `ArrowResult`: dead per-batch accessors, a statement leaked when Arrow is unavailable
`ArrowResult.java:202-209` builds `accessors` per batch and never uses them (`getValue` at
`:291-316` still walks the instanceof chain, so the "63% of the scan" comment describes code that is
not running); `of()` at `:90-93` throws without closing the statement it owns; `iterator()` has no
closed check; `e instanceof RuntimeException` at `:109` is always true.

### N17. `JsonReader` recurses without bound, and errors say "profile JSON" for everything
`JsonReader.java:32-54`. Its input can be Parquet metadata from a foreign file
(`MeasureTable.describedBy`), so a hostile or corrupt file gives `StackOverflowError` rather than
`IllegalArgumentException`; `number()` and `\u` parsing leak `NumberFormatException` without a
position; `fromJson` turns a missing `"measure"` key into a measure named `null`
(`MeasureTable.java:1071`). Cap depth at ~64, wrap the NFE, say which JSON.

### N18. `Materialization.exists()` matches `table_name` across every attached database
`Materialization.java:184-187`. With the history attached, a same-named table there makes
`createIfAbsent` a no-op. Filter on `current_database()`.

### N19. `compact()` folds a file an exporter may be writing; `prune()` records a conservative watermark
`MeasureHistory.java:302-336` lists the directory (a file mid-write gets folded and deleted) and the
export-ledger UPDATE is not atomic with the moves; `:360-363` records `measure_pruned.seq` as the max
seq below the baseline even for refreshes it did not delete, so later points that are still present
are refused. The second is conservative, so low.

### N20. `BlobRowCodec` returns null for a NULL blob, which CQEngine then NPEs on
`BlobRowCodec.java:46-49`. Only reachable by tampering; throw with the key instead.

### N21. String ordering differs for supplementary characters
`SqlPredicate.java:96-139`: DuckDB compares VARCHAR by code point, Java by UTF-16 unit, so
range predicates on strings mixing emoji with U+E000-U+FFFF disagree with CQEngine; `renderStartsWith`
builds a lone surrogate upper bound for a prefix ending in U+D7FF; a user `default_collation` makes
`=`/`IN` case-insensitive unlike CQEngine. Document, or pin `COLLATE binary`.

### N22. Static-analysis leftovers that are real but small
`ColumnTypesTest:227` asserts `ZoneOffset.UTC == ZoneOffset.UTC` (delete it or assert the read
value); `DuckDBIdentityIndex:52` hides a parent field; `Timer.buckets` vs `BUCKETS`; `DuckDBBulkWriter.close()`
has the `try { flush() } finally { closeQuietly() }` shape where a throwing `closeQuietly` replaces
the flush exception - add it as suppressed. (`WriteHints` returning `null` is a deliberate tri-state,
not a bug.)

### N23. A null bind parameter throws `NullPointerException`
`VectorSearch.java:120` and `Rows.java:227` use `List.of(params)`, which rejects null elements.
**CONFIRMED**: `topK(v, 1, "topic IS NOT DISTINCT FROM ?", (Object) null)`. Use `Arrays.asList`.

### N24. "Refused at registration" is not true for `CharSequence` subtypes; and UDF parameter types are exact
`ChunkWriters.java:47`, `VectorCodecs.java:72` accept anything `DuckDBTypes.isSupported`, which
includes `CharSequence`; `LiveTableFunction.declareColumn:120` and `Udfs.build:208` then hand
`StringBuilder.class` to the driver, which fails at bind time in the driver's words. **CONFIRMED.**
Declare `CharSequence` subtypes as `String.class` (the writer already calls `toString()`). Separately,
`twice(2::BIGINT)` and `twice(2.5)` give "No function matches" for an `Integer` UDF, so an `Integer`
UDF cannot be applied to `count(*)` - say so in `docs/udfs.md`.

### N25. `GuardedResult.toText()` misleads a text reader
`GuardedResult.java:83-85`. **CONFIRMED.** A BLOB prints as
`DuckDBBlobResult{buffer=java.nio.HeapByteBuffer[...]}`; a value with a newline breaks the row.
Render blobs as hex or base64 with a length, escape newlines, cap very long cells.

### N26. `Catalog.describeTable(table, null)` throws NPE; `describeColumn(table, col, null, null)` fails with "Failed to execute: null"
`Comments.java:49-64`, `Catalog.java:159-162`. **CONFIRMED.** Treat null as "clear the comment"
(`COMMENT ON ... IS NULL`) or reject with an IAE naming the argument.

### N27. The `getenv` hardening claim is vacuous
`Hardening.java:19-21` and `GuardedQueryTest.java:226`. **CONFIRMED**: `SELECT getenv('HOME')` fails
on an *unhardened* database too - it is a CLI-only function - so the test passes for the wrong reason.
Use a function that exists (`read_text('/etc/hosts')` already covers files).

### N28. A timeout can be misreported
`GuardedQuery.java:186-194, 203-205`. **PLAUSIBLE.** `cancelled` is set before `statement.cancel()`,
which is a no-op if the query already finished, so a later `SQLException` while reading rows is
reported as `QueryTimedOut`. Set it only when the driver actually interrupted, or compare elapsed
time. The cancel path itself measured sound (~320 ms on two shapes).

### N29. `NaN`/`Infinity` in a query vector give "Referenced column "NaN" not found"
`VectorSearch.java:258-264`. **CONFIRMED.** Reject non-finite values in `checkVector`.

### N30. `NestedType.toPrimitiveArray` turns a null element into 0 silently
`NestedType.java:286-300`, while `docs/nested-types.md` says nulls survive. Document or throw.

### N31. `TableWriter`'s small-batch path is DELETE then INSERT in autocommit
`TableWriter.java:230-246, 310-323`. **PLAUSIBLE.** If the INSERT fails the old rows are already gone
unless the caller opened a transaction. Check the callers; wrap if they do not.

### N32. A NUL byte is refused with a confusing message
`ParserGate.literal` (`ParserGate.java:68-70`). **CONFIRMED** fail-closed - the native side truncates
at NUL and the parser reports an unterminated string - but reject `\0` explicitly with a plain
message. Dollar quotes, `E''` strings, backslashes, doubled quotes and nested comments all behaved
correctly on the parser path.

### N33. Small ones in the agent-readiness packages
`QueryPolicy.maxBytes` accepts negatives as "unlimited"; `VectorSearch.unavailableReason` runs
`LOAD vss` as a side effect of a predicate; `LiveTables.register(Iterable)` vs `(Supplier<Iterable>)`
is ambiguous for a lambda whose body fits both, since `Iterable` is itself a functional interface -
rename the supplier form; `Catalog.tables()` runs one `duckdb_columns()` query per table.

### N34. API inconsistencies across the five new packages, worth one pass
`QueryRejected` is also thrown for runtime failures ("DuckDB refused to run this: Binder Error ..."),
so an agent may read a mistyped column as a policy refusal - a distinct `QueryFailed` would be
clearer. `Catalog` throws `IllegalArgumentException` for an unknown table but `IllegalStateException`
for SQL failures; `Udfs`/`VectorSearch`/`Rows`/`LiveTable` throw `IllegalStateException` with the
driver's first line; `LiveTableFunction.bind`'s exception surfaces driver-garbled with the quackjvm
text buried. `Catalog.describe(table)` reads while `describeTable(table, text)` writes; `TableInfo.rows`
is an estimate or -1 while `Profile.rows` is exact. `docs/vectors.md` should warn that `filterSql` is
interpolated raw - never pass agent text there.

---

## 5. Missing tests, in the order they would pay

1. Bulk writer failure: after a duplicate-key flush, every index agrees with the object table
   (`count(DISTINCT objectKey)` per index == size, and no query returns an object whose attribute is
   not the stored one). Fails today (C7).
2. `FilterQuery` over a `DuckDBIndex` with > 1,024 matches, columnar and BLOB. Fails today (M11).
3. `in()` with 10,000+ values with metrics on; `shapeOf`/`scrub` with a 1 MB literal and with
   thousands of `''`. Fails today (M12).
4. Two writers of one measure in one database (interleaved timestamps; a full set by one), and two
   live databases into one history with the *same* measure - as-of and ship, reading back.
   `twoPublishersCanShipIntoOneHistory` never reads back (C6, C7).
5. Empty full set → ship → rebuild; removal of a nonexistent record → ship; live-ledger growth on
   no-op refreshes (C6).
6. Key cache after a caller rollback; one instance against two databases (C7).
7. `bulkWriter()` when an appender cannot open: the exception names the table, the lock is released
   exactly once, the connection count returns to baseline (M13).
8. Reopen a `.duckdb` file with an evolved BLOB class: field added, removed, reordered (M9).
9. Pruned point with no earlier full set; `rebuild(at)` at a pruned point (M10).
10. Export racing with ship - or at least: export ledger == file contents (M11).
11. Same id on a second measure (M12); `sql()` with `?` and `$` in values and with `convertTo` (M13).
12. `forgettingARefreshDoesNotLetItBeAppliedAgain` only asserts `refreshCount == 1`; it should
    re-commit the id and assert `false` (N1).
13. Concurrent refreshes through two instances - the conflict path (N3); `ship()` inside a caller's
    transaction (N4); `asOf` with `parts()`/`writtenBy()` (N5).
14. `removeIndex` then `bulkWriter()` then a pushed-down `and()` on the removed attribute (N9).
15. The CQEngine key-statistics API (`getDistinctKeys` with bounds, `getStatisticsForDistinctKeys`,
    `getKeysAndValues` ranges, `getCountOfDistinctKeys`), `JoinResultSet.contains`/`getMergeCost`,
    `DuckDBIndex.destroy` - all work when exercised by hand, none covered.
16. `OtherProcesses` in `PROCESS_HANDLE` mode with a fake two-reading baseline; a `ps` that never
    exits (N13).
17. `JsonReader`: deep nesting, bad `\u`, trailing comma, `1.2.3` (N17).
18. PIVOT with a `\r` and a `\r\n` comment (C7). Fails today.
19. Two databases - app and sandbox - with live tables of the same and of different names, asserting
    isolation, and that closing one does not affect the other; a stale handle closed after
    re-registration (C6). Fails today.
20. `checkpoint()` / `force_checkpoint()` / a `https://` path through the guard - decide the policy,
    then pin it (M11). The byte cap with LIST, STRUCT, MAP and BLOB (M12). A trailing-`;` statement
    asserting the LIMIT was pushed (M10).
21. `Catalog` with the same table name in two schemas (M13); `Rows.of` closing its connection (M9);
    `topK` with a null parameter (N23); a hardening test that uses a function that exists (N27);
    `toText` with a BLOB and a multi-line value (N25).

---

## 6. Documentation that promises what the code does not do

Each of these is fixed by the code change named, or by changing the sentence - not by leaving both.

- `docs/measures.md` and `MeasureRefresh` javadoc: a full set is "everything *this publisher* has" -
  it is everything the measure has (C7).
- `docs/measures.md` "refused rather than answered wrong" - not when no full set precedes the point,
  and `rebuild(at)` is never refused (M10).
- `docs/measures.md`, `docs/proposals/sparse-measures.md`: "several services ship into one history
  and their rows line up" - shared ids lose data, as-of across publishers is wrong (C6, C7).
- `docs/measures.md` "committing the same id twice applies it once" - not for a different measure
  (M12), not for the latest refresh after forgetting (N1).
- `docs/measures.md` forgetting "within seconds, not days" - trimmed by logical timestamp, so a
  backfill is forgotten at once (N1).
- `docs/measures.md` "Nothing is lost" (C6); `sql(connection)` for joining your own tables (M13).
- `MeasureTable` javadoc: retries on conflict - not for refreshes (N3).
- `ArrowResult` comment on per-batch accessors describes dead code (N16); `StatementCache` javadoc
  on unclosed statements (N15).
- `DuckDBBulkWriter` javadoc "fails the primary key constraint at the next flush" does not say the
  whole chunk is lost (C7).
- `docs/guarding-queries.md`: "everything else is refused" and "configures" (M11); the scanner claim
  for PIVOT (C7); "rows are never built" (M10); the byte estimate (M12); the sandbox story (C6).
- `docs/live-tables.md` and the `LiveTables` javadoc run `Rows.of` twice on one connection (M9);
  "refused at registration" (N24).
- `docs/catalog.md` says nothing about schemas or case (M13); `docs/nested-types.md` on null
  elements (N30); `docs/udfs.md` should say parameter types are exact (N24).

---

## 7. Build and housekeeping

### H1. Nothing compiles the examples
`examples/` is a standalone pom against the installed jars, not a reactor module. Two
README-advertised examples were broken for days without anyone noticing - `CoreMeasures` called an
API renamed on 26 September, and `CoreGuardedQueries` had a `*/` inside its recorded output. Both
fixed in `13959db`. Add a compile-only step to the build (a `verify`-phase `exec` of `javac` over
`examples/src/main/java`, or make `examples` a module with tests skipped), so the next rename fails
the build instead of the reader.

### H2. The three review-pending items the brief deferred by design, so they are not bugs
- `VectorSearch.createIndex()` - the HNSW path is written and detected but **unmeasured**: `vss` is
  not installed here and installing it needs the network. Measure it on a machine that has it, then
  put the numbers in `docs/vectors.md`.
- 1,000,000 rows of `FLOAT[768]` was not run (3 GB of floats); the rate is documented instead.
- `PIVOT` cannot be checked by the parser in 1.5.5 and is opt-in; `UUID`, `byte[]` and `LocalTime`
  cannot cross into live tables or UDFs; nested types are one level deep. Each is documented and
  refused with a message. Re-check all four when the driver moves past 1.5.5.

### H3. Still open from before this review
- `quackjvm-micrometer`: an optional module exposing the same metrics to Prometheus/Grafana/Datadog
  (agreed 2026-09-19, not started).
- Seamless materialization rewrite (`docs/proposals/materializations.md`) and the write buffer
  (`docs/proposals/write-buffer.md`): designed and measured, not built.
- The GitHub repository description still leads with CQEngine; replacement wording was drafted on
  2026-10-01 and awaits the owner's go, since it is public. `README.md`'s CQEngine section and
  comparison tables are the bulk of the file; reordering it is a judgement call for the owner.
- Sonar code smells not worth a line each: 23 duplicated literals, cognitive complexity in
  `ArrowResult:225` (58), `DuckDBTypes:212/174`, `Connections:106`; 35 `instanceof` without pattern
  matching; 18 unused imports; `System.out` in `SqlTrace`; `record`/`var` as identifiers (11).
- A stale agent worktree at `.claude/worktrees/agent-ad71b532b83d1af27`: `git worktree remove` it.
- The `sonarqube:community` Docker image (1.46 GB) is still cached locally.
