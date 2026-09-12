# API overview

Only the types you are expected to use directly are listed. Anything in an `internal` package is
implementation detail and may change.

## quackjvm-core

### `io.quackjvm.core.layout.ColumnarLayout<O>`

How to shred an object into one typed DuckDB column per field, and how to rebuild it from a row.

| method | notes |
|---|---|
| `static <R> ColumnarLayout<R> ofRecord(Class<R>)` | Columns from the record's components; objects rebuilt through the canonical constructor. |
| `static <O> ColumnarLayout<O> reflective(Class<O>)` | Columns from every non-static, non-transient instance field. Objects are allocated without a constructor and the fields written reflectively. Records are delegated to `ofRecord`. |
| `static <O> Builder<O> builder(Class<O>)` | Explicit columns: `.column(name, type, accessor)` repeatedly, then `.rowFactory(values -> ...)` and `.build()`. A row factory is mandatory. |
| `List<Column<O, ?>> getColumns()` | Each `Column` has `getName()`, `getType()` and `getValue(object)`. |
| `O createObject(Object[] values)` | Rebuilds an object from column values in column order. |

Every column type must be one DuckDB understands; an unsupported type is rejected when the layout
is built, not at the first insert.

### `io.quackjvm.core.duckdb.DuckDBTypes`

The Java-to-DuckDB type mapping, and the conversions either way.

- `sqlTypeFor(Class)` / `isSupported(Class)` - the column type for a Java type.
- `toSqlValue(Object)`, `bind(PreparedStatement, index, value)`, `append(DuckDBAppender, value, javaType)`.
- `readerFor(Class)` / `read(ResultSet, index, Class)`.

Supported: the primitive wrappers, `String`, `Character`, `BigInteger`, `BigDecimal` (scale 10),
`UUID`, `byte[]`, enums, `java.util.Date`, the `java.sql` date and time types, and
`LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`.

Two conversions worth knowing: **enums are stored as `ordinal()`** in an INTEGER column, which
preserves Java's natural ordering for them but makes stored data sensitive to the declaration order
of the constants; and all date and time values are normalised to `java.time` types so that a value
written through a prepared statement and the same value written through the Appender are identical
in the database.

### `io.quackjvm.core.duckdb.ColumnDef`

One column: `new ColumnDef(name, javaType)`, then `getName()`, `getJavaType()`, `getSqlType()`,
`toDdl()`. The SQL type is derived from the Java type at construction, so an unsupported type
fails here.

### `io.quackjvm.core.duckdb.TableWriter`

Writes rows into a table, choosing its strategy by batch size: batches at or below
`DEFAULT_APPENDER_THRESHOLD` (1024) go through a JDBC batch of prepared inserts; larger ones are
streamed through a per-connection temporary staging table using DuckDB's Appender, in chunks of
`DEFAULT_STAGING_CHUNK_ROWS` (131,072) so that memory stays bounded.

```java
new TableWriter(tableName, columns, orReplace, appenderThreshold [, stagingChunkRows])
```

The table's first column is treated as the key column.

| method | notes |
|---|---|
| `createTable(Connection, boolean primaryKeyOnKeyColumn)` | `CREATE TABLE IF NOT EXISTS`. |
| `WriteResult write(Connection, Iterator<Object[]> rows, boolean deleteExistingKeys)` | Rows are column values in column order. `WriteResult` has public `rowsWritten` and `rowsReplaced`. |
| `AppenderHandle openAppender(Connection)` | A long-lived appender for streaming inserts: `appendRow(Object[])`, `flush()`, `close()`. Constraint violations surface at flush time. |
| `count`, `clear`, `dropTable`, `tableExists`, `deleteKeys(Connection, Iterable<?>)` | |
| `validateSchema(Connection)` | Checks an existing table's columns against this writer's, with a message naming both. |

