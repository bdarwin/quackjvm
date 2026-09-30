package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * A second database holding every contribution ever made, so that the one being written to holds
 * only the current state and stays the same size however long it runs.
 *
 * <pre>
 * MeasureHistory history = MeasureHistory.at("/var/lib/quack/history.duckdb");
 * history.create(live, exposure, coverage);
 *
 * // refreshes land in the live database, contributions gathering in its outbox
 * MeasureRefresh.at(now, "run-1").increment(exposure, batch).commit(live);
 *
 * // on whatever schedule suits you - every second, every hour
 * history.ship(live, exposure, coverage);
 * </pre>
 *
 * <h2>Why two databases, and why an outbox</h2>
 *
 * <p>DuckDB refuses to write to two attached databases in one transaction, so a refresh cannot land
 * in both at once. Instead it writes the new state and its contributions into the live database in
 * one transaction - the contributions being the outbox - and shipping is a separate, later thing.
 * Nothing can be lost: until it is shipped, a contribution sits in a committed table. Nothing is
 * counted twice: shipping skips refreshes the history already holds, so a shipment interrupted
 * half-way can simply be run again.</p>
 *
 * <p>Shipping is two transactions, because of the same rule: one writes the history, the next clears
 * what it took from the outbox. A crash between them leaves contributions that have been shipped
 * still in the outbox, and the next shipment ignores them.</p>
 *
 * <h2>What the history holds</h2>
 *
 * <p>Contributions arrive with their key fields beside them rather than a dictionary id, since ids
 * are local to the database that minted them and several live databases may ship into one history.
 * That is also the shape a Parquet file wants, so exporting is a copy rather than a join.</p>
 */
public final class MeasureHistory {

    /** What the history database is attached as. */
    public static final String ALIAS = "quack_history";

    private final String location;
    private final String alias;

    private MeasureHistory(String location, String alias) {
        this.location = location;
        this.alias = alias;
    }

