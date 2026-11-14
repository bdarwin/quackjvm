# Iceberg tables

Tables that several databases, on several machines, read and write at once. The files are Parquet
in an [object store](object-stores.md); an Iceberg REST catalog records which files make up each
table and takes one commit at a time. Any catalog that speaks the REST protocol works: Lakekeeper,
Polaris, Nessie, Gravitino, or a cloud provider's.

```java
ObjectStore store = ObjectStore.s3("http://storage:9000").keys(keyId, secret);
IcebergCatalog lake = IcebergCatalog.rest("http://catalog:8181/catalog").warehouse("lake");

try (Quack db = Quack.builder(null).objectStore(store).catalog("lake", lake).open()) {
    db.execute("CREATE TABLE lake.demo.items (id BIGINT, name VARCHAR)");
    db.execute("INSERT INTO lake.demo.items VALUES (1, 'a')");     // retried if another writer commits first
    db.query("SELECT count(*) FROM lake.demo.items").scalar(Long.class);
    db.tidy("lake.demo.items", Duration.ofDays(1));                  // merge small files, drop old snapshots
}
```

It is how quackjvm scales out: every database that opens the same catalog sees the same tables,
so query nodes keep nothing of their own and can be added or replaced at will. The
[design](proposals/distributed.md) has the whole picture.

## Measured

quackjvm against Lakekeeper 0.10.3 on Postgres 17, with [cairn](https://github.com/bdarwin/cairn)
storing the files, DuckDB 2.0.0, one Mac, 2026-11-14:

| | Result |
|---|---|
| `INSERT` 1,000,000 rows | 167 ms |
| `INSERT` of one row | 115 ms each, over 100 |
| `tidy` after those 101 inserts | 2.5 s: 101 files merged into 1, 101 old snapshots removed, 304 files (4.7 MB) deleted |
| A database opened afterwards | reads all 1,000,100 rows |
| A hardened database (`allowed_directories = ['s3://warehouse/']`, external access off, locked) | reads the table; `s3://other/` refused |

Every write is a commit to the catalog, so small writes are slow and leave a file and a snapshot
each. Write in batches where you can, and `tidy` the rest.

## Opening

| | |
|---|---|
| `IcebergCatalog.rest(url)` | the catalog, at the URL before `/v1/`, such as `http://localhost:8181/catalog` |
| `.warehouse(name)` | which warehouse, for catalogs with several; Lakekeeper and Polaris need it |
| `.token(token)` | a bearer token sent with every request |
| `.oauth2(clientId, secret, tokenUrl)` | OAuth2 client credentials, exchanged for a token |
| `.retries(n)` | how many more times a conflicting commit is tried; 4 unless set |

`Quack.builder(...).catalog(name, catalog)` attaches it under `name`, so its tables are
`name.schema.table` in SQL. The iceberg extension is loaded when the database opens (installed
first if this machine does not have it).

**Keys.** With an `ObjectStore` given, DuckDB reads and writes the table files with that store's
keys. Without one, it asks the catalog to hand out credentials, which only some catalogs do, and
only with a cloud behind them: measured, Lakekeeper without one offers to sign each request
itself instead, and DuckDB 2.0 cannot use that. It sent its writes unsigned, and cairn refused them.

**Secrets stay out of sight.** The token and the client secret become a DuckDB secret, created on
the database's own connection, and are left out of `toString()` and of every error.

**Hardened databases** reach the catalog: when the settings turn external access off, the
catalog's URL is added to `allowed_directories`. The `s3://` folders its tables live in must be
allowed as for any other location. Measured: without the catalog's URL allowed, DuckDB refuses
every request to it, `ATTACH` included.

## When two writers meet

The catalog takes one commit at a time and refuses one made against a version of the table that
has since moved on. What happens next depends on the statement and the call:

| | |
|---|---|
| An append that meets other appends | DuckDB applies it on top of them; nothing to retry |
| An insert or a delete the catalog refuses (409) | DuckDB itself sends it again, up to 5 times (about 1.2 s) |
| A delete or update that meets a change to the rows it read | refused: *"conflicts with a concurrent commit"* |
| **`db.execute(sql)`** that fails either way | run again by quackjvm, up to `retries` times, after a short random wait |
| **`db.inTransaction(work)`** that fails either way | throws `CommitConflict`; the work is not run again |
| **`db.retrying(work)`** | as `inTransaction`, but runs the work again on `CommitConflict` |

A statement fails or commits as a whole, so running it again is safe. A transaction's work is not
rerun unless asked, since it may have done things outside the database; give `retrying` work that
only reads and writes the database.

Measured, four databases each adding 1 to its own row of one table 25 times, as fast as they could,
with `retries(20)`:

| Writers x updates | Took | Slowest update | Result |
|---|---|---|---|
| 1 x 25 | 3.7 s | 171 ms | every update applied once |
| 2 x 25 | 9.5 s | 4.1 s | every update applied once |
| 4 x 25 (three runs) | 22.8 to 24.8 s | 13.7 to 15.1 s | 99 of 100 applied once; 1 gave up after 20 retries, with a `Conflict_409` error |

Nothing was lost without an error, and nothing was applied twice. But a table takes commits one
at a time, and a writer that keeps meeting others can keep losing: several machines writing one
table nonstop is what Iceberg suits least. Give each writer its own table, or batch writes.

## Tidying

Every commit leaves a snapshot, every small insert a file, and both stay until removed. Measured
with 200 one-row inserts on top of 1,000,000 rows: a query in a fresh database went from 34 ms to as
much as 273 ms.

`db.tidy("lake.demo.items", keepFor)` does, in order:

1. **Merges small files** with DuckDB's `iceberg_rewrite_data_files`.
2. **Removes snapshots taken more than `keepFor` ago**, through the catalog's REST API, keeping the
   current one and any a branch or tag points to. The table can no longer be read as it stood
   then. DuckDB has no function for this, and Lakekeeper removes nothing on its own.
3. **Deletes the files no remaining snapshot refers to** and that are older than `keepFor`, from
   the table's own `data/` and `metadata/` folders. Nothing in the stack does this either.

It returns a `Tidied`: files merged and written, snapshots removed, files deleted and their bytes,
and files kept.

**`keepFor` is what protects another writer's work.** A file a writer has written but not yet
committed is referred to by no snapshot; only its age keeps it. Keep `keepFor` longer than any write
takes: hours, not seconds.

Files are deleted through the database's `ObjectStore`s (a signed `DELETE`, checked by cairn), or
from the local disk. Files anywhere else are counted as kept. A table whose properties put files
outside its folders (`write.data.path`, `write.metadata.path`, object-storage layout) has nothing
deleted, since listing its folders would not find them all.

Run it on a schedule of your choosing. It commits like any writer, and is retried the same way.

## Limits

- `tidy` takes `catalog.schema.table` with no dots inside a name.
- The catalog's choice matters. Iceberg's own test catalog, `apache/iceberg-rest-fixture`, loses
  commits made at the same moment through `transactions/commit`, which DuckDB uses: measured, 17 of
  77 with 4 writers. Use it for single-writer tests only. Lakekeeper lost none.
- DuckDB 2.0 writes partitioned tables and reads only the partitions a query needs: measured, one
  day of 30 fetched 1 file of 30.
