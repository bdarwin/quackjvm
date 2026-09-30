package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One point on a publisher's timeline: what it published, and when it says that was.
 *
 * <pre>
 * MeasureRefresh.at(nineOClock, "run-2026-09-30T09:00")
 *         .full(exposure, everythingExposureHas)
 *         .increment(sensitivity, whatMoved)
 *         .remove(coverage, 42)
 *         .commit(connection);
 * </pre>
 *
 * <h2>Full or incremental, per measure</h2>
 *
 * <p>A record that appears in a refresh replaces itself entirely, whichever kind it came in: a key it
 * held and the refresh does not is gone. A {@link #full} set says more - the measure holds exactly
 * what the refresh names, and records it does not name are gone. The kind is per measure, so a
 * refresh that leaves a measure out leaves that measure untouched, where one kind for the whole
 * refresh would empty it by omission.</p>
 *
 * <h2>Contributions</h2>
 *
 * <p>Nothing is overwritten. In an increment, a change is written as cancelling the old value and
 * adding the new, and a removal as cancelling everything the record held, so that what was published
 * can still be seen afterwards. A {@link #full} set is a baseline instead: it contributes the values
 * themselves, and whatever it does not name is simply not in it.</p>
 *
 * <p>The measure's own tables hold the state that results, and that state is always the sum of the
 * contributions from the last full set onwards - which is the one rule reading as of a point in time
 * follows, and the reason everything before a full set can be thrown away.</p>
 *
 * <h2>The timestamp, and the id</h2>
 *
 * <p>The timestamp is yours: it marks the point the data belongs to, not the moment of writing, so
 * two refreshes may share one - a correction of the same point, or several measures refreshed
 * together. Because of that it cannot identify a refresh, and the id can and must: a publisher that
 * commits, misses the acknowledgement and publishes again is ignored rather than counted twice,
 * which matters when contributions add up. Refreshes that share a timestamp are ordered by the
 * sequence they committed in.</p>
 *
 * <p>Every measure in one refresh must have been {@link MeasureTable.Builder#writtenBy written by}
 * the same publisher: a timeline belongs to whoever is publishing it.</p>
 */
public final class MeasureRefresh {

    /** The ledger of refreshes, shared by every measure in a database. */
    public static final String LEDGER = "measure_refresh";
    /** How far along its timeline each publisher has taken each measure. */
    public static final String WATERMARK = "measure_watermark";

    private static final String SEQUENCE = "measure_refresh_seq";

    private final Instant at;
    private final String id;
    private final Map<MeasureTable, Entry> entries = new LinkedHashMap<>();

    private static final class Entry {
        private MeasureBatch batch;
        private boolean full;
        private final List<Long> removed = new ArrayList<>();
    }

    private MeasureRefresh(Instant at, String id) {
        if (at == null) {
            throw new IllegalArgumentException("A refresh needs the timestamp it belongs to");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A refresh needs an id, so that publishing it twice counts once");
        }
        this.at = at;
        this.id = id;
    }

    /**
     * @param at the point this data belongs to - yours to choose, and free to repeat
     * @param id what makes this refresh itself: committing the same id twice applies it once
     */
    public static MeasureRefresh at(Instant at, String id) {
        return new MeasureRefresh(at, id);
    }

    public Instant getAt() {
        return at;
    }

    public String getId() {
        return id;
    }

    /** Everything this publisher has of that measure: records it does not name are removed. */
    public MeasureRefresh full(MeasureTable measure, MeasureBatch batch) {
        return put(measure, batch, true);
    }

    /** Only the records that moved. Records the refresh does not name are left alone. */
    public MeasureRefresh increment(MeasureTable measure, MeasureBatch batch) {
        return put(measure, batch, false);
    }

    /**
     * Removes records, cancelling every value they hold. What they held stays in the contributions,
     * so a reader asking about an earlier point still sees it.
     */
    public MeasureRefresh remove(MeasureTable measure, long... recordIds) {
        Entry entry = entryFor(measure);
        for (long recordId : recordIds) {
            entry.removed.add(recordId);
        }
        return this;
    }

    private MeasureRefresh put(MeasureTable measure, MeasureBatch batch, boolean full) {
        if (batch == null) {
            throw new IllegalArgumentException("Nothing to publish to " + measure.getName()
                    + "; to empty it, give full() an empty batch");
        }
        Entry entry = entryFor(measure);
        if (entry.batch != null) {
            throw new IllegalArgumentException(measure.getName() + " is already in this refresh: one refresh is one"
                    + " point in time, so build the whole batch first");
        }
        entry.batch = batch;
        entry.full = full;
        return this;
    }

    private Entry entryFor(MeasureTable measure) {
        if (!entries.isEmpty()) {
            String publisher = entries.keySet().iterator().next().getWrittenBy();
            if (!publisher.equals(measure.getWrittenBy())) {
                throw new IllegalArgumentException("A refresh is one publisher's timeline, but " + measure.getName()
                        + " is written by '" + measure.getWrittenBy() + "' and this refresh by '" + publisher + "'");
            }
        }
        return entries.computeIfAbsent(measure, m -> new Entry());
    }

    /**
     * Applies the whole refresh, or none of it. Creates the ledger if it is not there.
     *
     * @return false if this id has been committed before, in which case nothing was written
     */
    public boolean commit(Connection connection) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Refresh '" + id + "' publishes nothing");
        }
        List<MeasureTable> measures = new ArrayList<>(entries.keySet());
        // Locked in one order, always, so two refreshes over the same measures cannot deadlock.
        measures.sort(Comparator.comparing(MeasureTable::getName));
        createLedger(connection);
        for (MeasureTable measure : measures) {
            measure.lockWrites();
        }
        boolean ownTransaction = Transactions.isAutoCommit(connection);
        try {
            if (ownTransaction) {
                Transactions.begin(connection);
            }
            if (alreadyCommitted(connection)) {
                if (ownTransaction) {
                    Transactions.commit(connection);
                }
                return false;
            }
            LocalDateTime when = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
            for (MeasureTable measure : measures) {
                checkTimeline(connection, measure, when);
            }
            long sequence = Sql.queryLong(connection, "SELECT nextval('" + SEQUENCE + "')", List.of());
            for (MeasureTable measure : measures) {
                Entry entry = entries.get(measure);
                MeasureBatch batch = entry.batch != null ? entry.batch : measure.batch().build();
                long[] removed = new long[entry.removed.size()];
                for (int i = 0; i < removed.length; i++) {
                    removed[i] = entry.removed.get(i);
                }
                measure.refresh(connection, batch, entry.full, removed, id, when);
                Sql.execute(connection, "INSERT INTO " + Sql.quote(LEDGER) + " VALUES ("
                        + MeasureTable.literal(id) + ", " + MeasureTable.literal(measure.getWrittenBy()) + ", "
                        + MeasureTable.literal(when.toString()) + "::TIMESTAMP, " + sequence + ", "
                        + MeasureTable.literal(measure.getName()) + ", " + (entry.full ? "'full'" : "'increment'")
                        + ")");
                Sql.execute(connection, "INSERT OR REPLACE INTO " + Sql.quote(WATERMARK) + " VALUES ("
                        + MeasureTable.literal(measure.getWrittenBy()) + ", "
                        + MeasureTable.literal(measure.getName()) + ", "
                        + MeasureTable.literal(when.toString()) + "::TIMESTAMP)");
            }
            if (ownTransaction) {
                Transactions.commit(connection);
            }
            return true;
        }
        catch (SQLException | RuntimeException e) {
            if (ownTransaction) {
                Transactions.rollbackQuietly(connection);
            }
            throw e instanceof RuntimeException re ? re
                    : new IllegalStateException("Failed to commit refresh '" + id + "'", e);
        }
        finally {
            for (MeasureTable measure : measures) {
                measure.unlockWrites();
            }
        }
    }

    /**
     * A publisher's timeline only moves forward. A refresh dated before the last one it published of
     * that measure is refused, because a contribution says how much a value moved from what the
     * measure held when it was written: were an older point allowed in afterwards, reading as of a
     * point between the two would add that movement to a state it was never measured against.
     *
     * <p>A correction is published at a new point, which is also the truth of it - the correction
     * happened now. Two refreshes may share a point; those are ordered by the sequence they
     * committed in, and their movements still chain in that order.</p>
     */
    private void checkTimeline(Connection connection, MeasureTable measure, LocalDateTime when) {
        String last;
        try (java.sql.PreparedStatement statement = connection.prepareStatement("SELECT refreshed_at FROM "
                + Sql.quote(WATERMARK) + " WHERE writer = " + MeasureTable.literal(measure.getWrittenBy())
                + " AND measure = " + MeasureTable.literal(measure.getName()));
             java.sql.ResultSet rows = statement.executeQuery()) {
            last = rows.next() ? rows.getString(1) : null;
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read how far '" + measure.getWrittenBy() + "' has taken "
                    + measure.getName(), e);
        }
        if (last != null && LocalDateTime.parse(last.replace(' ', 'T')).isAfter(when)) {
            throw new IllegalArgumentException("'" + measure.getWrittenBy() + "' has already published "
                    + measure.getName() + " at " + last + ", and a timeline only moves forward - this refresh is"
                    + " dated " + when + ". Publish the correction at a later point: it is a change made now to"
                    + " what was said then, and reading as of a point in between must not see it.");
        }
    }

    private boolean alreadyCommitted(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(LEDGER) + " WHERE refresh_id = "
                + MeasureTable.literal(id), List.of()) > 0;
    }

    /** The ledger: a row per measure per refresh, saying when it was for and whether it was full. */
    public static void createLedger(Connection connection) {
        Sql.execute(connection, "CREATE SEQUENCE IF NOT EXISTS " + Sql.quote(SEQUENCE) + " START 1");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(LEDGER)
                + " (refresh_id VARCHAR NOT NULL, writer VARCHAR NOT NULL, refreshed_at TIMESTAMP NOT NULL,"
                + " seq BIGINT NOT NULL, measure VARCHAR NOT NULL, kind VARCHAR NOT NULL,"
                + " PRIMARY KEY (refresh_id, measure))");
        // Kept for ever, unlike the ledger, which is trimmed: it is what says a timeline may not
        // go backwards, and one row per publisher per measure costs nothing.
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(WATERMARK)
                + " (writer VARCHAR NOT NULL, measure VARCHAR NOT NULL, refreshed_at TIMESTAMP NOT NULL,"
                + " PRIMARY KEY (writer, measure))");
    }

    @Override
    public String toString() {
        return "MeasureRefresh[" + id + " at " + at + " over " + entries.keySet().stream()
                .map(MeasureTable::getName).toList() + "]";
    }
}