**Gotcha.** `orReplace` and `primaryKeyOnKeyColumn` have to agree: `INSERT OR REPLACE` needs a
unique or primary key to conflict on, so `orReplace = true` over a table created without one fails
at the first write with a binder error from DuckDB.

### `io.quackjvm.core.sql.SqlQuery` / `SqlRow` / `JoinPair`

```java
static Stream<SqlRow> SqlQuery.stream(Connection connection, String sql, Object... parameters)
```

**The stream takes ownership of the connection** and closes it, the statement and the result set
when the stream is closed. Give it a connection you are willing to lose - for a DuckDB in-memory
database, `((DuckDBConnection) root).duplicate()` yields another connection to the same database.

`SqlRow` addresses columns by name or by 1-based position: `get`, `getString`, `getLong`,
`getDouble`, `getColumnNames`, `toArray`. **One `SqlRow` is reused for every row of a result**, as
a view over the cursor, so a row kept beyond the iteration must be copied with `toArray()`.

`JoinPair<L, R>` is a record of `left()` and `right()`.

### `io.quackjvm.core.sql.Rows`

Typed results without writing a `ResultSet` loop. `Rows.of(connection, sql, parameters...)` builds
one; nothing runs until you ask for a shape, and the shape decides how the result is read.

| method | notes |
|---|---|
| `<T> T scalar(Class<T>)` | One value from one row. Throws if the query returns no row. |
| `<T> Optional<T> scalarOptional(Class<T>)` | The same, when no row is a legitimate answer. |
| `<T> List<T> list(Class<T>)` | The first column of every row. |
| `<T> void forEachValue(Class<T>, Consumer<T>)` | The streaming form of `list`. |
| `<T> List<T> records(Class<T>)` | A record per row; components are matched to the selected columns **by position**, not by name. |
| `<T> void forEachRecord(Class<T>, Consumer<T>)` | The streaming form of `records`. |
| `long count()` | Wraps the query in a `count(*)` rather than reading and discarding rows. |
| `void forEachRow(Consumer<SqlRow>)` | Raw rows, for a dynamic shape such as a `PIVOT`. |
| `Stream<SqlRow> stream()` | **Holds a connection; must be closed.** |

Every method except `stream()` closes the connection, statement and result set itself.

`scalar` and `scalarOptional` deliberately read through JDBC: setting up a columnar export costs
more than reading one value. `list`, `records` and the `forEach` forms use Arrow when it is
available. This is decided per call, not configured.

`Rows` takes ownership of the connection it is given, as `SqlQuery` does — pass a `duplicate()`.
`DuckDBDatabase.query(...)` handles that for you and is the usual way in.

### `io.quackjvm.core.arrow.ArrowSupport`

Whether the Arrow read path is usable, and why not if it is not.

- `boolean isAvailable()` — Arrow classes on the classpath and the JVM flag set.
- `String getUnavailableReason()` — which of those is missing.
- `String getRequiredJvmFlag()` — `--add-opens=java.base/java.nio=ALL-UNNAMED`.

Arrow is optional: with `org.apache.arrow:arrow-vector`, `arrow-c-data` and `arrow-memory-unsafe`
present, reads go through columnar batches (about 10x faster on wide results); without them
everything falls back to JDBC rows automatically.

`ArrowResult.of(PreparedStatement, batchSize)` and `ArrowObjectReader<O>` are the lower-level entry
points, for reading batches or rebuilding objects from them directly.

### `io.quackjvm.core.duckdb.ConnectionPool` and `Connections`

`ConnectionPool(DuckDBConnection root, int maxIdle)` hands out connections duplicated from one root
connection: `borrow()`, `release(Connection)`, `close()`. `Connections.duckDB(Connection)` unwraps
a `DuckDBConnection` from a pooled or proxied connection; `Connections.managed(...)` wraps one so
that `close()` returns it to a pool.

## quackjvm-cqengine

### `io.quackjvm.cqengine.persistence.DuckDBPersistence<O, A>`

A CQEngine `Persistence`, and the entry point for a single collection.

