package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Values that each belong to a record and are identified by several fields, stored sparsely - a row
 * per value that exists - and read back grouped or pivoted by any of those fields.
 *
 * <pre>
 * MeasureTable measures = MeasureTable.named("measures")
 *         .fields("a", "b", "point", "unit")
 *         .unit("unit")
 *         .writtenBy("svc-a")
 *         .build();
 * measures.create(connection);
 *
 * measures.append(connection, measures.batch()
 *         .record(42).put(1.25, "x", "y", "5y", "U1").build(), "2026-09-20");
 *
 * measures.query().rows("a").columns("point").where("unit", "U1").run(connection.duplicate());
 * </pre>
 *
 * <p>Two tables: a dictionary holding each distinct key once, with a column per field, and a values
 * table of {@code (record_id, key_id, value, part, written_by, written_at)}. The dictionary stays
 * small - thousands of rows where the values table holds millions - which is what makes grouping by
 * any field cheap.</p>
 *
 * <h2>Parts</h2>
 *
 * <p>Every write carries a part - a label, usually a day. A part is the unit of archiving: it can be
 * written out as Parquet and removed from the live tables, then read back, or queried where it lies,
 * one part or many at once. Writes also carry who wrote them and when, at a measured cost of 1.7%,
 * so that a reader can decide what to do when two writers touched the same record.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Reads never block. Writes through one instance are serialised with each other; writes through
 * different instances are safe, though two touching the same records can make one fail with a
 * conflict, which is retried when this class owns the transaction.</p>
 */
public final class MeasureTable {

    /** Where the definition lives inside an archived Parquet file. */
    public static final String METADATA_KEY = "quackjvm_measure";
    /** The part a write belongs to when none is given. */
    public static final String DEFAULT_PART = "";

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int IN_LIST_CHUNK = 1_000;
    private static final int ATTEMPTS = 3;
    /** Values written between two checks of whether the measure is due to be archived. */
    private static final long CHECK_EVERY = 50_000;
    /** How many keys one instance remembers the dictionary id of. */
    private static final int KEY_CACHE_LIMIT = 200_000;

    private final String name;
    private final List<String> fields;
    /** Per field, what values written before it existed should read as. */
    private final Map<String, String> defaults;
    private final String unitField;
    private final Map<String, List<String>> orders;
    private final String writtenBy;
    private final long archiveOverBytes;
    private final String archiveLocation;
    private final ArchiveHandler archiveHandler;
    private final String keyTable;
    private final String valueTable;
    private final String contributionTable;
    private final String sequence;
    private final ReentrantLock writeLock = new ReentrantLock(true);
    /** Key fields to dictionary id, for keys this instance has looked up. Guarded by writeLock. */
    private final Map<List<String>, Integer> keyIds = new KeyCache();
    private long writtenSinceCheck;
    /** Whether the tables have been checked against this definition. Guarded by writeLock. */
    private boolean checkedColumns;

    /** The most recently used keys, bounded so that a measure with millions cannot fill the heap. */
    private static final class KeyCache extends LinkedHashMap<List<String>, Integer> {

        private static final long serialVersionUID = 1L;

        private KeyCache() {
            super(1_024, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<List<String>, Integer> eldest) {
            return size() > KEY_CACHE_LIMIT;
        }
    }

    /** Told about each part as it is archived, so its file can be moved somewhere else. */
    @FunctionalInterface
    public interface ArchiveHandler {
        void archived(String part, String location) throws Exception;
    }

    /** A part of a measure, as it stands in the live tables. */
    public record Part(String name, long values) {
    }