    /** @param location a DuckDB file - one of your own, holding nothing but history */
    public static MeasureHistory at(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("A history needs a file to live in");
        }
        return new MeasureHistory(location, ALIAS);
    }

    /** The same, attached under another name - for a second history, or to avoid a clash. */
    public MeasureHistory as(String alias) {
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("An alias must be letters, digits and underscores: " + alias);
        }
        return new MeasureHistory(location, alias);
    }

    public String getLocation() {
        return location;
    }

    public String getAlias() {
        return alias;
    }

    /**
     * What one shipment moved: ledger entries - one per measure per refresh - and contributions.
     *
     * @param entries        rows added to the history's ledger, so a refresh over three measures is three
     * @param contributions  contributions added to the history
     */
    public record Shipped(long entries, long contributions) {
    }

    /**
     * Attaches the history to this connection and creates what it needs: the ledger of refreshes, and
     * a table of contributions per measure. Safe to call again.
     */
    public void create(Connection live, MeasureTable... measures) {
        attach(live);
        Sql.execute(live, "CREATE TABLE IF NOT EXISTS " + refreshTable()
                + " (refresh_id VARCHAR NOT NULL, writer VARCHAR NOT NULL, refreshed_at TIMESTAMP NOT NULL,"
                + " seq BIGINT NOT NULL, measure VARCHAR NOT NULL, kind VARCHAR NOT NULL,"
                + " PRIMARY KEY (refresh_id, measure))");
        for (MeasureTable measure : measures) {
            StringBuilder fields = new StringBuilder();
            for (String field : measure.getFields()) {
                fields.append(Sql.quote(field)).append(" VARCHAR NOT NULL, ");
            }
            Sql.execute(live, "CREATE TABLE IF NOT EXISTS " + contributionTable(measure)
                    + " (refresh_id VARCHAR NOT NULL, record_id BIGINT NOT NULL, " + fields
                    + "value DOUBLE NOT NULL)");
        }
    }

    /** Attaches the history if it is not attached to this connection already. */
    public void attach(Connection live) {
        Sql.execute(live, "ATTACH IF NOT EXISTS " + MeasureTable.literal(location) + " AS " + Sql.quote(alias));
    }

    /**
     * Moves everything in the measures' outboxes into the history, in bulk, and clears what it moved.
     *
     * <p>Measured at 25 ms for 250,000 contributions, against 20,657 ms for the same rows one
     * statement at a time - which is why this is two statements over a whole table rather than a loop.
     * Shipping the same refreshes again moves nothing and takes 6 ms.</p>
     *
     * @return what this shipment added to the history
     */
    public Shipped ship(Connection live, MeasureTable... measures) {
        if (measures.length == 0) {
            throw new IllegalArgumentException("Nothing to ship: name the measures");
        }
        attach(live);
        // What is waiting: a handful of ids, whatever the outbox holds behind them. Everything below
        // is then a lookup on those rather than a sweep of a history that only grows.
        List<String> waiting = waitingRefreshes(live, measures);
        if (waiting.isEmpty()) {
            return new Shipped(0, 0);
        }
        String ids = inList(waiting);
        long entries;
        long contributions = 0;
        // One transaction, writing only the history: DuckDB allows no more than that.
        boolean ownTransaction = Transactions.isAutoCommit(live);
        try {
            if (ownTransaction) {
                Transactions.begin(live);
            }
            // Contributions first, because the history's ledger entry is what says they are there.
            // Were the entry written first, this would take its own word for it and ship nothing.
            for (MeasureTable measure : measures) {
                contributions += Sql.executeUpdate(live, "INSERT INTO " + contributionTable(measure)
                        + " SELECT c.refresh_id, c.record_id, " + measure.fieldList("k") + ", c.value FROM "
                        + Sql.quote(measure.getContributionTable()) + " c JOIN " + Sql.quote(measure.getKeyTable())
                        + " k ON k.id = c.key_id WHERE c.refresh_id IN " + ids + " AND NOT EXISTS (SELECT 1 FROM "
                        + refreshTable() + " l WHERE l.refresh_id = c.refresh_id AND l.measure = "
                        + MeasureTable.literal(measure.getName()) + ")", List.of());
            }
            // Only for the measures being shipped: an entry for one whose contributions stayed behind
            // would tell the next shipment they had already gone.
            entries = Sql.executeUpdate(live, "INSERT INTO " + refreshTable()
                    + " SELECT r.refresh_id, r.writer, r.refreshed_at, r.seq, r.measure, r.kind FROM "
                    + Sql.quote(MeasureRefresh.LEDGER) + " r WHERE r.refresh_id IN " + ids + " AND r.measure IN "
                    + inList(names(measures)) + " AND NOT EXISTS (SELECT 1 FROM " + refreshTable()
                    + " h WHERE h.refresh_id = r.refresh_id AND h.measure = r.measure)", List.of());
            if (ownTransaction) {
                Transactions.commit(live);
            }
        }
        catch (RuntimeException e) {
            if (ownTransaction) {
                Transactions.rollbackQuietly(live);
            }
            throw new IllegalStateException("Failed to ship contributions to " + location, e);
        }
        // A second transaction, writing only the live database. Every id here now has its entry in
        // the history; a crash before this leaves shipped contributions in the outbox, and the next
        // shipment finds their entry and passes over them.
        for (MeasureTable measure : measures) {
            Sql.execute(live, "DELETE FROM " + Sql.quote(measure.getContributionTable())
                    + " WHERE refresh_id IN " + ids);
        }
        return new Shipped(entries, contributions);
    }

    /** The refreshes with something waiting in these measures' outboxes. */
    private List<String> waitingRefreshes(Connection live, MeasureTable... measures) {
        StringBuilder sql = new StringBuilder();
        for (MeasureTable measure : measures) {
            sql.append(sql.length() == 0 ? "" : " UNION ").append("SELECT DISTINCT refresh_id FROM ")
                    .append(Sql.quote(measure.getContributionTable()));
        }
        List<String> ids = new ArrayList<>();
        try (java.sql.PreparedStatement statement = live.prepareStatement(sql.toString());
             java.sql.ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getString(1));
            }
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read what is waiting to be shipped", e);
        }
        return ids;
    }

    private static List<String> names(MeasureTable... measures) {
        List<String> names = new ArrayList<>();
        for (MeasureTable measure : measures) {
            names.add(measure.getName());
        }
        return names;
    }

    private static String inList(List<String> values) {
        StringBuilder list = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(MeasureTable.literal(values.get(i)));
        }
        return list.append(')').toString();
    }

    /**
     * Drops refreshes from the live ledger once they are in the history and older than {@code keep}.
     *
     * <p>The live ledger is what makes committing an id twice harmless, and a publisher that missed an
     * acknowledgement republishes within seconds, not days - so at a refresh every few milliseconds
     * this is what stops one row per refresh accumulating for ever. The history keeps them all.</p>
     *
     * @return how many were forgotten
     */
    public long forgetShippedRefreshes(Connection live, Duration keep) {
        attach(live);
        LocalDateTime before = LocalDateTime.ofInstant(Instant.now().minus(keep), ZoneOffset.UTC);
        return Sql.executeUpdate(live, "DELETE FROM " + Sql.quote(MeasureRefresh.LEDGER) + " r WHERE"
                + " r.refreshed_at < " + MeasureTable.literal(before.toString()) + "::TIMESTAMP AND EXISTS"
                + " (SELECT 1 FROM " + refreshTable() + " h WHERE h.refresh_id = r.refresh_id"
                + " AND h.measure = r.measure)", List.of());
    }

    /** How many refreshes the history holds. */
    public long refreshCount(Connection live) {
        attach(live);
        return Sql.queryLong(live, "SELECT count(DISTINCT refresh_id) FROM " + refreshTable(), List.of());
    }

    /** How many contributions the history holds for a measure. */
    public long contributionCount(Connection live, MeasureTable measure) {
        attach(live);
        return Sql.queryLong(live, "SELECT count(*) FROM " + contributionTable(measure), List.of());
    }

    /** The history's ledger of refreshes, to read or join against. */
    public String refreshTable() {
        return Sql.quote(alias) + "." + Sql.quote(MeasureRefresh.LEDGER);
    }

    /** A measure's contributions in the history: {@code (refresh_id, record_id, fields..., value)}. */
    public String contributionTable(MeasureTable measure) {
        return Sql.quote(alias) + "." + Sql.quote(measure.getContributionTable());
    }

    @Override
    public String toString() {
        return "MeasureHistory[" + location + " as " + alias + "]";
    }
}
