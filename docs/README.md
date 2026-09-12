# quackjvm documentation

quackjvm makes DuckDB usable from the JVM as an embedded columnar engine. It has two parts, and
you can use either on its own.

| | |
|---|---|
| **[Getting started](getting-started.md)** | Install it, store your first objects, run your first query. Start here. |
| **[Storing objects](storing-objects.md)** | BLOB or columnar, what each costs, which types are supported, how layouts work. |
| **[Querying](querying.md)** | Every query form, what is pushed into SQL and what is not, and how to tell. |
| **[Joins across collections](joins.md)** | The thing CQEngine cannot do: `existsIn`, joined pairs, and arbitrary SQL. |
| **[Aggregates and projections](aggregates.md)** | Answering questions without rebuilding objects — the biggest performance lever. |
| **[Writing data](writing.md)** | `add`, `addAll`, `BULK_IMPORT`, and the streaming bulk writer. |
| **[Tuning](tuning.md)** | Memory limits, caches, `optimize()`, ART indexes, concurrency. |
| **[Troubleshooting](troubleshooting.md)** | Every error message you are likely to see, and what it means. |
| **[Migrating](migrating.md)** | Coming from on-heap CQEngine, or from its SQLite persistence. |
| **[API reference](api-overview.md)** | Class by class. |

Every example in these pages is complete and runnable. The ones in
[`examples/`](../examples/) are full programs with their real output recorded.

## The shortest possible version

```java
// One line changes. Your queries do not.
IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(
        DuckDBPersistence.onPrimaryKey(Car.CAR_ID));

cars.addAll(millionCars);
cars.retrieve(equal(Car.MANUFACTURER, "Ford"));   // exactly as before
```

A million objects that cost 861 MB of Java heap now cost 3 MB, and the garbage collector has
nothing to walk.

## Which part do I need?

- **You use CQEngine and want your heap back** → [Getting started](getting-started.md), then
  [Migrating](migrating.md).
- **You want to join two collections** → [Joins](joins.md). This needs `DuckDBDatabase`.
- **You do not use CQEngine at all** → `quackjvm-core` alone gives you Java objects as DuckDB
  columns and typed SQL results: [Storing objects](storing-objects.md) and
  [Aggregates](aggregates.md).