    private MeasureTable(Builder builder) {
        this.name = builder.name;
        this.fields = List.copyOf(builder.fields);
        this.defaults = Map.copyOf(builder.defaults);
        this.unitField = builder.unitField;
        this.orders = Map.copyOf(builder.orders);
        this.writtenBy = builder.writtenBy;
        this.archiveOverBytes = builder.archiveOverBytes;
        this.archiveLocation = builder.archiveLocation;
        this.archiveHandler = builder.archiveHandler;
        this.keyTable = name + "_key";
        this.valueTable = name + "_value";
        this.contributionTable = name + "_contribution";
        this.sequence = name + "_key_id";
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private final List<String> fields = new ArrayList<>();
        private final Map<String, String> defaults = new HashMap<>();
        private String unitField;
        private final Map<String, List<String>> orders = new LinkedHashMap<>();
        private String writtenBy = "unknown";
        private long archiveOverBytes;
        private String archiveLocation;
        private ArchiveHandler archiveHandler;

        private Builder(String name) {
            check(name, "A measure name");
            this.name = name;
        }

        /** The fields that identify a value, in the order {@code put(...)} takes them. */
        public Builder fields(String... fields) {
            for (String field : fields) {
                field(field);
            }
            return this;
        }

        /** One more field, required of every value written from now on. */
        public Builder field(String field) {
            check(field, "A field name");
            if (List.of("record_id", "key_id", "value", "part", "written_by", "written_at").contains(field)) {
                throw new IllegalArgumentException("'" + field + "' is a column of the values table;"
                        + " a field cannot be called that");
            }
            if (fields.contains(field)) {
                throw new IllegalArgumentException("A field is named twice: " + field);
            }
            fields.add(field);
            return this;
        }

        /**
         * A field added to a measure that already holds values, with what those older values should
         * read as. Archived files written before the field existed take this too.
         */
        public Builder field(String field, String valueBeforeItExisted) {
            field(field);
            defaults.put(field, valueBeforeItExisted);
            return this;
        }

        /**
         * Which field says what unit a value is expressed in. Totals then refuse to add values in
         * different units unless the query converts them or narrows to one - see
         * {@link MeasureQuery#convertTo}.
         */
        public Builder unit(String field) {
            this.unitField = field;
            return this;
        }

        /**
         * The order a field's values come out in, as rows or as columns. Without this they come out
         * in text order, which puts "10y" before "1d". Values not named come last, in text order.
         */
        public Builder order(String field, String... values) {
            orders.put(field, List.of(values));
            return this;
        }

        /**
         * Who this instance is, written with every value. With several services writing one measure,
         * give each its own name: a reader can then tell their values apart.
         */
        public Builder writtenBy(String writer) {
            this.writtenBy = writer;
            return this;
        }

        /**
         * Archive the oldest parts whenever the measure's own tables grow past this many bytes.
         * Needs somewhere for the files to go: {@link #archiveTo}, and usually {@link #onArchive}.
         */
        public Builder archiveWhenLargerThan(long bytes) {
            this.archiveOverBytes = bytes;
            return this;
        }

        /** Where archived parts are written: a directory, or anything DuckDB can write to. */
        public Builder archiveTo(String location) {
            this.archiveLocation = location;
            return this;
        }

        /**
         * Called once per archived part, with where its file was written - to move it into a store
         * of your own. Runs on the thread that did the write, after that write has committed.
         */
        public Builder onArchive(ArchiveHandler handler) {
            this.archiveHandler = handler;
            return this;
        }

        public MeasureTable build() {
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("A measure needs at least one field");
            }
            if (unitField != null && !fields.contains(unitField)) {
                throw new IllegalArgumentException("The unit field '" + unitField + "' is not one of " + fields);
            }
            for (String field : orders.keySet()) {
                if (!fields.contains(field)) {
                    throw new IllegalArgumentException("Cannot order '" + field + "', which is not one of " + fields);
                }
            }
            if (archiveOverBytes > 0 && archiveLocation == null) {
                throw new IllegalArgumentException("archiveWhenLargerThan needs archiveTo(location)");
            }
            return new MeasureTable(this);
        }

        private static void check(String word, String what) {
            if (word == null || !NAME.matcher(word).matches()) {
                throw new IllegalArgumentException(what + " must be letters, digits and underscores,"
                        + " starting with a letter or underscore: " + word);
            }
        }
    }

    public String getName() {
        return name;
    }

    /** The fields that identify a value, in the order {@code put(...)} takes them. */
    public List<String> getFields() {
        return fields;
    }

    /** What values written before a field existed read as, or null if the field has no default. */
    public String defaultOf(String field) {
        return defaults.get(field);
    }

    /** The declared order of a field's values, or empty if they come out in text order. */
    public List<String> orderOf(String field) {
        return orders.getOrDefault(field, List.of());
    }

    /** Which field says what unit a value is in, or null if the measure has no units. */
    public String getUnitField() {
        return unitField;
    }

    public String getWrittenBy() {
        return writtenBy;
    }

    /** The dictionary table: {@code (id INTEGER, <field> VARCHAR, ...)}. */
    public String getKeyTable() {
        return keyTable;
    }

    /** The values table: {@code (record_id, key_id, value, part, written_by, written_at)}. */
    public String getValueTable() {
        return valueTable;
    }

    /**
     * The contributions written by refreshes: {@code (refresh_id, record_id, key_id, value)}, where
     * the value is how much that refresh moved the key by. Nothing here is ever changed once
     * written - see {@link MeasureRefresh}.
     */
    public String getContributionTable() {
        return contributionTable;
    }

    public MeasureBatch.Builder batch() {
        return new MeasureBatch.Builder(fields);
    }