Factory methods, all taking the primary key `SimpleAttribute` first:

| method | storage |
|---|---|
| `onPrimaryKey(pk)` / `onPrimaryKeyInMemory(pk)` | in memory (native memory, not the Java heap) |
| `onPrimaryKeyInFile(pk, File)` | a DuckDB file |
| `onPrimaryKeyInTempFile(pk)` | a temporary file, reported by `getFile()` |
| `onPrimaryKeyInFileWithProperties(pk, File, Properties)` | a file, with extra DuckDB settings |
| `builder(pk)` | everything below |

Builder options: `database(DuckDBDatabase)`, `collectionName(String)`, `file(File)`, `tempFile()`,
`inMemory()`, `columnarLayout(ColumnarLayout<O>)`, `objectCacheSize(int)`,
`appenderThreshold(int)`, `stagingChunkRows(int)`, `memoryLimit(String)`,
`serializeWrites(boolean)`, `maxPooledConnections(int)`, `property(name, value)`,
`properties(Properties)`, `build()`.

Without a `columnarLayout` each object is serialized into a single BLOB, which needs no mapping
code but hides the object's fields from SQL and from joins. With one, each field is a real typed
column, which compresses far better and is what `sql(...)`, `join(...)` and pushed-down `existsIn`
need.

Other methods worth knowing:

| method | notes |
|---|---|
| `DuckDBBulkWriter<O> bulkWriter()` | see below |
| `long getBytesUsed()` | the size of the whole database - shared, if several collections share it |
| `File getFile()` | null for an in-memory database |
| `void optimize()` | rewrites each index table ordered by value so DuckDB can skip blocks. Worth calling after a bulk load; it is not cheap |
| `void analyze()` | DuckDB's `ANALYZE` |
| `void compact()` | checkpoints and reclaims space |
| `void expand(long)` | a no-op, present only so code written against CQEngine's SQLite persistence still compiles |
| `void close()` | closes the database if this persistence created it |

**Concurrency.** One DuckDB database is opened and each CQEngine request borrows a connection from
a pool. Reads run concurrently under DuckDB's MVCC. Writes are serialised against each other by
default, because two DuckDB transactions modifying one table concurrently make one of them fail;
`serializeWrites(false)` turns that off for applications which coordinate their own writes.

### `io.quackjvm.cqengine.persistence.DuckDBIndexedCollection<O, A>`

`new DuckDBIndexedCollection<>(persistence)` - an ordinary `ConcurrentIndexedCollection` except
that it translates a whole query, including `existsIn` nested inside it, into one SQL statement
rather than letting CQEngine intersect the branches in Java. Anything it cannot translate is handed
back to CQEngine unchanged, so results never depend on the push-down.

### `io.quackjvm.cqengine.persistence.DuckDBDatabase`

One DuckDB database shared by any number of collections, which is what makes joins possible.

- `inMemory()`, `inFile(File)`, or `builder()` with `file`, `memoryLimit`, `property`,
  `properties`, `serializeWrites`, `maxPooledConnections`.
- `collection(pkAttribute)` returns a `CollectionBuilder` with `name(String)`,
  `columnarLayout(...)`, `objectCacheSize(int)`, `appenderThreshold(int)`, `stagingChunkRows(int)`,
  and then either `build()` (a registered `DuckDBIndexedCollection`) or `buildPersistence()` (the
  persistence alone, for your own `IndexedCollection`). Give two collections of the same type
  different names; the default name is the object type's simple name in lower case.
- `<L, R> Join<L, R> join(left, right)` - see below.
- `Rows query(String, Object...)` - arbitrary SQL over the collections, returned as typed values.
  The usual way in; see `Rows` above and [Aggregates](aggregates.md).
- `Stream<SqlRow> sql(String, Object...)` - the same SQL as a raw row stream. The stream holds a
  connection and must be closed.
- `String table(collection)`, `String column(collection, attribute)`, `List<String> columns(collection)`
  - the names to use in `sql(...)`, checked rather than guessed.
