package io.quackjvm.core.sparse;

import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;
import io.quackjvm.core.sql.Materialization;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Records that each have a few of thousands of possible numeric data points, stored the way that
 * suits DuckDB rather than the way they look on screen.
 *
 * <p>Drawn as a table, such data is thousands of columns, mostly empty. Stored that way it is the
 * worst layout there is: measured on ten million values across 5,000 possible data points, a wide
 * table was four times the disk, took two minutes to build, could not be built in one {@code PIVOT}
 * even with 16 GB, and took 109 ms to read a single row because every one of its 5,000 columns has
 * a fixed cost. So this stores it <b>long</b> - one row per value that exists - and shows it
 * <b>wide</b> only on demand, with only the columns a view asks for.</p>
 *
 * <p>Two tables, named after the table given here:</p>
 *
 * <pre>
 * name_attribute (id INTEGER PRIMARY KEY, name VARCHAR UNIQUE)   -- each data point's name, once
 * name_point     (record_id BIGINT, attr INTEGER, value DOUBLE)  -- one row per value that exists
 * </pre>
 *
 * <p>A value that is absent has no row, so nothing is stored for it; a data point never seen before
 * is one new row in the dictionary, not a schema change. Fields every record has belong in an
 * ordinary table of your own, joined on {@code record_id} - only the sparse part goes here.</p>
 *
 * <pre>
 * SparseTable samples = SparseTable.named("sample");
 * samples.create(connection);
 * samples.append(connection, SparseBatch.builder()
 *         .record(1).put("temperature", 21.5).put("pressure", 101.3)
 *         .record(2).put("temperature", 22.1).put("humidity", 40.0)
 *         .build());
 *
 * SparseRecord one = samples.read(connection, 1);
 * String sql = samples.viewSql(connection, List.of("temperature", "humidity"));
 * </pre>
 *
 * <h2>Transactions</h2>
 *
 * <p>A write is all or nothing: any new dictionary entries, the removal of replaced values and the
 * new values themselves commit together or not at all. When the connection is in auto-commit mode a
 * write runs in a transaction of its own; inside a transaction you have started, it joins yours and
 * leaves the commit to you. See {@link Transactions} for why a transaction of its own is begun and
 * ended with SQL rather than with {@code setAutoCommit} and {@code commit}.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Reads never block. Writes through one {@code SparseTable} instance are serialised with each
 * other and with {@link #optimize}: share one instance per table. Writes through different
 * instances are safe too - the dictionary is guarded by its own unique constraint, and
 * {@link #optimize} is written so that a concurrent write cannot be lost - but two transactions
 * touching the same rows make one of them fail with a conflict. Nothing is half-applied; the
 * failed one is rolled back and, when this class owns the transaction, retried. {@link #optimize}
 * touches every row, so through a second instance it conflicts with any {@link #replace} that
 * lands during it, and under steady writes it can run out of retries and throw.</p>
 */
public final class SparseTable {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Beyond this many values in one IN list, look the rows up through a temporary table. */
    private static final int IN_LIST_CHUNK = 1_000;
    private static final int ATTEMPTS = 3;
    /** More than a write gets: an optimize conflicts with every replace that lands during it. */
    private static final int OPTIMIZE_ATTEMPTS = 10;

    private final String name;
    private final String attributeTable;
    private final String pointTable;
    private final String sequence;
    private final ReentrantLock writeLock = new ReentrantLock(true);

    private SparseTable(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A sparse table name must be letters, digits and"
                    + " underscores, starting with a letter or underscore: " + name);
        }
        this.name = name;
        this.attributeTable = name + "_attribute";
        this.pointTable = name + "_point";
        this.sequence = name + "_attribute_id";
    }

    public static SparseTable named(String name) {
        return new SparseTable(name);
    }

    public String getName() {
        return name;
    }

    /** The dictionary table: {@code (id INTEGER, name VARCHAR)}. */
    public String getAttributeTable() {
        return attributeTable;
    }

    /** The values table: {@code (record_id BIGINT, attr INTEGER, value DOUBLE)}. */
    public String getPointTable() {
        return pointTable;
    }

    /** Creates the two tables and the id sequence if they are not there already. */
    public void create(Connection connection) {
        Sql.execute(connection, "CREATE SEQUENCE IF NOT EXISTS " + Sql.quote(sequence) + " START 0 MINVALUE 0");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(attributeTable)
                + " (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL UNIQUE)");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(pointTable)
                + " (record_id BIGINT NOT NULL, attr INTEGER NOT NULL, value DOUBLE)");
    }

    // ---------- Writing ----------

    /**
     * Adds the batch's values. Records already in the table keep what they had; the batch must not
     * repeat a data point a record already has, or that record ends up with two values for it -
     * use {@link #replace} when records may already be there.
     */
    public void append(Connection connection, SparseBatch batch) {
        write(connection, batch, false);
    }

    /**
     * Makes each record in the batch hold exactly the batch's values: whatever those records had
     * before is removed first. A record with no values in the batch is removed altogether.
     */
    public void replace(Connection connection, SparseBatch batch) {
        write(connection, batch, true);
    }

    private void write(Connection connection, SparseBatch batch, boolean replacing) {
        writeLock.lock();
        try {
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            for (int attempt = 1; ; attempt++) {
                try {
                    if (ownTransaction) {
                        Transactions.begin(connection);
                    }
                    int[] idOfName = resolveCreating(connection, batch.distinctNames());
                    if (replacing) {
                        deleteRecords(connection, batch.recordIds());
                    }
                    appendValues(connection, batch, idOfName);
                    if (ownTransaction) {
                        Transactions.commit(connection);
                    }
                    return;
                }
                catch (SQLException | RuntimeException e) {
                    if (ownTransaction) {
                        Transactions.rollbackQuietly(connection);
                    }
                    if (!ownTransaction || attempt == ATTEMPTS || !isConflict(e)) {
                        throw e instanceof RuntimeException re ? re
                                : new IllegalStateException("Failed to write " + batch.valueCount()
                                        + " values to " + name, e);
                    }
                    // Most often two writers adding the same new data point at once: the next
                    // attempt finds it in the dictionary.
                }
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    /** The dictionary id of each distinct name in the batch, adding any the dictionary lacks. */
    private int[] resolveCreating(Connection connection, List<String> names) throws SQLException {
        Map<String, Integer> ids = lookup(connection, names);
        if (ids.size() < names.size()) {
            List<String> missing = new ArrayList<>();
            for (String n : names) {
                if (!ids.containsKey(n)) {
                    missing.add(n);
                }
            }
            for (int from = 0; from < missing.size(); from += IN_LIST_CHUNK) {
                List<String> chunk = missing.subList(from, Math.min(missing.size(), from + IN_LIST_CHUNK));
                String sql = "INSERT INTO " + Sql.quote(attributeTable) + " SELECT nextval('" + sequence
                        + "'), name FROM (VALUES " + String.join(",", java.util.Collections.nCopies(chunk.size(), "(?)"))
                        + ") v(name) WHERE name NOT IN (SELECT name FROM " + Sql.quote(attributeTable) + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < chunk.size(); i++) {
                        statement.setString(i + 1, chunk.get(i));
                    }
                    statement.executeUpdate();
                }
            }
            ids = lookup(connection, names);
            if (ids.size() < names.size()) {
                throw new IllegalStateException("Could not add data points to the dictionary of " + name);
            }
        }
        int[] result = new int[names.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ids.get(names.get(i));
        }
        return result;
    }

    /** Names to ids, for those of the given names the dictionary has. */
    private Map<String, Integer> lookup(Connection connection, List<String> names) throws SQLException {
        Map<String, Integer> ids = new HashMap<>(names.size() * 2);
        for (int from = 0; from < names.size(); from += IN_LIST_CHUNK) {
            List<String> chunk = names.subList(from, Math.min(names.size(), from + IN_LIST_CHUNK));
            // Sql.placeholders includes its own parentheses.
            String sql = "SELECT name, id FROM " + Sql.quote(attributeTable) + " WHERE name IN "
                    + Sql.placeholders(chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.put(rows.getString(1), rows.getInt(2));
                    }
                }
            }
        }
        return ids;
    }

    private void deleteRecords(Connection connection, long[] recordIds) throws SQLException {
        if (recordIds.length == 0) {
            return;
        }
        if (recordIds.length <= IN_LIST_CHUNK) {
            String sql = "DELETE FROM " + Sql.quote(pointTable) + " WHERE record_id IN "
                    + Sql.placeholders(recordIds.length);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < recordIds.length; i++) {
                    statement.setLong(i + 1, recordIds[i]);
                }
                statement.executeUpdate();
            }
            return;
        }
        // Too many for an IN list: stage the ids in a temporary table and delete by semi-join.
        String staging = name + "__replacing";
        Sql.execute(connection, "CREATE TEMP TABLE IF NOT EXISTS " + Sql.quote(staging) + " (record_id BIGINT)");
        Sql.execute(connection, "DELETE FROM " + Sql.quote(staging));
        // Catalog, schema and table spelled out: the two-argument form reads "temp" as a schema and
        // fails.
        try (DuckDBAppender appender = Connections.duckDB(connection)
                .createAppender("temp", "main", staging)) {
            for (long id : recordIds) {
                appender.beginRow();
                appender.append(id);
                appender.endRow();
            }
            appender.flush();   // see appendValues
        }
        Sql.execute(connection, "DELETE FROM " + Sql.quote(pointTable) + " WHERE record_id IN (SELECT record_id FROM "
                + Sql.quote(staging) + ")");
    }

    private void appendValues(Connection connection, SparseBatch batch, int[] idOfName) throws SQLException {
        if (batch.valueCount() == 0) {
            return;
        }
        DuckDBConnection duckDB = Connections.duckDB(connection);
        try (DuckDBAppender appender = duckDB.createAppender(DuckDBConnection.DEFAULT_SCHEMA, pointTable)) {
            for (int record = 0; record < batch.recordCount(); record++) {
                long recordId = batch.recordId(record);
                for (int position = batch.valueStart(record); position < batch.valueEnd(record); position++) {
                    appender.beginRow();
                    appender.append(recordId);
                    appender.append(idOfName[batch.nameIndex(position)]);
                    appender.append(batch.value(position));
                    appender.endRow();
                }
            }
            // Explicit, never left to close(): DuckDB's Appender swallows a failed flush on close and
            // discards the rows without an error, which would let this transaction commit a write
            // that silently did not happen.
            appender.flush();
        }
    }

    // ---------- Reading ----------

    /** Everything one record has, by name. Empty if the table holds nothing for it. */
    public SparseRecord read(Connection connection, long recordId) {
        String sql = "SELECT a.name, p.value FROM " + Sql.quote(pointTable) + " p JOIN "
                + Sql.quote(attributeTable) + " a ON a.id = p.attr WHERE p.record_id = ? ORDER BY a.name";
        List<String> names = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recordId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                    values.add(rows.getDouble(2));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read record " + recordId + " of " + name, e);
        }
        double[] array = new double[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return new SparseRecord(recordId, names.toArray(new String[0]), array);
    }

    /**
     * SQL for a wide view of the chosen data points: {@code record_id}, then one {@code DOUBLE}
     * column per name, in the order given, NULL where a record lacks that data point.
     *
     * <p>Only records with at least one of the chosen data points appear. To keep every record,
     * {@code LEFT JOIN} this onto your own table of records. A name the dictionary has never seen
     * gives a column of NULLs rather than an error, so a view can be defined before its data
     * arrives - but the id is looked up now, so a view built before a data point exists stays NULL
     * for it until the SQL is built again.</p>
     *
     * <p>Names become column headers here and nowhere else; the query itself works on ids, so a
     * name is never attached to ten million rows.</p>
     */
    public String viewSql(Connection connection, List<String> names) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) {
                throw new IllegalArgumentException("'" + n + "' is asked for twice");
            }
        }
        Map<String, Integer> ids;
        try {
            ids = lookup(connection, names);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to look up data points of " + name, e);
        }
        StringBuilder columns = new StringBuilder("record_id");
        StringBuilder known = new StringBuilder();
        for (String n : names) {
            Integer id = ids.get(n);
            columns.append(", ");
            if (id == null) {
                columns.append("CAST(NULL AS DOUBLE)");
            }
            else {
                // One row per (record, data point), so any aggregate gives that row's value.
                columns.append("max(value) FILTER (WHERE attr = ").append(id).append(')');
                known.append(known.length() == 0 ? "" : ",").append(id);
            }
            columns.append(" AS ").append(Sql.quote(n));
        }
        return "SELECT " + columns + " FROM " + Sql.quote(pointTable)
                + " WHERE " + (known.length() == 0 ? "false" : "attr IN (" + known + ")")
                + " GROUP BY record_id";
    }

    /**
     * The wide view of the chosen data points as {@link Rows}. The connection is used to look the
     * names up and then handed to {@code Rows}, which - as always - owns and closes it.
     */
    public Rows view(Connection ownedConnection, List<String> names) {
        return Rows.of(ownedConnection, viewSql(ownedConnection, names));
    }

    /**
     * A {@link Materialization} of the wide view of the chosen data points, built if absent: the
     * data points your screens read all the time, as a real table of just those columns, refreshed
     * without readers ever seeing it missing. Measured on ten million values, ten hot data points
     * materialised this way served a table view faster than a full 5,000-column table.
     */
    public Materialization materializeWide(Connection connection, String tableName, List<String> names) {
        Materialization materialization = new Materialization(tableName, viewSql(connection, names));
        materialization.createIfAbsent(connection);
        return materialization;
    }

    // ---------- Housekeeping ----------

    /**
     * Rewrites the values ordered by data point, so that a query about one data point reads only
     * that data point's blocks - measured at 0.26 ms against 1.94 on ten million values. Fetching
     * one whole record gets slower in exchange, 0.24 ms to 2.3, since its values are then spread
     * across the table. Values written afterwards land at the end, unordered, so the benefit
     * decays as the table grows; call this after a large load rather than routinely.
     *
     * <p>Done by deleting every row and inserting them again in order, inside one transaction -
     * <b>not</b> by building a sorted copy and swapping it in. The swap is the obvious way and it
     * loses data: a write that commits between building the copy and dropping the original
     * disappears, and DuckDB reports no error, even with both steps in one transaction. Deleting in
     * place only removes rows this transaction can see, so a concurrent write survives. The file
     * can grow to twice the table's size while the old rows are awaiting reuse; it does not keep
     * growing.</p>
     *
     * <p>A {@link #replace} through another instance that lands during it makes it fail with a
     * conflict and roll back; it retries up to ten times, then throws. Run it through the instance
     * your writers use and they simply wait for it instead.</p>
     */
    public void optimize(Connection connection) {
        writeLock.lock();
        String sorted = name + "__sorted";
        try {
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            for (int attempt = 1; ; attempt++) {
                try {
                    if (ownTransaction) {
                        Transactions.begin(connection);
                    }
                    Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(sorted) + " AS SELECT * FROM "
                            + Sql.quote(pointTable) + " ORDER BY attr, record_id");
                    Sql.execute(connection, "DELETE FROM " + Sql.quote(pointTable));
                    Sql.execute(connection, "INSERT INTO " + Sql.quote(pointTable) + " SELECT * FROM " + Sql.quote(sorted));
                    if (ownTransaction) {
                        Transactions.commit(connection);
                    }
                    break;
                }
                catch (RuntimeException e) {
                    if (ownTransaction) {
                        Transactions.rollbackQuietly(connection);
                    }
                    // Deleting every row conflicts with any write through another instance that
                    // deletes rows at the same moment - a replace. Nothing was changed, so trying
                    // again is safe; a pause gives that write time to finish.
                    if (!ownTransaction || attempt == OPTIMIZE_ATTEMPTS || !isConflict(e)) {
                        throw new IllegalStateException("Failed to optimize " + name
                                + (attempt > 1 ? " after " + attempt + " attempts" : ""), e);
                    }
                    pause(attempt);
                }
            }
            if (ownTransaction) {
                Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(sorted));
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 10L * attempt + 1));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying", e);
        }
    }

    public long valueCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(pointTable), List.of());
    }

    public long recordCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(DISTINCT record_id) FROM " + Sql.quote(pointTable), List.of());
    }

    /** How many distinct data points have ever been written. */
    public long attributeCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(attributeTable), List.of());
    }

    // ---------- Helpers ----------

    /** Conflicts between concurrent transactions are worth one more try; nothing else is. */
    private static boolean isConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("conflict") || message.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "SparseTable[" + name + "]";
    }
}
