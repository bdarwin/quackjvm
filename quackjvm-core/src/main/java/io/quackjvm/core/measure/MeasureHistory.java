package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final String PRUNED = "measure_pruned";

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
            // Deliberately without the refresh's sequence number on each row: carrying it would let a
            // read as of a point skip row groups by range, which measured 63 ms against 67 for an older
            // point, no better for the newest one, 8 bytes a row and 13% more history on disk. Declined.
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

    // ---------- Parquet ----------

    /** What one export wrote. */
    public record Exported(List<String> files, long refreshes, long contributions) {
    }

    /**
     * Writes contributions the history has not exported yet as Parquet, one file per day of refresh,
     * and remembers that it has.
     *
     * <p>Exporting is not pruning. Downstream wants files all day, where the history can only be cut
     * back once a later full set exists - so these are separate calls, on separate schedules.</p>
     *
     * <p>A file holds a row per contribution with its key fields beside it, the refresh it belongs to
     * and when that was, and the measure's definition in the file's metadata. Anything that reads
     * Parquet reads the rows; nothing else is needed to understand them.</p>
     *
     * @param location where to write: a directory, or anything DuckDB can write such as {@code s3://}
     */
    public Exported export(Connection live, MeasureTable measure, String location) {
        attach(live);
        createExportLedger(live);
        List<String> days = pendingDays(live, measure);
        List<String> files = new ArrayList<>();
        long refreshes = 0;
        long contributions = 0;
        for (String day : days) {
            String file = fileFor(location, measure, day);
            String pending = pending(measure, day);
            Sql.execute(live, "COPY (SELECT c.record_id, " + measure.fieldList("c") + ", c.value, r.refresh_id,"
                    + " r.refreshed_at, r.writer, r.kind, r.seq FROM " + contributionTable(measure) + " c JOIN "
                    + refreshTable() + " r ON r.refresh_id = c.refresh_id AND r.measure = "
                    + MeasureTable.literal(measure.getName()) + " WHERE c.refresh_id IN (" + pending
                    + ") ORDER BY r.seq) TO " + MeasureTable.literal(file) + " (FORMAT parquet, COMPRESSION zstd,"
                    + " KV_METADATA {" + MeasureTable.METADATA_KEY + ": "
                    + MeasureTable.literal(measure.definitionJson(day)) + "})");
            contributions += Sql.queryLong(live, "SELECT count(*) FROM " + contributionTable(measure)
                    + " WHERE refresh_id IN (" + pending + ")", List.of());
            refreshes += Sql.executeUpdate(live, "INSERT INTO " + exportTable() + " SELECT "
                    + MeasureTable.literal(measure.getName()) + ", refresh_id, " + MeasureTable.literal(day) + ", "
                    + MeasureTable.literal(file) + ", now()::TIMESTAMP FROM (" + pending + ")", List.of());
            files.add(file);
        }
        return new Exported(files, refreshes, contributions);
    }

    /**
     * Folds a day's exported files into one, and deletes the originals.
     *
     * <p>Only days that are finished, so that a consumer reading files as they appear is never
     * disturbed. Measured: 18 ms for a day, and a day as 17 files was 737 KB and 18.6 ms to query
     * where one compacted file was 579 KB and 14.6 ms.</p>
     *
     * @return the file now holding the day, or null if there was nothing to do
     */
    public String compact(Connection live, MeasureTable measure, String location, String day) {
        if (location.contains("://")) {
            throw new IllegalArgumentException("Compaction deletes the files it replaces, which only works on a"
                    + " local path - compact there and upload the result");
        }
        Path directory = Path.of(location, "measure=" + measure.getName(), "part=" + safe(day));
        List<String> existing = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.toString().endsWith(".parquet")).map(Path::toString).sorted()
                    .forEach(existing::add);
        }
        catch (java.io.IOException noDirectory) {
            return null;
        }
        if (existing.size() < 2) {
            return null;
        }
        Path into = directory.resolve("compacting-" + System.currentTimeMillis() + ".parquet.tmp");
        Sql.execute(live, "COPY (SELECT * FROM " + MeasureTable.readParquet(existing) + " ORDER BY seq) TO "
                + MeasureTable.literal(into.toString()) + " (FORMAT parquet, COMPRESSION zstd, KV_METADATA {"
                + MeasureTable.METADATA_KEY + ": " + MeasureTable.literal(measure.definitionJson(day)) + "})");
        String file = fileFor(location, measure, day);
        try {
            Files.move(into, Path.of(file));
            for (String replaced : existing) {
                Files.delete(Path.of(replaced));
            }
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to put the compacted file in place of " + existing, e);
        }
        Sql.execute(live, "UPDATE " + exportTable() + " SET file = " + MeasureTable.literal(file) + " WHERE measure = "
                + MeasureTable.literal(measure.getName()) + " AND part = " + MeasureTable.literal(day));
        return file;
    }

    /**
     * Drops contributions the history has exported and no longer needs: everything before the last
     * full set.
     *
     * <p>Back to the last full set and no further, because that is what the live database can be
     * rebuilt from - and what reading as of any later point needs. A refresh that has not been
     * exported is never dropped.</p>
     *
     * @return how many contributions were dropped
     */
    public long prune(Connection live, MeasureTable measure) {
        attach(live);
        createExportLedger(live);
        String name = MeasureTable.literal(measure.getName());
        long dropped = Sql.executeUpdate(live, "DELETE FROM " + contributionTable(measure) + " WHERE refresh_id IN ("
                + " SELECT r.refresh_id FROM " + refreshTable() + " r WHERE r.measure = " + name
                + " AND r.seq < (SELECT coalesce(max(seq), 0) FROM " + refreshTable() + " WHERE measure = " + name
                + " AND kind = 'full') AND EXISTS (SELECT 1 FROM " + exportTable() + " e WHERE e.measure = " + name
                + " AND e.refresh_id = r.refresh_id))", List.of());
        if (dropped > 0) {
            Sql.execute(live, "CREATE TABLE IF NOT EXISTS " + prunedTable()
                    + " (measure VARCHAR NOT NULL PRIMARY KEY, seq BIGINT NOT NULL)");
            Sql.execute(live, "INSERT OR REPLACE INTO " + prunedTable() + " SELECT " + name
                    + ", coalesce(max(r.seq), 0) FROM " + refreshTable() + " r WHERE r.measure = " + name
                    + " AND r.seq < (SELECT coalesce(max(seq), 0) FROM " + refreshTable() + " WHERE measure = "
                    + name + " AND kind = 'full')");
        }
        try {
            // Until the history checkpoints, what was deleted still holds its blocks.
            Sql.execute(live, "CHECKPOINT " + Sql.quote(alias));
        }
        catch (RuntimeException busy) {
            // Someone is reading it; a later checkpoint releases the space instead.
        }
        return dropped;
    }

    /**
     * Refuses a point in time whose contributions this history has pruned, rather than answering with
     * what is left - which would be a wrong number that looks right.
     *
     * @param filesSql the exported files the query is also reading, which may hold it, or null
     */
    void checkNotPruned(Connection live, MeasureTable measure, Instant at, String filesSql) {
        if (Sql.queryLong(live, "SELECT count(*) FROM duckdb_tables() WHERE database_name = "
                + MeasureTable.literal(alias) + " AND table_name = " + MeasureTable.literal(PRUNED), List.of()) == 0) {
            return;
        }
        String name = MeasureTable.literal(measure.getName());
        String when = MeasureTable.literal(LocalDateTime.ofInstant(at, ZoneOffset.UTC).toString()) + "::TIMESTAMP";
        long baseline = Sql.queryLong(live, "SELECT coalesce(max(seq), 0) FROM " + refreshTable()
                + " WHERE measure = " + name + " AND kind = 'full' AND refreshed_at <= " + when, List.of());
        long pruned = Sql.queryLong(live, "SELECT coalesce(max(seq), 0) FROM " + prunedTable()
                + " WHERE measure = " + name, List.of());
        if (baseline > pruned || baseline == 0) {
            return;
        }
        // The files were written before anything was pruned, so the full set this point reads from
        // being in them means everything after it is too.
        if (filesSql != null && Sql.queryLong(live, "SELECT count(*) FROM " + filesSql + " WHERE seq = " + baseline,
                List.of()) > 0) {
            return;
        }
        throw new IllegalStateException(measure.getName() + " as of " + at + " has been pruned from the history"
                + (filesSql != null ? ", and the files given do not hold it either"
                : " - it is in the exported files: pass them to from(...) alongside asOf(...)"));
    }

    /** The files this history has written for a measure, oldest day first. */
    public List<String> exportedFiles(Connection live, MeasureTable measure) {
        attach(live);
        createExportLedger(live);
        List<String> files = new ArrayList<>();
        try (java.sql.PreparedStatement statement = live.prepareStatement("SELECT DISTINCT part, file FROM "
                + exportTable() + " WHERE measure = " + MeasureTable.literal(measure.getName())
                + " ORDER BY part, file");
             java.sql.ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                files.add(rows.getString(2));
            }
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read what has been exported", e);
        }
        return files;
    }

    // ---------- Rebuilding the live database ----------

    /**
     * Rebuilds a measure's state in the live database from the history: the sum of the contributions
     * from the last full set onwards, as of a point in time.
     *
     * <p>The history is the record; the live database is a cache of the current state, and this is how
     * it comes back - after a corruption, a bad deploy, or a definition that changed. Refuses while
     * anything is still waiting in the outbox, since that would be thrown away: ship it first.</p>
     *
     * @return how many values the measure now holds
     */
    public long rebuild(Connection live, MeasureTable measure, Instant at) {
        attach(live);
        long waiting = measure.contributionCount(live);
        if (waiting > 0) {
            throw new IllegalStateException(waiting + " contributions are still in " + measure.getName()
                    + "'s outbox; ship them before rebuilding, or they would be lost");
        }
        measure.create(live);
        String name = MeasureTable.literal(measure.getName());
        String when = MeasureTable.literal(LocalDateTime.ofInstant(at, ZoneOffset.UTC).toString()) + "::TIMESTAMP";
        String staging = Sql.quote(measure.getName() + "__rebuilding");
        boolean ownTransaction = Transactions.isAutoCommit(live);
        measure.lockWrites();
        try {
            if (ownTransaction) {
                Transactions.begin(live);
            }
            Sql.execute(live, "CREATE OR REPLACE TEMP TABLE " + staging + " AS WITH since AS ("
                    + " SELECT coalesce(max(seq), 0) AS seq FROM " + refreshTable() + " WHERE measure = " + name
                    + " AND kind = 'full' AND refreshed_at <= " + when + "), included AS (SELECT r.refresh_id FROM "
                    + refreshTable() + " r, since s WHERE r.measure = " + name + " AND r.refreshed_at <= " + when
                    + " AND r.seq >= s.seq) SELECT record_id, " + measure.fieldList(null) + ", sum(value) AS value"
                    + " FROM " + contributionTable(measure)
                    + " WHERE refresh_id IN (SELECT refresh_id FROM included) GROUP BY record_id, "
                    + measure.fieldList(null) + " HAVING sum(value) <> 0");
            Sql.execute(live, "DELETE FROM " + Sql.quote(measure.getValueTable()));
            Sql.execute(live, "INSERT INTO " + Sql.quote(measure.getKeyTable()) + " SELECT nextval('"
                    + measure.getName() + "_key_id'), s.* FROM (SELECT DISTINCT " + measure.fieldList(null)
                    + " FROM " + staging + ") s WHERE NOT EXISTS (SELECT 1 FROM "
                    + Sql.quote(measure.getKeyTable()) + " k WHERE " + measure.matchOn("k", "s") + ")");
            long values = Sql.executeUpdate(live, "INSERT INTO " + Sql.quote(measure.getValueTable())
                    + " SELECT f.record_id, k.id, f.value, " + MeasureTable.literal(MeasureTable.DEFAULT_PART) + ", "
                    + MeasureTable.literal(measure.getWrittenBy()) + ", now()::TIMESTAMP FROM " + staging
                    + " f JOIN " + Sql.quote(measure.getKeyTable()) + " k ON " + measure.matchOn("k", "f"), List.of());
            Sql.execute(live, "DROP TABLE IF EXISTS " + staging);
            if (ownTransaction) {
                Transactions.commit(live);
            }
            return values;
        }
        catch (RuntimeException e) {
            if (ownTransaction) {
                Transactions.rollbackQuietly(live);
            }
            throw new IllegalStateException("Failed to rebuild " + measure.getName() + " from " + location, e);
        }
        finally {
            measure.unlockWrites();
        }
    }

    /** Rebuilds the measure as it stands now. */
    public long rebuild(Connection live, MeasureTable measure) {
        return rebuild(live, measure, Instant.now().plusSeconds(1));
    }

    // ---------- Internals ----------

    /** The days of refresh that have contributions not yet exported, oldest first. */
    private List<String> pendingDays(Connection live, MeasureTable measure) {
        String name = MeasureTable.literal(measure.getName());
        List<String> days = new ArrayList<>();
        try (java.sql.PreparedStatement statement = live.prepareStatement("SELECT DISTINCT"
                + " strftime(r.refreshed_at, '%Y-%m-%d') AS day FROM " + refreshTable() + " r WHERE r.measure = "
                + name + " AND EXISTS (SELECT 1 FROM " + contributionTable(measure) + " c"
                + " WHERE c.refresh_id = r.refresh_id) AND NOT EXISTS (SELECT 1 FROM " + exportTable() + " e"
                + " WHERE e.measure = " + name + " AND e.refresh_id = r.refresh_id) ORDER BY day");
             java.sql.ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                days.add(rows.getString(1));
            }
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read what is waiting to be exported", e);
        }
        return days;
    }

    /** The refreshes of one day that have contributions not yet exported. */
    private String pending(MeasureTable measure, String day) {
        String name = MeasureTable.literal(measure.getName());
        return "SELECT r.refresh_id FROM " + refreshTable() + " r WHERE r.measure = " + name
                + " AND strftime(r.refreshed_at, '%Y-%m-%d') = " + MeasureTable.literal(day)
                + " AND NOT EXISTS (SELECT 1 FROM " + exportTable() + " e WHERE e.measure = " + name
                + " AND e.refresh_id = r.refresh_id)";
    }

    /** {@code <location>/measure=<name>/part=<day>/<writer>-<when>.parquet} */
    private String fileFor(String location, MeasureTable measure, String day) {
        String trimmed = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        String directory = trimmed + "/measure=" + measure.getName() + "/part=" + safe(day);
        if (!location.contains("://")) {
            try {
                Files.createDirectories(Path.of(directory));
            }
            catch (java.io.IOException e) {
                throw new IllegalStateException("Could not make the directory " + directory, e);
            }
        }
        return directory + "/" + safe(measure.getWrittenBy()) + "-" + System.currentTimeMillis() + ".parquet";
    }

    private static String safe(String text) {
        return text.isEmpty() ? "none" : text.replaceAll("[^A-Za-z0-9._=-]", "_");
    }

    private void createExportLedger(Connection live) {
        Sql.execute(live, "CREATE TABLE IF NOT EXISTS " + exportTable()
                + " (measure VARCHAR NOT NULL, refresh_id VARCHAR NOT NULL, part VARCHAR NOT NULL,"
                + " file VARCHAR NOT NULL, exported_at TIMESTAMP NOT NULL, PRIMARY KEY (measure, refresh_id))");
    }

    /** How far a measure has been pruned: reading as of a point at or before this is refused. */
    public String prunedTable() {
        return Sql.quote(alias) + "." + Sql.quote(PRUNED);
    }

    /** What has been exported, and to which file. */
    public String exportTable() {
        return Sql.quote(alias) + "." + Sql.quote("measure_export");
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