- `String describe()` - every collection, its SQL name and its columns. Print it when writing a query.
- `register(collection, persistence)` - only needed for a collection built outside `collection(...)`.
- `getBytesUsed()`, `getFile()`, `isClosed()`, `compact()`, `optimize()`, `close()`.

Each collection is stored in a table named `cq_<name>`, with a view named `<name>` over it, which
is what `sql(...)` and `table(...)` use. Indexes live in `cqidx_<name>_<attribute>`.

### `io.quackjvm.cqengine.query.Join<L, R>`

From `database.join(left, right)`:

`on(leftAttribute, rightAttribute)`, `whereLeft(Query<L>)`, `whereRight(Query<R>)`, then either
`Stream<JoinPair<L, R>> stream()` or `long count()`. The stream holds a connection and must be
closed; `count()` answers without materialising any objects. An object whose join attribute holds
several values appears once per match, as in SQL.

### `io.quackjvm.cqengine.index.DuckDBIndex`

An index stored in DuckDB as a two-column `(objectKey, value)` table - the DuckDB counterpart of
CQEngine's `DiskIndex`.

- `DuckDBIndex.onAttribute(attribute)` - the usual choice.
- `DuckDBIndex.onAttributeWithArtIndex(attribute)` - additionally builds DuckDB ART indexes over
  the columns. Faster lookups into a very large index table, at several times the disk and memory;
  worth it only where lookups are both frequent and latency-critical.
- `DuckDBIndex.onAttributeWithSuffix(attribute, tableNameSuffix, artIndexes)` - when two indexes on
  the same attribute must coexist in one file.

Supports `equal`, `between`, `greaterThan`, `lessThan`, `in`, `has`, `stringStartsWith` and
`existsIn`. Enum attributes index normally here; CQEngine's own SQLite indexes reject them.

The index is created and populated when it is added to the collection, and reused as it stands if
the database already contains it.

### `io.quackjvm.cqengine.persistence.DuckDBBulkWriter<O>`

From `persistence.bulkWriter()`. Keeps one DuckDB Appender open per table:

```java
try (DuckDBBulkWriter<Event> writer = persistence.bulkWriter()) {
    while (records.hasNext()) {
        writer.add(toEvent(records.next()));
    }
}   // flushed and closed here
```

`add(O)`, `addAll(Iterable<? extends O>)`, `flush()`, `getObjectsWritten()`, `close()`.

What it asks of you:

- **The objects must be new.** Rows are appended, not merged, so a primary key which is already
  stored fails at the next flush. Use `IndexedCollection.update` to replace existing objects.
- **The collection is inconsistent until a flush.** Each table flushes independently, so a query
  running during a session can see an object which is not yet in every index.
- **Add your indexes first.** The writer appends to the index tables which exist when it opens.
- **One thread.** A writer is not thread-safe and holds the write lock for its lifetime, so other
  writers wait. Readers are never blocked.

### `io.quackjvm.cqengine.DuckDBFlags`

`BULK_IMPORT` tells a bulk write that every object is new, so the delete-before-insert which makes
`addAll` idempotent can be skipped. It has the same flag value as CQEngine's
`SQLiteIndexFlags.BULK_IMPORT`, so existing code using that flag already gets this behaviour.

```java
QueryOptions options = new QueryOptions();
FlagsEnabled.forQueryOptions(options).add(DuckDBFlags.BULK_IMPORT);
collection.update(Collections.emptyList(), manyObjects, options);
```

`DuckDBFlags.isBulkImport(QueryOptions)` reports whether it is set.

If a primary key is in fact already present the write fails on the primary key constraint rather
than replacing silently: the flag is an assertion, not a hint.

### `io.quackjvm.cqengine.serialization.KryoPojoSerializer`

The default serializer for BLOB storage, used when no `ColumnarLayout` is given and the object
type's `@PersistenceConfig` does not name a `PojoSerializer` of its own.
