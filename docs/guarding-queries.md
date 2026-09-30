# Guarding queries

Running SQL that came from somewhere you do not control - a language model, a user, another service -
against a database that has real data in it.

```java
GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly());
GuardedResult result = guard.run(whateverTheAgentWrote);
System.out.println(result.toText());
```

Four things stand between the statement and the database: what it is, how long it may run, how much
it may return, and - if you turn it on - what it can reach.

## What it is: DuckDB's parser decides

The check is `json_serialize_sql`, which parses the text and hands back its syntax tree. DuckDB
serializes **only queries**, so everything else comes back as an error and is refused:

| allowed | refused |
|---|---|
| `SELECT`, `WITH`, `FROM`-first, `TABLE`, `VALUES` | `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `DROP`, `ALTER`, `TRUNCATE` |
| `DESCRIBE`, `SUMMARIZE`, `UNPIVOT` | `ATTACH`, `COPY`, `INSTALL`, `LOAD`, `EXPORT` |
| `EXPLAIN`, `EXPLAIN ANALYZE` of an allowed query | `SET`, `PRAGMA`, `CALL`, `BEGIN`, `PREPARE`, `CREATE MACRO` |

A CTE with a write inside it - `WITH x AS (DELETE ... RETURNING *) SELECT * FROM x` - is refused too,
by the parser, not by us. The statements come back as an array, so **more than one statement is a
count**, not a hunt for semicolons: `SELECT 'a;b'`, `$$c;d$$`, `-- comments` and `/* nested /* ones */ */`
are the parser's problem rather than ours.

### Why it never prepares the statement to find out

With `duckdb_jdbc` 1.5.5, preparing a multi-statement string **executes all but the last statement**:

```java
connection.prepareStatement("DROP TABLE secrets; SELECT 1");   // never executed
// secrets is gone
```

That is measured, in `CoreGuardedQueries`, and it is why a guard that validates by preparing has
already lost. Nothing here prepares a statement until the parser has said it is a single query.

### PIVOT, which cannot be checked

DuckDB refuses to serialize `PIVOT` as well - even wrapped in a subquery - so the parser cannot tell a
pivot apart from a statement that writes. It is refused by default, and the message says so. If you
need it:

```java
QueryPolicy.readOnly().allowUnverified(QueryPolicy.PIVOT);
```

What that gives up: the parser is no longer what decides. The statement is recognised by its leading
keyword, and a small scanner - one that knows quotes, dollar-quoting and nested comments - stands in
for the parser's promise that there is only one statement. The caps and the timeout still apply.
Nothing else can be allowed this way; `allowUnverified("INSERT")` throws.

## How long, and how much

```java
QueryPolicy.readOnly()
        .timeout(Duration.ofSeconds(2))
        .maxRows(50)
        .maxBytes(1 << 20);
```

- **Timeout** cancels the statement through `Statement.cancel()` from a daemon scheduler. DuckDB stops
  mid-scan and the connection stays usable - measured at 310 ms to cancel a query that would have run
  for minutes.
- **maxRows** becomes a `LIMIT` around the statement, so the rows are never built: a `SELECT range FROM
  range(2000000000)` with a five-row cap comes back in milliseconds. A query that would exceed the cap
  is **refused**, not quietly cut short - a truncated answer read as a whole one is worse than no
  answer. If the statement cannot be wrapped, the cap is applied as the rows arrive instead.
- **maxBytes** is counted as the values are read, and is an estimate: two bytes a character, eight for
  a number, the length of a blob.

## What it can reach: hardening

`Hardening.DATABASE` takes away the database's access to files, extensions and the network, so a
statement that somehow got past the check still cannot reach anything:

```java
QueryPolicy.readOnly().hardening(Hardening.DATABASE);
```

**Read this before turning it on.** In DuckDB this is a property of the *database*, not of a
connection, and it cannot be undone while the database runs - *"Cannot enable external access while
database is running"*. Every connection to that database loses file access at once, including yours:
`MeasureTable.archive`, `MeasureHistory.export`, `read_parquet` and every Parquet restore stop
working. That is why it is off by default.

### The sandbox, which costs nothing

```java
try (Connection sandbox = Hardening.sandbox()) {
    // register live Java data on it, or copy snapshots in
    GuardedQuery.on(sandbox, QueryPolicy.readOnly()).run(sql);
}
```

A second, empty database opened with external access off and the configuration locked. A hardened
database can still see live Java objects through a registered table function, and can hold tables
copied into it - both measured - so an agent gets a database with no way out while the database your
application writes to keeps everything it had.

## What it costs

Measured on 200,000 rows, this machine, DuckDB 1.5.5:

| | |
|---|---|
| the check alone, nothing run | 0.20 ms |
| a grouped aggregate, guarded | 2.57 ms |
| the same, unguarded | 2.06 ms |

One parse of the statement, and one of the wrapped form that carries the row cap - about half a
millisecond, whatever the query costs afterwards.

## When it refuses

Every refusal is an exception whose message is written to be read by whoever sent the SQL:

- `QueryRejected` - what was refused and what is allowed instead.
- `QueryTimedOut` - how long it was given, and that narrowing the query would help.
- `ResultTruncated` - which cap was passed, and that a `LIMIT`, a filter or a `GROUP BY` would help.

`guard.whyRejected(sql)` gives the same message without running anything, for a tool that wants to
tell an agent why before it tries.

See `examples/src/main/java/CoreGuardedQueries.java` for all of it, with its real output.
