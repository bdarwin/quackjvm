# Measures: refreshes, contributions, and two instances (design)

A measure holds values that each belong to a record and are identified by several fields. What makes
it awkward is not the shape of one value but the life of the whole thing:

- Thousands of records, each carrying hundreds to thousands of values, and thousands of distinct keys.
- Publishers refresh them anywhere from every few milliseconds to once a day.
- A refresh is sometimes everything a publisher has, sometimes only what moved.
- Records appear and disappear, and what was published must stay auditable afterwards.
- Downstream consumers read Parquet continuously, not only when a day ends.
- History must not make the working database heavy, and must be restorable when someone asks for it.

What follows is the design we settled on, with the numbers that decided each part. What exists today
is listed near the end; the rest is to build.

## The model

**A measure** is a table per measure, its fields fixed when it is defined. One field may be the unit;
a field may declare the order its values come out in. None of that changes here - see
[Measures](../measures.md).

**A refresh** is a point on a publisher's timeline: `(writer, timestamp, id)`.

- The **timestamp** is yours. It marks the point the data belongs to, so two refreshes may share one:
  a correction for the same point, or several measures refreshed together. It cannot be assumed
  unique - `Instant.now()` repeats the previous value in most iterations of a tight loop, and DuckDB
  keeps microseconds rather than nanoseconds.
- The **id** is required, and recorded. A publisher that commits, misses the acknowledgement and
  republishes is then ignored rather than applied twice - which matters, because contributions add up.
- A hidden **commit sequence** orders refreshes that share a timestamp, so reads are deterministic.

**Within a refresh, each measure is full or an increment.** The kind is per measure, not per refresh:
a refresh that leaves a measure out leaves it untouched, where a per-refresh kind would empty it by
omission. A record that appears in either kind **replaces itself entirely**. A full set says more than
that: anything it does not mention is gone. Records can also be removed explicitly.

```java
MeasureRefresh.at(timestamp, "run-2026-09-30T09:00")
        .full(exposure, everythingExposureHas)
        .increment(sensitivity, whatMoved)
        .remove(coverage, 42)
        .commit(connection);
```

## Everything is a contribution

A change is stored as "cancel the old, add the new"; a removal as "cancel everything". Nothing is
overwritten and nothing is deleted, so the audit trail stands.

```
09:00  full        record 3:  5y +10,  10y +20            state: 5y=10, 10y=20
09:15  increment   record 3:  5y  +2,  10y -20,  30y +5   state: 5y=12, 10y=0,  30y=5
09:30  increment   record 3 removed:  5y -12, 30y -5      state: all zero, history intact
```

Reading is then one rule:

> **the state as of T = the sum of contributions from the last full set at or before T, up to T**

There is no second mode for "state" measures against "additive" ones: a measure with no full sets
sums everything, which is the same rule. Keys that cancel to zero are dropped unless `showZeros()`.

Measured on 525,000 rows: summing is 15.8 ms where keeping the newest row per key with a window is
24.5 ms - and the sum is the one that stays right when the same value arrives twice.

quackjvm works out the contributions, since it knows what a record currently holds. A publisher that
would rather send contributions itself can.

## Two instances

| | holds | written | read for |
|---|---|---|---|
| **live** | current state, plus an outbox of contributions not yet shipped | one transaction per refresh | panels, as of now |
| **history** | every contribution, with its refresh timestamp and id | in bulk, by the shipper | history, as-of, export |
| **Parquet** | what the archive has exported | on a schedule | downstream, and archived as-of reads |

The live instance stays the same size however long it runs, because it only ever holds current state:
measured at 1,548 KB holding 500,000 values after 600 refreshes.

**Why two, and why an outbox.** DuckDB refuses to write to two attached databases in one transaction:

> *"Attempting to write to database "archive" in a transaction that has already modified database
> "live" - a single transaction can only write to a single attached database."*

So a refresh cannot land in both atomically. Instead it writes the new state **and** its contributions
into the live instance in one transaction, the contributions going to an outbox table. A background
**shipper** moves the outbox into the archive instance and clears it. Nothing can be lost: until the
shipper runs, the contributions sit in a committed table. Nothing is applied twice: the shipper skips
refresh ids the archive already holds, which is also what makes a retry after a crash safe.

That gives asynchronous archiving without the usual risk of it, and the schedule is yours - every
second or every hour.

**Costs, measured:**

| | |
|---|---|
| a refresh of 1 record (25 values), live alone | 0.73 ms |
| the same, with its contributions in the outbox | 0.99 ms (+36%) |
| a refresh of 100 records (2,500 values), with outbox | 1.87 ms (+77%) |
| shipping 250,000 contributions to the archive, in bulk | 25 ms |
| clearing the outbox afterwards | 5 ms |
| shipping the same refreshes again (nothing added) | 6 ms |
| the same shipment row by row over JDBC instead | 20,657 ms - never do this |

