# Object stores

SQL can read and write `s3://` locations: AWS S3 itself, or any server that speaks its API, such
as MinIO, R2 or [cairn](https://github.com/bdarwin/cairn). Tables go there as Parquet, and queries
read them back fetching only the byte ranges they need.

```java
ObjectStore store = ObjectStore.s3("http://localhost:9000")
        .keys(keyId, secret)
        .scope("s3://items/");

try (Quack db = Quack.builder("local.duckdb").objectStore(store).open()) {
    db.exportTable("reading", "s3://items/reading.parquet");
    db.query("SELECT count(*), avg(value) FROM read_parquet('s3://items/*.parquet')")
            .records(Total.class);
}
```

## Setting a store up

| | |
|---|---|
| `ObjectStore.s3(url)` | a server at `http://host:port` or `https://host`; buckets addressed by path, which such servers expect |
| `ObjectStore.aws(region)` | AWS S3 itself |
| `.keys(keyId, secret)` | the access key; without it requests go unsigned, for public buckets |
| `.scope("s3://items/")` | the locations this store answers for; with several stores, a location goes to the one with the longest scope it starts with |
| `.region(...)`, `.pathStyle(...)` | the region requests are signed for; path or virtual-host addressing |

`Quack.builder(...).objectStore(store)` can be called once per store. When the database opens,
DuckDB's `httpfs` extension is loaded (installed first if this machine does not have it, which
needs the network once), and each store becomes a DuckDB secret.

**The secret key stays out of sight.** The statement that creates the secret runs on the
database's own connection, not through the pool, so the pool's metrics never see it. If it fails,
the error names the store without the key. `duckdb_secrets()` shows it redacted, and so does the
store's `toString()`.

## Writing and reading

- **`db.exportTable(table, location)`** writes a table to one Parquet file, replacing one already
  there, and returns the number of rows. A large file goes up as a multipart upload.
- **For anything else, use DuckDB's SQL.** `execute("COPY (SELECT ...) TO 's3://...' (FORMAT
  parquet, PARTITION_BY (day))")` writes partitioned folders; `read_parquet('s3://items/**/*.parquet',
  hive_partitioning = true)` reads them back.
- **Reads are ordinary queries**, with the location as a parameter if you like:
  `db.query("SELECT * FROM read_parquet(?) WHERE id = ?", location, id)`.

## With hardening

A locked-down database can reach a store, and only the locations you allow:

```java
Hardening.Options options = Hardening.options()
        .allowedFolders("s3://items/")      // kept as written, not made a local path
        .objectStore(store);

// A query session:
QuerySession session = QuerySession.open(options, QueryPolicy.readOnly());
session.register("reading", "SELECT * FROM read_parquet('s3://items/reading.parquet')");

// Or a database of your own:
Quack.Builder builder = Quack.builder(null).settings(options.properties());
options.getObjectStores().forEach(builder::objectStore);
Quack db = builder.open();
```

DuckDB loads no extension once external access is off, and changes no setting once the
configuration is locked. So a database opened with a store gets `enable_external_access = false`
and `lock_configuration = true` last, once the store is set up. The tests check that the database
then reads `s3://items/`, and refuses `s3://notes/`, a neighbour such as `s3://items2/`, and local
files.

!!! warning "An allowed `s3://` folder is not a boundary on its own"
    DuckDB 2.0.0 checks a location against the allowed folders **as written**, then collapses
    `..` before sending the request. With `s3://items/` allowed, `s3://items/../notes/item.parquet`
    is let through, and fetches `/notes/item.parquet`. A test checks this, so the page will be
    corrected when DuckDB changes. SQL can build such a string as it runs, so no check of the
    statement's text can stop it.

    **The boundary is the keys.** A database that runs SQL it did not write - a query session, an
    agent's queries - should get keys that the server lets read only what that SQL may read. Encoded
    forms (`%2e%2e`) are sent encoded, and S3 servers take them as part of the key.

## Errors

A query that fails says why in its message, such as `Failed to run: SELECT ...` followed by
DuckDB's first line, like `Permission Error: Cannot access file "s3://notes/a.csv" - file
system operations are disabled by configuration` or `HTTP Error: ... (HTTP 404 Not Found)`.
