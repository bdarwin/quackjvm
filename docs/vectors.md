# Vector similarity

The rows whose vector is nearest a given one - what an agent's memory needs, and what a recommendation
is underneath.

```java
VectorSearch search = VectorSearch.on(connection, "note", "embedding").identifiedBy("id");

List<Match> nearest = search.topK(queryVector, 10);
List<Match> inTopic = search.topK(queryVector, 10, "topic = ?", "weather");
```

Each `Match` carries the id column's value and the score. `search.sql(query, k, filter)` gives the SQL,
for joining your own tables to it.

## No extension needed

`array_cosine_similarity`, `array_distance` and `array_inner_product` are built into DuckDB. Nothing
here loads an extension, reaches the network, or touches a file - so vector search works on a database
hardened by [the guard](guarding-queries.md).

The column must be a **fixed-size** array, because that is what those functions take:

```java
ColumnarLayout.builder(Note.class)
        .vectorColumn("embedding", 768, Note::embedding)   // FLOAT[768], not FLOAT[]
```

A `FLOAT[]` column is refused with a message pointing at `vectorColumn`, and a query vector of the wrong
length is refused naming both lengths.

## The three metrics

| | function | nearest is | note |
|---|---|---|---|
| `COSINE` (default) | `array_cosine_similarity` | highest | 1 identical, -1 opposite; length-independent |
| `EUCLIDEAN` | `array_distance` | lowest | 0 identical |
| `INNER_PRODUCT` | `array_inner_product` | highest | not normalised, so a longer vector scores higher |

## What an exact search costs

Every row is scored. Measured on this machine, DuckDB 1.5.5, top-10:

| vectors | dimensions | size | top-10 |
|---|---|---|---|
| 10,000 | 768 | 29 MB | 15.8 ms |
| 100,000 | 768 | 293 MB | 109.1 ms |
| 100,000 | 128 | 49 MB | 15.6 ms |
| 1,000,000 | 128 | 488 MB | 40.8 ms |

The cost follows rows times dimensions, with a penalty for width: a million 128-dimension vectors are
faster than a hundred thousand 768-dimension ones despite being more bytes. **A million vectors of 768
floats is 3 GB before DuckDB sees any of them**, which is why the row-count scaling is measured at 128
here rather than quoted for a run nobody did.

Writing them, for context: 7 µs a row at 128 dimensions, 41 µs at 768.

So: below a few hundred thousand vectors, exact search needs nothing else. Past that, an index earns its
keep.

## The HNSW index, honestly

DuckDB's HNSW index lives in the `vss` extension, which **must already be installed** - installing one
needs the network and a writable extension directory, which a hardened database forbids by design.

```java
String why = search.createIndex();       // null when it was created
VectorSearch.isIndexAvailable(connection);
VectorSearch.unavailableReason(connection);
```

On a machine that has never installed it, which includes the one these numbers come from:

```
the vss extension is not installed. Install it once, outside quackjvm, with INSTALL vss -
it needs the network, which a hardened database forbids. Exact search works without it.
```

**So the HNSW path is written and detected but not measured here**, and this page will not pretend
otherwise. What can be said without measuring: the queries `VectorSearch` builds are the shape DuckDB's
planner uses an HNSW index for - a `LIMIT k` over an `ORDER BY` on the array function - so nothing about
the call changes once the index exists.

See `examples/src/main/java/CoreVectorSearch.java` for all of it, with its real output.