The second instance costs nothing in interference: with four readers and a writer working flat out on
the live instance, moving 500,000 rows every second changed reads from 1,406/s to 1,409/s, and moved
the p99 by less than two runs with no move at all differ from each other.

## Parquet, and downstream

Publishing files is separate from pruning the archive. Downstream needs files all day; pruning can
only happen once a later full set exists. So three operations, not one:

1. **Export** - the archive writes new contributions as Parquet on a schedule. Downstream sees them
   promptly; nothing is removed anywhere.
2. **Compact** - a finished day's files are folded into one. Measured: 18 ms for a day; a day as 17
   files was 737 KB and 18.6 ms to query, where the one compacted file was 579 KB and 14.6 ms. Only
   completed days are compacted, so a consumer reading files as they appear is never disturbed.
3. **Prune** - the archive drops what it has exported and no longer needs, keeping back to the last
   full set.

A file is a row per contribution with its key fields beside it, plus `record_id`, `value`, and the
refresh timestamp, id and writer - and the measure's definition in the file's Parquet key-value
metadata. Anything that reads Parquet reads the rows without that metadata; quackjvm uses it to
rebuild the table. Paths stay `measure=.../part=.../writer-when.parquet` so a lake can prune by them.

At millisecond cadence a file per refresh is impossible - 86 million files a day - so files roll by
time or size and carry many refreshes each. Downstream latency is the roll interval.

**S3 and the like** work as they are: `archive(...)` writes to `s3://`, and a query reads only the
byte ranges it needs. Measured against a local HTTP server: a count, a group-by and the metadata all
answered without pulling the file down.

## Rebuilding the live instance

The archive is the truth; the live instance is a cache of the current state. It can be dropped and
rebuilt by replaying the archive from the last full set - after a corruption, a bad deploy, or a
change of definition. That is why the archive keeps back to the last full set, and why export must
not prune past it.

The live instance keeps **no window of history**. A window would double the read paths and add a
retention knob to save time on a question the archive already answers in 24.5 ms for a whole panel
and 1.8 ms for one record, and the outbox covers the newest contributions anyway. If a panel proves
that too slow, the window can be added then, with the measurement that justifies it.

## What exists today

Built, in `MeasureTable` and `MeasureQuery`, with the examples `CoreMeasures` and
`CoreMeasureLifecycle`: fields and the dictionary, any field as rows or columns, the unit rule and
conversion, declared orders, parts and provenance, archiving a part to a self-contained Parquet file,
querying files where they lie, restoring them, fields added later, and archiving by size.

Built since, in `MeasureRefresh`, with `CoreRefreshes`: **refreshes and contributions** - the refresh
model, full and increment per measure, removals that keep the audit, contributions worked out from
the current state, the id enforced, and the ledger. Measured on 100 records of 25 values published
200 times to a database on disk: 4.05 ms a refresh against 2.04 ms for a plain replace that keeps no
history, the state staying 2,500 values and the contributions reaching 51,999 rows. The 0.99 ms and
1.87 ms in the table above were the prototype's write alone; the built path also resolves keys,
writes the ledger row and commits to disk.

Built after that, in `MeasureHistory`, with `CoreMeasureHistory`: **the two instances** - the live
database holding current state with the contributions as its outbox, a second database holding every
contribution, and the shipper between them, guarded by the history's own ledger so an interrupted
shipment can be run again and shipping one measure never strands another's rows. Contributions reach
the history with their key fields beside them, since dictionary ids are local to the database that
minted them and several services ship into one history. `forgetShippedRefreshes` trims the live
ledger, the one thing that would otherwise grow for ever. Measured on 1,000 records of 50 values:
18 ms to ship the first 50,000 contributions, single digits for an increment's 5,000, 0.7 ms with
nothing waiting, the live database settling at 1,804 KB and the history at 1,292 KB.

To build, in this order, each with a runnable example:

1. **Reading as of a point** - across live, history and files, with zeros dropped.
2. **Export, compaction and pruning** on schedules, and the manifest that ties one refresh's measures
   together.
3. **Rebuilding the live database** from the history.

## What this costs you

- **Every refresh writes twice** - the state and its contribution. Measured above: +36% to +77%.
- **History grows** with every refresh; full sets are what let it be cut back.
- **Two files to operate** rather than one, and a shipper to schedule.
- **Publishing needs the current state** to work out a contribution, so a publisher that wants to
  skip that has to send contributions itself.