    /** Creates the two tables and the id sequence if they are not there already. */
    public void create(Connection connection) {
        Sql.execute(connection, "CREATE SEQUENCE IF NOT EXISTS " + Sql.quote(sequence) + " START 0 MINVALUE 0");
        StringBuilder columns = new StringBuilder("id INTEGER PRIMARY KEY");
        for (String field : fields) {
            columns.append(", ").append(Sql.quote(field)).append(" VARCHAR NOT NULL");
        }
        columns.append(", UNIQUE (").append(fieldList(null)).append(')');
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(keyTable) + " (" + columns + ")");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(valueTable)
                + " (record_id BIGINT NOT NULL, key_id INTEGER NOT NULL, value DOUBLE,"
                + " part VARCHAR NOT NULL, written_by VARCHAR NOT NULL, written_at TIMESTAMP NOT NULL)");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(contributionTable)
                + " (refresh_id VARCHAR NOT NULL, record_id BIGINT NOT NULL, key_id INTEGER NOT NULL,"
                + " value DOUBLE NOT NULL)");
    }

    /**
     * Brings tables made by an older definition up to this one: fields added since appear in the
     * dictionary, holding the default given for each, and what makes a key unique is rebuilt to
     * include them.
     *
     * <p>Refuses anything that would lose values - a field the tables have and this definition does
     * not - rather than guessing. Does nothing when they already agree.</p>
     */
    public void migrate(Connection connection) {
        create(connection);
        List<String> existing = new ArrayList<>(columnsOfQuery(connection, Sql.quote(keyTable)));
        existing.remove("id");
        if (existing.equals(fields)) {
            return;
        }
        List<String> lost = new ArrayList<>(existing);
        lost.removeAll(fields);
        if (!lost.isEmpty()) {
            throw new IllegalStateException(name + " already holds " + lost + ", which this definition does not."
                    + " Dropping a field would add together values that differ only by it; restore into a new"
                    + " measure instead.");
        }
        for (String field : fields) {
            if (!existing.contains(field) && !defaults.containsKey(field)) {
                throw new IllegalStateException("'" + field + "' is new, so values written before it existed need"
                        + " something to read as: field(\"" + field + "\", \"...\")");
            }
        }
        writeLock.lock();
        try {
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            try {
                if (ownTransaction) {
                    Transactions.begin(connection);
                }
                // The dictionary is rebuilt rather than altered: what makes a key unique has changed.
                String rebuilt = keyTable + "__migrating";
                StringBuilder columns = new StringBuilder("id INTEGER PRIMARY KEY");
                for (String field : fields) {
                    columns.append(", ").append(Sql.quote(field)).append(" VARCHAR NOT NULL");
                }
                columns.append(", UNIQUE (").append(fieldList(null)).append(')');
                Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(rebuilt));
                Sql.execute(connection, "CREATE TABLE " + Sql.quote(rebuilt) + " (" + columns + ")");
                StringBuilder select = new StringBuilder("SELECT id");
                for (String field : fields) {
                    select.append(", ").append(existing.contains(field) ? Sql.quote(field)
                            : literal(defaults.get(field)) + " AS " + Sql.quote(field));
                }
                Sql.execute(connection, "INSERT INTO " + Sql.quote(rebuilt) + " " + select + " FROM "
                        + Sql.quote(keyTable));
                Sql.execute(connection, "DROP TABLE " + Sql.quote(keyTable));
                Sql.execute(connection, "ALTER TABLE " + Sql.quote(rebuilt) + " RENAME TO " + Sql.quote(keyTable));
                checkedColumns = false;
                keyIds.clear();
                if (ownTransaction) {
                    Transactions.commit(connection);
                }
            }
            catch (RuntimeException e) {
                if (ownTransaction) {
                    Transactions.rollbackQuietly(connection);
                }
                throw new IllegalStateException("Failed to bring " + name + " up to " + fields, e);
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    /** Starts a query. Any field can be rows, any field can be the columns. */
    public MeasureQuery query() {
        return new MeasureQuery(this);
    }

    public long valueCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(valueTable), List.of());
    }

    public long recordCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(DISTINCT record_id) FROM " + Sql.quote(valueTable), List.of());
    }

    public long keyCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(keyTable), List.of());
    }

    /** The parts the live tables hold, oldest label first, with how many values each holds. */
    public List<Part> parts(Connection connection) {
        List<Part> parts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT part, count(*) FROM "
                + Sql.quote(valueTable) + " GROUP BY part ORDER BY part");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                parts.add(new Part(rows.getString(1), rows.getLong(2)));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the parts of " + name, e);
        }
        return parts;
    }

    /**
     * How much disk this measure's own two tables use, in bytes - not the whole database file. Zero
     * for a database held in memory, which has no blocks.
     */
    public long sizeOnDisk(Connection connection) {
        return blocksOf(connection, keyTable) + blocksOf(connection, valueTable);
    }

    private long blocksOf(Connection connection, String table) {
        try {
            return Sql.queryLong(connection, "SELECT coalesce(count(DISTINCT block_id), 0)"
                    + " * coalesce((SELECT block_size FROM pragma_database_size()), 0)"
                    + " FROM pragma_storage_info(" + literal(table) + ") WHERE block_id IS NOT NULL", List.of());
        }
        catch (RuntimeException notPersistent) {
            return 0;
        }
    }

    void checkField(String field) {
        if (!fields.contains(field)) {
            throw new IllegalArgumentException("'" + field + "' is not a field of " + name + ": " + fields);
        }
    }

    // ---------- Writing ----------

    /** Adds the batch's values, under the default part. */
    public void append(Connection connection, MeasureBatch batch) {
        append(connection, batch, DEFAULT_PART);
    }

    /**
     * Adds the batch's values under the given part. Records already in the table keep what they had;
     * the batch must not repeat a key a record already has - use {@link #replace} for that.
     */
    public void append(Connection connection, MeasureBatch batch, String part) {
        write(connection, batch, part, false, false);
    }

    /**
     * Adds only the values that differ from what this measure already holds: one the same as the
     * stored value is not written again.
     *
     * <p>Measured on two thousand records of a thousand values each, of which 5% had changed:
     * working out the difference took 36 ms for the whole batch and left 95% of the rows unwritten,
     * which is repaid on every later read, archive and restore. It reads before it writes, so it
     * suits one writer per record.</p>
     */
    public void appendChanges(Connection connection, MeasureBatch batch, String part) {
        write(connection, batch, part, false, true);
    }

    /** Makes each record in the batch hold exactly the batch's values, under the default part. */
    public void replace(Connection connection, MeasureBatch batch) {
        replace(connection, batch, DEFAULT_PART);
    }

    /**
     * Makes each record in the batch hold exactly the batch's values: whatever those records had
     * before is removed first, whichever part it was written under. A record with no values in the
     * batch is removed altogether.
     */
    public void replace(Connection connection, MeasureBatch batch, String part) {
        write(connection, batch, part, true, false);
    }

    private void write(Connection connection, MeasureBatch batch, String part, boolean replacing,
                       boolean onlyChanges) {
        if (!batch.getFields().equals(fields)) {
            throw new IllegalArgumentException("That batch is for the key " + batch.getFields() + ", not " + fields);
        }
        if (part == null) {
            throw new IllegalArgumentException("A part is a label, not null; DEFAULT_PART means none");
        }
        writeLock.lock();
        try {
            ensureAgreesWithTables(connection);
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            for (int attempt = 1; ; attempt++) {
                try {
                    if (ownTransaction) {
                        Transactions.begin(connection);
                    }
                    int[] idOfKey = resolveCreating(connection, batch);
                    if (replacing) {
                        deleteRecords(connection, batch.recordIds());
                    }
                    appendValues(connection, batch, idOfKey, part, onlyChanges);
                    if (ownTransaction) {
                        Transactions.commit(connection);
                    }
                    break;
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
                    // Most often two writers adding the same new key at once: the next attempt
                    // finds it in the dictionary.
                }
            }
        }
        finally {
            writeLock.unlock();
        }
        archiveIfTooLarge(connection, batch.valueCount());
    }

    // ---------- Refreshes ----------

    /**
     * Applies one measure's share of a refresh, inside the caller's transaction: works out what each
     * value moved by, writes those contributions, and leaves the table holding the new state.
     *
     * <p>Records in the batch replace themselves entirely, whichever kind this is - a key a record
     * held and the batch does not is cancelled. Records in {@code removed} are cancelled whether the
     * batch names them or not, so that what was published stays in the contributions.</p>
     *
     * <p>A full set is different in kind: it is a baseline, so it contributes the values themselves
     * rather than what they moved by, and whatever it does not name is simply not in it. Reading as of
     * a later point therefore starts at the last full set and needs nothing older - which is also what
     * makes everything older safe to throw away.</p>
     *
     * @return how many contributions were written - values that actually moved
     */
    long refresh(Connection connection, MeasureBatch batch, boolean full, long[] removed, String refreshId,
                 LocalDateTime at) throws SQLException {
        if (!batch.getFields().equals(fields)) {
            throw new IllegalArgumentException("That batch is for the key " + batch.getFields() + ", not " + fields);
        }
        ensureAgreesWithTables(connection);
        int[] idOfKey = resolveCreating(connection, batch);
        String incoming = Sql.quote(name + "__refreshing");
        Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + incoming
                + " (record_id BIGINT, key_id INTEGER, value DOUBLE)");
        if (batch.valueCount() > 0) {
            try (DuckDBAppender appender = Connections.duckDB(connection)
                    .createAppender("temp", "main", name + "__refreshing")) {
                for (int record = 0; record < batch.recordCount(); record++) {
                    long recordId = batch.recordId(record);
                    for (int position = batch.valueStart(record); position < batch.valueEnd(record); position++) {
                        appender.beginRow();
                        appender.append(recordId);
                        appender.append(idOfKey[batch.keyOfValue(position)]);
                        appender.append(batch.value(position));
                        appender.endRow();
                    }
                }
                appender.flush();
            }
        }
        long moved;
        String scope = "";
        if (full) {
            // A full set is a baseline, so it contributes the values themselves rather than what they
            // moved by. That is what lets everything before it be thrown away: reading as of a later
            // point starts from the last full set and needs nothing older. A key the set does not
            // name is simply absent from it, which is how it comes to be gone.
            moved = Sql.executeUpdate(connection, "INSERT INTO " + Sql.quote(contributionTable)
                    + " SELECT " + literal(refreshId) + ", record_id, key_id, value FROM " + incoming, List.of());
        }
        else {
            scope = " WHERE record_id IN (SELECT record_id FROM " + touched(connection, batch, removed) + ")";
            // A full outer join, so that a key only the batch has and a key only the table has both
            // become contributions - one added, one cancelled.
            moved = Sql.executeUpdate(connection, "INSERT INTO " + Sql.quote(contributionTable)
                    + " SELECT " + literal(refreshId) + ", coalesce(i.record_id, v.record_id),"
                    + " coalesce(i.key_id, v.key_id), coalesce(i.value, 0) - coalesce(v.value, 0)"
                    + " FROM " + incoming + " i FULL OUTER JOIN (SELECT record_id, key_id, value FROM "
                    + Sql.quote(valueTable) + scope + ") v ON v.record_id = i.record_id AND v.key_id = i.key_id"
                    + " WHERE coalesce(i.value, 0) IS DISTINCT FROM coalesce(v.value, 0)", List.of());
        }
        Sql.execute(connection, "DELETE FROM " + Sql.quote(valueTable) + (full ? "" : scope));
        if (batch.valueCount() > 0) {
            Sql.execute(connection, "INSERT INTO " + Sql.quote(valueTable) + " SELECT record_id, key_id, value, "
                    + literal(DEFAULT_PART) + ", " + literal(writtenBy) + ", " + literal(at.toString())
                    + "::TIMESTAMP FROM " + incoming);
        }
        Sql.execute(connection, "DROP TABLE IF EXISTS " + incoming);
        return moved;
    }

    /** The records this refresh touches: those it publishes, and those it removes. */
    private String touched(Connection connection, MeasureBatch batch, long[] removed) throws SQLException {
        String table = name + "__touched";
        Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(table) + " (record_id BIGINT)");
        try (DuckDBAppender appender = Connections.duckDB(connection).createAppender("temp", "main", table)) {
            for (int record = 0; record < batch.recordCount(); record++) {
                appender.beginRow();
                appender.append(batch.recordId(record));
                appender.endRow();
            }
            for (long recordId : removed) {
                appender.beginRow();
                appender.append(recordId);
                appender.endRow();
            }
            appender.flush();
        }
        return Sql.quote(table);
    }

    /**
     * Refuses to write through a definition the tables disagree with - a field added or renamed since
     * they were made. Checked once per instance, on its first write, at the cost of one DESCRIBE.
     *
     * <p>Without this the disagreement is silent: a key resolved by the fields the definition does
     * have would match some other key that happens to share them, and values would pile onto it.</p>
     */
    private void ensureAgreesWithTables(Connection connection) {
        if (checkedColumns) {
            return;
        }
        List<String> existing;
        try {
            existing = new ArrayList<>(columnsOfQuery(connection, Sql.quote(keyTable)));
        }
        catch (RuntimeException notThereYet) {
            // No tables to disagree with; the write itself says what is missing.
            return;
        }
        existing.remove("id");
        if (!existing.equals(fields)) {
            throw new IllegalStateException(keyTable + " holds " + existing + ", but this definition has " + fields
                    + " - call migrate(connection) to bring the tables up to it, or restore into a new measure");
        }
        checkedColumns = true;
    }

    /** Locks this measure against other writers through this instance. */
    void lockWrites() {
        writeLock.lock();
    }

    void unlockWrites() {
        writeLock.unlock();
    }

    /** How many contributions have been written to this measure, over all refreshes. */
    public long contributionCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(contributionTable), List.of());
    }

    /**
     * The dictionary id of each distinct key in the batch, adding any the dictionary lacks.
     *
     * <p>Ids never change, so those already looked up are remembered: a publisher writing the same
     * keys over and over then does no SQL at all for them, which was measured at 2.1 ms of a 6.5 ms
     * refresh of 250 keys. Only keys the dictionary already had are remembered - one this call is
     * adding would be a lie if the transaction rolled back.</p>
     */
    private int[] resolveCreating(Connection connection, MeasureBatch batch) throws SQLException {
        int[] result = new int[batch.keyCount()];
        List<List<String>> unknown = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int key = 0; key < batch.keyCount(); key++) {
            List<String> parts = new ArrayList<>(fields.size());
            for (int field = 0; field < fields.size(); field++) {
                parts.add(batch.keyPart(key, field));
            }
            Integer known = keyIds.get(parts);
            if (known != null) {
                result[key] = known;
            }
            else {
                unknown.add(parts);
                positions.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return result;
        }
        String staging = name + "__keys";
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            columns.append(i == 0 ? "" : ", ").append(Sql.quote(fields.get(i))).append(" VARCHAR");
        }
        Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(staging) + " (" + columns + ")");
        try (DuckDBAppender appender = Connections.duckDB(connection).createAppender("temp", "main", staging)) {
            for (List<String> parts : unknown) {
                appender.beginRow();
                for (String part : parts) {
                    appender.append(part);
                }
                appender.endRow();
            }
            // Explicit, never left to close(): DuckDB's Appender swallows a failed flush on close.
            appender.flush();
        }
        Map<List<String>, Integer> ids = idsOf(connection, staging);
        // These the dictionary had before this call, so they are safe to remember.
        keyIds.putAll(ids);
        if (ids.size() < unknown.size()) {
            Sql.execute(connection, "INSERT INTO " + Sql.quote(keyTable) + " SELECT nextval('" + sequence + "'), s.*"
                    + " FROM " + Sql.quote(staging) + " s WHERE NOT EXISTS (SELECT 1 FROM " + Sql.quote(keyTable)
                    + " k WHERE " + matchOn("k", "s") + ")");
            ids = idsOf(connection, staging);
        }
        for (int i = 0; i < unknown.size(); i++) {
            Integer id = ids.get(unknown.get(i));
            if (id == null) {
                throw new IllegalStateException("Could not add a key to the dictionary of " + name + ": "
                        + unknown.get(i));
            }
            result[positions.get(i)] = id;
        }
        return result;
    }

    /** The dictionary ids of the keys staged in a temp table, by their fields. */
    private Map<List<String>, Integer> idsOf(Connection connection, String staging) throws SQLException {
        StringBuilder select = new StringBuilder("SELECT k.id");
        for (String field : fields) {
            select.append(", k.").append(Sql.quote(field));
        }
        Map<List<String>, Integer> ids = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(select + " FROM " + Sql.quote(keyTable)
                + " k JOIN " + Sql.quote(staging) + " s ON " + matchOn("k", "s"));
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                List<String> parts = new ArrayList<>(fields.size());
                for (int field = 0; field < fields.size(); field++) {
                    parts.add(rows.getString(field + 2));
                }
                ids.put(parts, rows.getInt(1));
            }
        }
        return ids;
    }

    /** {@code k."a" = s."a" AND ...}, the join between the dictionary and another set of keys. */
    String matchOn(String left, String right) {
        StringBuilder match = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            match.append(i == 0 ? "" : " AND ").append(left).append('.').append(Sql.quote(fields.get(i)))
                    .append(" = ").append(right).append('.').append(Sql.quote(fields.get(i)));
        }
        return match.toString();
    }

    private void deleteRecords(Connection connection, long[] recordIds) throws SQLException {
        if (recordIds.length == 0) {
            return;
        }
        if (recordIds.length <= IN_LIST_CHUNK) {
            String sql = "DELETE FROM " + Sql.quote(valueTable) + " WHERE record_id IN "
                    + Sql.placeholders(recordIds.length);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < recordIds.length; i++) {
                    statement.setLong(i + 1, recordIds[i]);
                }
                statement.executeUpdate();
            }
            return;
        }
        String staging = name + "__replacing";
        Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(staging) + " (record_id BIGINT)");
        try (DuckDBAppender appender = Connections.duckDB(connection).createAppender("temp", "main", staging)) {
            for (long id : recordIds) {
                appender.beginRow();
                appender.append(id);
                appender.endRow();
            }
            appender.flush();
        }
        Sql.execute(connection, "DELETE FROM " + Sql.quote(valueTable) + " WHERE record_id IN (SELECT record_id FROM "
                + Sql.quote(staging) + ")");
    }

    private void appendValues(Connection connection, MeasureBatch batch, int[] idOfKey, String part,
                              boolean onlyChanges) throws SQLException {
        if (batch.valueCount() == 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        String incoming = name + "__incoming";
        if (onlyChanges) {
            Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(incoming)
                    + " (record_id BIGINT, key_id INTEGER, value DOUBLE)");
        }
        DuckDBConnection duckDB = Connections.duckDB(connection);
        try (DuckDBAppender appender = onlyChanges
                ? duckDB.createAppender("temp", "main", incoming)
                : duckDB.createAppender(DuckDBConnection.DEFAULT_SCHEMA, valueTable)) {
            for (int record = 0; record < batch.recordCount(); record++) {
                long recordId = batch.recordId(record);
                for (int position = batch.valueStart(record); position < batch.valueEnd(record); position++) {
                    appender.beginRow();
                    appender.append(recordId);
                    appender.append(idOfKey[batch.keyOfValue(position)]);
                    appender.append(batch.value(position));
                    if (!onlyChanges) {
                        appender.append(part);
                        appender.append(writtenBy);
                        appender.append(now);
                    }
                    appender.endRow();
                }
            }
            appender.flush();
        }
        if (onlyChanges) {
            // One statement over the whole batch: 36 ms for two million values, measured.
            Sql.execute(connection, "INSERT INTO " + Sql.quote(valueTable) + " SELECT i.record_id, i.key_id, i.value, "
                    + literal(part) + ", " + literal(writtenBy) + ", " + literal(now.toString())
                    + "::TIMESTAMP FROM " + Sql.quote(incoming) + " i WHERE NOT EXISTS (SELECT 1 FROM "
                    + Sql.quote(valueTable) + " v WHERE v.record_id = i.record_id AND v.key_id = i.key_id"
                    + " AND v.value IS NOT DISTINCT FROM i.value)");
            Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(incoming));
        }
    }

    private static boolean isConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("conflict") || message.contains("duplicate key")) {
                return true;
            }
        }
        return false;
    }

    // ---------- Archiving ----------

    /**
     * Writes one part out as Parquet and removes it from the live tables. The file holds a row per
     * value with its key fields beside it, and this measure's definition in its metadata, so it can
     * be read back - or read by anything else that reads Parquet - with nothing else in hand.
     *
     * @param location where to write: a directory, or anything DuckDB can write such as {@code s3://}
     * @return the file written, as {@code <location>/measure=<name>/part=<part>/<writer>-<when>.parquet}
     */
    public String archive(Connection connection, String location, String part) {
        writeLock.lock();
        try {
            String file = fileFor(location, part);
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            try {
                if (ownTransaction) {
                    Transactions.begin(connection);
                }
                Sql.execute(connection, "COPY (SELECT v.record_id, " + fieldList("k") + ", v.value, v.part,"
                        + " v.written_by, v.written_at FROM " + Sql.quote(valueTable) + " v JOIN " + Sql.quote(keyTable)
                        + " k ON k.id = v.key_id WHERE v.part = " + literal(part) + " ORDER BY " + fieldList("k")
                        + ") TO " + literal(file) + " (FORMAT parquet, COMPRESSION zstd, KV_METADATA {"
                        + METADATA_KEY + ": " + literal(definitionJson(part)) + "})");
                Sql.execute(connection, "DELETE FROM " + Sql.quote(valueTable) + " WHERE part = " + literal(part));
                if (ownTransaction) {
                    Transactions.commit(connection);
                }
            }
            catch (RuntimeException e) {
                if (ownTransaction) {
                    Transactions.rollbackQuietly(connection);
                }
                throw new IllegalStateException("Failed to archive part '" + part + "' of " + name, e);
            }
            // Until DuckDB checkpoints, the rows just deleted still hold their blocks, so the
            // measure would look as large as before - measured at 8,448 KB against 5,888 after.
            // It cannot run inside a transaction or with a result set open, hence the quiet catch.
            try {
                Sql.execute(connection, "CHECKPOINT");
            }
            catch (RuntimeException busy) {
                // Someone is reading; the space is reclaimed by a later checkpoint instead.
            }
            return file;
        }
        finally {
            writeLock.unlock();
        }
    }

    /** Archives a part to the location this measure was given by {@code archiveTo(...)}. */
    public String archive(Connection connection, String part) {
        if (archiveLocation == null) {
            throw new IllegalStateException(name + " has no archiveTo(location), so archive() needs one");
        }
        return archive(connection, archiveLocation, part);
    }

    /** Archives the oldest parts until the measure is under its size limit, if it has one. */
    private void archiveIfTooLarge(Connection connection, long valuesWritten) {
        if (archiveOverBytes <= 0) {
            return;
        }
        writtenSinceCheck += valuesWritten;
        if (writtenSinceCheck < CHECK_EVERY) {
            return;
        }
        writtenSinceCheck = 0;
        while (sizeOnDisk(connection) > archiveOverBytes) {
            List<Part> parts = parts(connection);
            if (parts.size() <= 1) {
                // Never archive the only part: it is what writers are still adding to.
                return;
            }
            String oldest = parts.get(0).name();
            String file = archive(connection, archiveLocation, oldest);
            if (archiveHandler != null) {
                try {
                    archiveHandler.archived(oldest, file);
                }
                catch (Exception e) {
                    throw new IllegalStateException("Archived part '" + oldest + "' of " + name + " to " + file
                            + ", but handing it on failed", e);
                }
            }
        }
    }

    /** {@code <location>/measure=<name>/part=<part>/<writer>-<when>.parquet} */
    private String fileFor(String location, String part) {
        String directory = trimEnd(location) + "/measure=" + name + "/part=" + safe(part);
        if (!location.contains("://")) {
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));
            }
            catch (java.io.IOException e) {
                throw new IllegalStateException("Could not make the directory " + directory, e);
            }
        }
        return directory + "/" + safe(writtenBy) + "-" + System.currentTimeMillis() + ".parquet";
    }

    private static String trimEnd(String location) {
        return location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
    }

    /** Part labels and writer names end up in paths, so anything awkward becomes an underscore. */
    private static String safe(String text) {
        return text.isEmpty() ? "none" : text.replaceAll("[^A-Za-z0-9._=-]", "_");
    }

    /** This measure, as it goes into a file's metadata. Small enough to write by hand. */
    String definitionJson(String part) {
        StringBuilder json = new StringBuilder("{\"measure\":").append(jsonString(name)).append(",\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            json.append(i == 0 ? "" : ",").append(jsonString(fields.get(i)));
        }
        json.append(']');
        if (unitField != null) {
            json.append(",\"unit\":").append(jsonString(unitField));
        }
        if (!defaults.isEmpty()) {
            json.append(",\"defaults\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(defaults).entrySet()) {
                json.append(first ? "" : ",").append(jsonString(entry.getKey())).append(':')
                        .append(jsonString(entry.getValue()));
                first = false;
            }
            json.append('}');
        }
        if (!orders.isEmpty()) {
            json.append(",\"orders\":{");
            boolean first = true;
            for (Map.Entry<String, List<String>> entry : orders.entrySet()) {
                json.append(first ? "" : ",").append(jsonString(entry.getKey())).append(":[");
                for (int i = 0; i < entry.getValue().size(); i++) {
                    json.append(i == 0 ? "" : ",").append(jsonString(entry.getValue().get(i)));
                }
                json.append(']');
                first = false;
            }
            json.append('}');
        }
        return json.append(",\"part\":").append(jsonString(part))
                .append(",\"written_by\":").append(jsonString(writtenBy))
                .append(",\"written_at\":").append(jsonString(Instant.now().toString()))
                .append(",\"version\":1}").toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    // ---------- Reading files back ----------

    /**
     * The measure a file was written by, read from the definition in its metadata - so an archived
     * file can be understood with nothing else in hand.
     */
    public static MeasureTable describedBy(Connection connection, String location) {
        String json = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM parquet_kv_metadata("
                + literal(location) + ") WHERE key::VARCHAR = " + literal(METADATA_KEY));
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                json = new String(rows.getBytes(1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the measure written to " + location, e);
        }
        if (json == null) {
            throw new IllegalStateException("No measure definition in " + location
                    + " - was it written by MeasureTable.archive?");
        }
        return fromJson(json);
    }

    /** Reads a definition as {@link #definitionJson} writes it. */
    static MeasureTable fromJson(String json) {
        Map<String, Object> read = MeasureJson.parseObject(json);
        Builder builder = named(String.valueOf(read.get("measure")));
        for (Object field : MeasureJson.list(read.get("fields"))) {
            builder.field(String.valueOf(field));
        }
        MeasureJson.object(read.get("defaults"))
                .forEach((field, value) -> builder.defaults.put(field, String.valueOf(value)));
        if (read.get("unit") != null) {
            builder.unit(String.valueOf(read.get("unit")));
        }
        MeasureJson.object(read.get("orders")).forEach((field, values) -> {
            List<String> order = new ArrayList<>();
            for (Object value : MeasureJson.list(values)) {
                order.add(String.valueOf(value));
            }
            builder.order(field, order.toArray(new String[0]));
        });
        if (read.get("written_by") != null) {
            builder.writtenBy(String.valueOf(read.get("written_by")));
        }
        return builder.build();
    }

    /**
     * Reads archived files back into this measure's tables, one part or many at once, creating the
     * tables first if they are not there.
     *
     * <p>Keys are matched by their fields, not by any id, so files can arrive where the dictionary
     * was built in another order. The parts the files hold are replaced rather than added to, so
     * restoring the same files twice leaves the same thing. A field a file does not have - because
     * it was written before that field existed - takes its default.</p>
     *
     * @return how many values were read in
     */
    public long restore(Connection connection, List<String> locations) {
        if (locations.isEmpty()) {
            return 0;
        }
        writeLock.lock();
        try {
            create(connection);
            String files = readParquet(locations);
            List<String> present = columnsOfQuery(connection, files);
            StringBuilder select = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                String field = fields.get(i);
                if (!present.contains(field) && !defaults.containsKey(field)) {
                    throw new IllegalStateException("Those files have no '" + field + "', and it has no default:"
                            + " field(\"" + field + "\", \"...\") says what older values should read as");
                }
                select.append(i == 0 ? "" : ", ")
                        .append(present.contains(field)
                                ? "coalesce(" + Sql.quote(field) + ", " + literal(defaults.getOrDefault(field, "")) + ")"
                                : literal(defaults.get(field)))
                        .append(" AS ").append(Sql.quote(field));
            }
            String staging = Sql.quote(name + "__restoring");
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            try {
                if (ownTransaction) {
                    Transactions.begin(connection);
                }
                Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + staging + " AS SELECT " + select
                        + ", record_id, value, part, written_by, written_at FROM " + files);
                Sql.execute(connection, "INSERT INTO " + Sql.quote(keyTable) + " SELECT nextval('" + sequence
                        + "'), s.* FROM (SELECT DISTINCT " + fieldList(null) + " FROM " + staging + ") s"
                        + " WHERE NOT EXISTS (SELECT 1 FROM " + Sql.quote(keyTable) + " k WHERE "
                        + matchOn("k", "s") + ")");
                Sql.execute(connection, "DELETE FROM " + Sql.quote(valueTable)
                        + " WHERE part IN (SELECT DISTINCT part FROM " + staging + ")");
                long written = Sql.executeUpdate(connection, "INSERT INTO " + Sql.quote(valueTable)
                        + " SELECT f.record_id, k.id, f.value, f.part, f.written_by, f.written_at FROM " + staging
                        + " f JOIN " + Sql.quote(keyTable) + " k ON " + matchOn("k", "f"), List.of());
                Sql.execute(connection, "DROP TABLE IF EXISTS " + staging);
                if (ownTransaction) {
                    Transactions.commit(connection);
                }
                return written;
            }
            catch (RuntimeException e) {
                if (ownTransaction) {
                    Transactions.rollbackQuietly(connection);
                }
                throw new IllegalStateException("Failed to restore " + locations + " into " + name, e);
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    /**
     * Rebuilds a measure from archived files alone: reads what it is from the first file, creates
     * its tables, and reads every file in.
     */
    public static MeasureTable restore(Connection connection, String... locations) {
        if (locations.length == 0) {
            throw new IllegalArgumentException("Nothing to restore");
        }
        MeasureTable measure = describedBy(connection, locations[0]);
        measure.restore(connection, List.of(locations));
        return measure;
    }

    /**
     * Reads a set of archived files as one table: {@code union_by_name} so files written under
     * different definitions line up by column name, and {@code hive_partitioning = false} because
     * the path holds {@code measure=m/part=2026-09-20} for the benefit of data lakes - without this
     * DuckDB would read those back as columns of its own, inventing a {@code measure} column and
     * turning the file's own {@code part} into a date.
     */
    static String readParquet(List<String> locations) {
        StringBuilder list = new StringBuilder("read_parquet([");
        for (int i = 0; i < locations.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(literal(locations.get(i)));
        }
        return list.append("], union_by_name = true, hive_partitioning = false)").toString();
    }

    /** The column names of a table or of a set of files. */
    static List<String> columnsOfQuery(Connection connection, String source) {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("DESCRIBE SELECT * FROM " + source);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the columns of " + source, e);
        }
        return columns;
    }

    String fieldList(String alias) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(alias == null ? "" : alias + ".").append(Sql.quote(fields.get(i)));
        }
        return list.toString();
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @Override
    public String toString() {
        return "MeasureTable[" + name + " " + fields + (unitField == null ? "" : ", unit " + unitField)
                + ", written by " + writtenBy + "]";
    }
}
