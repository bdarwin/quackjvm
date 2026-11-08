# A distributed data system: quackjvm, Iceberg and cairn (design)

> **Design, 2026-11-08. Nothing here is built yet** except what the tables of measurements show.
> Where a sentence says *measured*, it was run; everything else is the plan.

The aim: a data system that scales by adding machines, and runs the same way in the cloud or on
machines you own. It is built from three parts, each replaceable, joined only by open protocols:

```
 Java app                      psql, BI tools     Python, Spark      scripts, web
    │                               │                  │                  │
    │ embeds                        └──── Postgres ────┴── Flight SQL ────┴── HTTP
    ▼                                                  │
 quackjvm  ◄──────────── the same library, embedded in the launcher ──────┘
    │
    ├── Iceberg REST ──► catalog (Lakekeeper, on Postgres)   which files make up each table
    │
    └── S3 ───────────► cairn, several nodes                 the files themselves
                         (or AWS S3, R2, any S3 server)
```

- **quackjvm is a library, and stays one.** An application embeds it and queries tables directly.
  There is no server you must run.
- **Doors are optional jars.** An application that wants others to query it adds a door (Postgres
  wire, Arrow Flight SQL, HTTP) and opens it on its own `Quack`.
- **The server is a thin launcher**, a few lines that open a `Quack` and its doors, shipped as a
  Docker image, for those who want a query node without writing Java.
- **Tables are Iceberg**, recorded in a REST catalog.
- **Files live in any S3 store.** In the cloud that is the provider's object storage; self-hosted
  it is cairn, which gains several nodes behind the same API.

Cloud and self-hosted differ only in two addresses: the S3 endpoint and the catalog's.

## What it is built on

Measured on 2026-11-08: DuckDB 2.0.0 (JDBC `duckdb_jdbc` 2.0.0.0) with its `iceberg` and `httpfs`
extensions, cairn at `75e4166` as the S3 store, on one Mac.

| Iceberg through a REST catalog, files in cairn | Result |
|---|---|
| `CREATE SCHEMA`, `CREATE TABLE` | work |
| `INSERT` 1,000,000 rows | 332 ms |
| `count(*), sum(id)` over them | 27 ms |
| `UPDATE` 10 rows, `DELETE` 5 rows | 132 ms, 103 ms |
| `ALTER TABLE ... ADD COLUMN` | works |
| Snapshots after insert, update, delete | 3, read with `iceberg_snapshots()` |
| Both catalogs writing their metadata files to cairn | work |

| Several query nodes appending to one table at once | Reported OK | Stored | Lost |
|---|---|---|---|
| Lakekeeper 0.10.3 on Postgres 17, 2 writers x 20 | 39 | 39 | 0 |
| Lakekeeper 0.10.3 on Postgres 17, 4 writers x 20 | 75 | 75 | 0 |
| `apache/iceberg-rest-fixture` 1.9.2, 2 writers x 20 | 40 | 29 | **11** |

Each writer was a DuckDB database of its own, as separate query nodes are. Four consequences:

1. **The catalog is what makes several writers safe, so its choice matters.** Iceberg's reference
   test catalog lost writes that had reported success, with no error anywhere. DuckDB sends the
   right check with each commit, *the table is still at snapshot X*, and the fixture refuses a
   stale commit sent on its own (409). It lets two through only when they arrive together. It is
   for single-writer tests and nothing else. Lakekeeper lost nothing.
2. **A conflict is an error, and DuckDB does not retry it.** 1 of 40 and 5 of 80 appends failed with
   `Conflict`. quackjvm has to retry, so applications do not see it.
3. **S3 needs no conditional writes for this.** The catalog decides which commit wins; the store
   only holds files with unique names. cairn has none, and needs none here.
4. **The S3 API is the whole contract with the store.** Lakekeeper checked its storage, DuckDB wrote
   and read, all against cairn without either knowing what it was.

## The parts

### 1. quackjvm: tables in a catalog

One small addition, shaped like `ObjectStore`:

```java
IcebergCatalog tables = IcebergCatalog.rest("http://catalog:8181/catalog").warehouse("lake");
try (Quack db = Quack.builder(null).objectStore(store).catalog("lake", tables).open()) {
    db.query("SELECT count(*) FROM lake.demo.items").scalar(Long.class);
    db.inTransaction(tx -> tx.execute("INSERT INTO lake.demo.items SELECT ..."));   // retried on Conflict
}
```

- Opening loads `iceberg` and attaches the catalog under the name given, before hardening is
  applied, as `ObjectStore` already does for `httpfs`.
- **Retry on conflict.** A write that fails with an Iceberg conflict is run again from the start, a
  few times with a short random wait. Only work quackjvm holds as a whole can be rerun: an
  `inTransaction` body, or a single statement. A connection the caller drives by hand gets the error.
- Authentication to the catalog: none, a bearer token, or OAuth2 client credentials, all of which
  DuckDB's `ATTACH` takes. The secret is handled as `ObjectStore` handles keys: never printed,
  scrubbed from errors.
- The name is `IcebergCatalog`, since `io.quackjvm.agent.catalog.Catalog` already exists and
  describes tables to agents.

### 2. Doors: optional jars

Each door is a module of its own, so the core jar gains no dependency:

| Module | Protocol | Who connects | Dependencies |
|---|---|---|---|
| `quackjvm-door-postgres` | Postgres wire | psql, JDBC and ODBC drivers, BI tools | none (the protocol written here) |
| `quackjvm-door-http` | HTTP, JSON and Arrow streams | scripts, web pages, curl | none (the JDK's server) |
| `quackjvm-door-flight` | Arrow Flight SQL | Python, pandas, Spark | Arrow Flight (gRPC, Netty) |

```java
PostgresDoor.on(db).port(5432).users(users).start();
```

- **A door never gives out the database itself.** Each client connection gets a `QuerySession`
  with the user's limits: the same hardening, allowed tables and guards an agent gets today. What a
  user can read is the session's rules plus the keys the node holds (part 4).
- Users: a door takes a small interface that checks a name and password or a token and returns the
  user's session settings. The application supplies it; the launcher reads a file.
- TLS: each door takes a key and certificate, and refuses plain connections unless told not to.
- Order to build: Postgres first (the most tools on day one), then HTTP, then Flight SQL.

### 3. The launcher

`quackjvm-server`: one `main` that reads a properties file (stores, catalog, doors, users), opens a
`Quack` and its doors, and exits cleanly on a signal. A Docker image of it, and a Compose file that
starts a whole self-hosted system on one machine: cairn, Postgres, Lakekeeper and one query node.

Query nodes keep nothing that matters. Data is in the store, tables in the catalog, so a node can
be killed, replaced or multiplied behind a load balancer.

### 4. cairn: several nodes

cairn's own design goes in its repository; this is what the system needs from it, and the shape
proposed there. cairn stays independent of everything above: it serves S3 and nothing else.

What it must promise:

1. **Any node answers any request**, so clients need one address (a load balancer) and no idea how
   many nodes there are.
2. **Read after write.** Once a PUT is answered, every node's GET returns it. Iceberg metadata is
   written and then read straight back by other nodes.
3. **Losing a node loses no data and stops no reads or writes.**
4. **Keys with limits.** A key allowed some buckets or prefixes, and reading only. This is already
   its TODO item 2, and it is the real fence: the client's allow-list is not (see
   [Object stores](../object-stores.md)).

The proposed shape, the one MinIO uses, chosen because it needs no consensus protocol and no
metadata service:

- **A fixed list of nodes**, in a file every node reads. No discovery.
- **Placement by hash.** An object's key decides which nodes hold it, so any node can find any
  object by computing, not by asking.
- **Copies first, erasure coding later.** Each object written to 3 nodes; a PUT is answered once 2
  have it on disk, a GET reads from 2 and returns the newer. Two of three on each side always
  overlap, which gives promise 2. Erasure coding saves space and can replace copies later without
  changing the API.
- **Growth by adding a pool.** A new group of nodes takes new objects; existing ones stay where
  they are. Nothing is moved when the cluster grows.
- **Healing.** A background pass finds objects with fewer copies than they should have and copies
  them back.
- **Listing** merges the listings of the nodes that hold the prefix.
- Nodes talk to each other over a private HTTP protocol of cairn's own, not S3.

The single-node items still open in its TODO come first, since every node inherits them: timeouts
and connection limits, TLS, and cleaning up after a crash.

### 5. The catalog

Lakekeeper, run as it comes, on Postgres. In the cloud, a managed Postgres; self-hosted, a Postgres
with a standby. It is the one stateful service besides the store, and the only one this design does
not write. Writing a catalog into cairn or quackjvm is not planned; Lakekeeper passed the test that
counts, and it is replaceable by any REST catalog that passes it too.

## Running it

| | Cloud | Self-hosted |
|---|---|---|
| Files | the provider's object storage | cairn, 3 or more nodes |
| Catalog | Lakekeeper, on managed Postgres | Lakekeeper, on Postgres |
| Queries | apps embedding quackjvm, and/or `quackjvm-server` nodes | the same |
| Keys | the provider's, one per role | cairn's limited keys, one per role |

Roles, each with its own key: query nodes that only read, nodes that write, and the catalog. A
reading node's key cannot write, whatever SQL it is sent.

## The order of work

Each step is usable on its own, and is measured before the next starts.

1. **quackjvm `IcebergCatalog`, with retry on conflict.** Tested against Lakekeeper on cairn, in
   Docker. Makes the system usable from Java at once, with AWS S3 or single-node cairn.
2. **cairn: limited keys, timeouts, TLS.** Needed before anything faces a network.
3. **The Postgres door,** then HTTP, then Flight SQL.
4. **The launcher, its Docker image, and the Compose file.** The whole system on one machine.
5. **cairn on several nodes:** placement, copies, healing, listing. Then pools.
6. **Deployment for clusters** (a Helm chart), and erasure coding in cairn.

## Open questions

- **Keeping tables small and fast.** Every insert adds a file and a snapshot. Iceberg tables need
  small files merged and old snapshots expired. Neither DuckDB nor Lakekeeper does this on its own.
  Whether DuckDB can do it well enough, or a quackjvm job is needed, is untested.
- **Partitioned tables.** Whether DuckDB 2.0 writes to partitioned Iceberg tables is untested; only
  unpartitioned ones were.
- **Credentials handed out by the catalog.** Lakekeeper can give each query a short-lived key in
  place of a fixed one. That needs cairn to issue them, an STS-like API. Fixed keys per role come
  first.
- **A cache on query nodes.** A node reading the same files repeatedly could keep them locally.
  Whether DuckDB's own caching suffices is unmeasured.
- **The fixture's lost writes** could be reported to Apache Iceberg. It is a test fixture, but
  nothing says it is unsafe with two writers.
