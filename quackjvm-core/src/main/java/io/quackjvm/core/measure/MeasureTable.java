package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Values that each belong to a record and are identified by several fields, stored sparsely - a row
 * per value that exists - and read back grouped or pivoted by any of those fields.
 *
 * <pre>
 * MeasureTable measures = MeasureTable.named("measures")
 *         .fields("a", "b", "c", "point", "unit")
 *         .unit("unit")
 *         .build();
 * measures.create(connection);
 *
 * measures.replace(connection, measures.batch()
 *         .record(42)
 *             .put(1.25, "x", "y", "z", "5y", "U1")
 *             .put(0.97, "x", "y", "z", "5y", "U2")
 *         .build());
 *
 * // Any field as rows, any field as columns.
 * measures.query().rows("a", "b").columns("point").where("unit", "U1").run(connection.duplicate());
 * </pre>
 *
 * <p>Two tables: a dictionary holding each distinct key once, with a column per field, and a values
 * table of {@code (record_id, key_id, value)}. The dictionary stays small - thousands of rows where
 * the values table holds millions - which is what makes grouping by any field cheap.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Reads never block. Writes through one instance are serialised with each other; writes through
 * different instances are safe, though two touching the same records can make one fail with a
 * conflict, which is retried when this class owns the transaction.</p>
 */
public final class MeasureTable {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int IN_LIST_CHUNK = 1_000;
    private static final int ATTEMPTS = 3;

    private final String name;
    private final List<String> fields;
    private final String unitField;
    /** Per field, the order its values are meant to come out in; empty means text order. */
    private final Map<String, List<String>> orders;
    private final String keyTable;
    private final String valueTable;
    private final String sequence;
    private final ReentrantLock writeLock = new ReentrantLock(true);

    private MeasureTable(Builder builder) {
        this.name = builder.name;
        this.fields = List.copyOf(builder.fields);
        this.unitField = builder.unitField;
        this.orders = Map.copyOf(builder.orders);
        this.keyTable = name + "_key";
        this.valueTable = name + "_value";
        this.sequence = name + "_key_id";
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private List<String> fields = List.of();
        private String unitField;
        private final Map<String, List<String>> orders = new HashMap<>();

        private Builder(String name) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("A measure name must be letters, digits and underscores,"
                        + " starting with a letter or underscore: " + name);
            }
            this.name = name;
        }

        /** The fields that identify a value, in the order {@code put(...)} takes them. */
        public Builder fields(String... fields) {
            if (fields.length == 0) {
                throw new IllegalArgumentException("A measure needs at least one field");
            }
            List<String> names = List.of(fields);
            for (String field : names) {
                if (!NAME.matcher(field).matches()) {
                    throw new IllegalArgumentException("A field name must be letters, digits and underscores,"
                            + " starting with a letter or underscore: " + field);
                }
                if (field.equals("record_id") || field.equals("key_id") || field.equals("value")) {
                    throw new IllegalArgumentException("'" + field + "' is a column of the values table;"
                            + " a field cannot be called that");
                }
            }
            if (names.size() != names.stream().distinct().count()) {
                throw new IllegalArgumentException("A field is named twice: " + names);
            }
            this.fields = names;
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

        public MeasureTable build() {
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Call fields(...) before build()");
            }
            if (unitField != null && !fields.contains(unitField)) {
                throw new IllegalArgumentException("The unit field '" + unitField + "' is not one of " + fields);
            }
            for (String field : orders.keySet()) {
                if (!fields.contains(field)) {
                    throw new IllegalArgumentException("Cannot order '" + field + "', which is not one of " + fields);
                }
            }
            return new MeasureTable(this);
        }
    }

    public String getName() {
        return name;
    }

    /** The fields that identify a value, in the order {@code put(...)} takes them. */
    public List<String> getFields() {
        return fields;
    }

    /** The declared order of a field's values, or empty if they come out in text order. */
    public List<String> orderOf(String field) {
        return orders.getOrDefault(field, List.of());
    }

    /** Which field says what unit a value is in, or null if the measure has no units. */
    public String getUnitField() {
        return unitField;
    }

    /** The dictionary table: {@code (id INTEGER, <field> VARCHAR, ...)}. */
    public String getKeyTable() {
        return keyTable;
    }

    /** The values table: {@code (record_id BIGINT, key_id INTEGER, value DOUBLE)}. */
    public String getValueTable() {
        return valueTable;
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
        columns.append(", UNIQUE (");
        for (int i = 0; i < fields.size(); i++) {
            columns.append(i == 0 ? "" : ", ").append(Sql.quote(fields.get(i)));
        }
        columns.append(')');
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(keyTable) + " (" + columns + ")");
        Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(valueTable)
                + " (record_id BIGINT NOT NULL, key_id INTEGER NOT NULL, value DOUBLE)");
    }

    /**
     * Adds the batch's values. Records already in the table keep what they had; the batch must not
     * repeat a key a record already has - use {@link #replace} when records may already be there.
     */
    public void append(Connection connection, MeasureBatch batch) {
        write(connection, batch, false);
    }

    /**
     * Makes each record in the batch hold exactly the batch's values: whatever those records had
     * before is removed first. A record with no values in the batch is removed altogether.
     */
    public void replace(Connection connection, MeasureBatch batch) {
        write(connection, batch, true);
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

    /** The distinct values a field has in the dictionary, in order. */
    public List<String> valuesOf(Connection connection, String field) {
        checkField(field);
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT " + Sql.quote(field)
                + " FROM " + Sql.quote(keyTable) + " ORDER BY 1");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the values of '" + field + "' in " + name, e);
        }
        return values;
    }

    void checkField(String field) {
        if (!fields.contains(field)) {
            throw new IllegalArgumentException("'" + field + "' is not a field of " + name + ": " + fields);
        }
    }

    // ---------- Exporting and reading back ----------

    /**
     * Writes this measure to a directory as Parquet: its dictionary, its values, and what it is -
     * the fields, which one is the unit, and any declared orders.
     *
     * <p>Three files, so that the export is a measure rather than two loose tables:</p>
     * <ul>
     *   <li>{@code key.parquet} - each distinct key once, a column per field</li>
     *   <li>{@code value.parquet} - {@code (record_id, key_id, value)}</li>
     *   <li>{@code measure.parquet} - the definition, so {@link #describedBy} can read it back</li>
     * </ul>
     *
     * <p>The files can be read back with {@link #importFrom}, queried where they lie with
     * {@link MeasureQuery#from}, or read by anything else that reads Parquet. For handing to
     * something that would rather have one flat table, see {@link #exportFlat}.</p>
     */
    public void export(Connection connection, Path directory) {
        createDirectory(directory);
        Sql.execute(connection, "COPY (SELECT * FROM " + Sql.quote(keyTable) + ") TO " + path(directory, "key.parquet")
                + " (FORMAT parquet, COMPRESSION zstd)");
        Sql.execute(connection, "COPY (SELECT * FROM " + Sql.quote(valueTable) + ") TO "
                + path(directory, "value.parquet") + " (FORMAT parquet, COMPRESSION zstd)");
        StringBuilder definition = new StringBuilder("SELECT * FROM (VALUES ('name', ")
                .append(literal(name)).append(", 0, NULL)");
        for (int i = 0; i < fields.size(); i++) {
            definition.append(", ('field', ").append(literal(fields.get(i))).append(", ").append(i).append(", NULL)");
        }
        if (unitField != null) {
            definition.append(", ('unit', ").append(literal(unitField)).append(", 0, NULL)");
        }
        orders.forEach((field, values) -> {
            for (int i = 0; i < values.size(); i++) {
                definition.append(", ('order', ").append(literal(field)).append(", ").append(i).append(", ")
                        .append(literal(values.get(i))).append(")");
            }
        });
        definition.append(") t(part, name, position, value)");
        Sql.execute(connection, "COPY (" + definition + ") TO " + path(directory, "measure.parquet")
                + " (FORMAT parquet)");
    }

    /**
     * The values with their key fields alongside, as one flat Parquet table: a row per value, with
     * a column per field. For handing to something that does not know this layout.
     *
     * @param partitionBy fields to split the files by, if any - one directory level each, as
     *                     {@code unit=U1/data_0.parquet}; without them it is one {@code flat.parquet}
     */
    public void exportFlat(Connection connection, Path directory, String... partitionBy) {
        createDirectory(directory);
        StringBuilder columns = new StringBuilder("v.record_id");
        for (String field : fields) {
            columns.append(", k.").append(Sql.quote(field));
        }
        columns.append(", v.value");
        StringBuilder options = new StringBuilder("FORMAT parquet, COMPRESSION zstd, OVERWRITE_OR_IGNORE true");
        if (partitionBy.length > 0) {
            options.append(", PARTITION_BY (");
            for (int i = 0; i < partitionBy.length; i++) {
                checkField(partitionBy[i]);
                options.append(i == 0 ? "" : ", ").append(Sql.quote(partitionBy[i]));
            }
            options.append(')');
        }
        Sql.execute(connection, "COPY (SELECT " + columns + " FROM " + Sql.quote(valueTable) + " v JOIN "
                + Sql.quote(keyTable) + " k ON k.id = v.key_id) TO "
                // Partitioned, the files go into the directory itself, as unit=U1/data_0.parquet.
                + (partitionBy.length > 0 ? literal(directory.toAbsolutePath().toString())
                        : path(directory, "flat.parquet"))
                + " (" + options + ")");
    }

    /** The measure that was exported to this directory, read from its definition file. */
    public static MeasureTable describedBy(Connection connection, Path directory) {
        String name = null;
        String unit = null;
        List<String> fields = new ArrayList<>();
        Map<String, List<String>> orders = new java.util.LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT part, name, position, value FROM "
                + readParquet(directory, "measure") + " ORDER BY part, position");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                switch (rows.getString(1)) {
                    case "name" -> name = rows.getString(2);
                    case "field" -> fields.add(rows.getString(2));
                    case "unit" -> unit = rows.getString(2);
                    case "order" -> orders.computeIfAbsent(rows.getString(2), f -> new ArrayList<>())
                            .add(rows.getString(4));
                    default -> throw new IllegalStateException("Unknown part of a measure: " + rows.getString(1));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the measure exported to " + directory, e);
        }
        if (name == null || fields.isEmpty()) {
            throw new IllegalStateException("No measure was exported to " + directory);
        }
        Builder builder = named(name).fields(fields.toArray(new String[0]));
        if (unit != null) {
            builder.unit(unit);
        }
        orders.forEach((field, values) -> builder.order(field, values.toArray(new String[0])));
        return builder.build();
    }

    /**
     * Reads an export back into this measure's tables, adding to whatever is already there.
     *
     * <p>Keys are matched by their fields, not by the ids they had when exported: a key the
     * dictionary already has keeps its id, and the values arriving point at it. So an export can be
     * read into a measure that holds other records, or into an empty one.</p>
     *
     * @return how many values were read in
     */
    public long importFrom(Connection connection, Path directory) {
        return importFrom(connection, directory, false);
    }

    /**
     * As {@link #importFrom(Connection, Path)}, but each record in the export first loses whatever
     * it had here - so those records end up holding exactly what was exported.
     */
    public long importReplacing(Connection connection, Path directory) {
        return importFrom(connection, directory, true);
    }

    private long importFrom(Connection connection, Path directory, boolean replacing) {
        MeasureTable exported = describedBy(connection, directory);
        if (!exported.fields.equals(fields)) {
            throw new IllegalArgumentException("That export is of " + exported.fields + ", not " + fields);
        }
        writeLock.lock();
        try {
            boolean ownTransaction = Transactions.isAutoCommit(connection);
            try {
                if (ownTransaction) {
                    Transactions.begin(connection);
                }
                create(connection);
                String keys = readParquet(directory, "key");
                String values = readParquet(directory, "value");
                Sql.execute(connection, "INSERT INTO " + Sql.quote(keyTable) + " SELECT nextval('" + sequence
                        + "'), s.* FROM (SELECT DISTINCT " + fieldList("i") + " FROM " + keys + " i) s"
                        + " WHERE NOT EXISTS (SELECT 1 FROM " + Sql.quote(keyTable) + " k WHERE " + matchOn("k", "s") + ")");
                if (replacing) {
                    Sql.execute(connection, "DELETE FROM " + Sql.quote(valueTable) + " WHERE record_id IN"
                            + " (SELECT DISTINCT record_id FROM " + values + ")");
                }
                long written = Sql.executeUpdate(connection, "INSERT INTO " + Sql.quote(valueTable)
                        + " SELECT v.record_id, k.id, v.value FROM " + values + " v JOIN " + keys
                        + " i ON i.id = v.key_id JOIN " + Sql.quote(keyTable) + " k ON " + matchOn("k", "i"), List.of());
                if (ownTransaction) {
                    Transactions.commit(connection);
                }
                return written;
            }
            catch (RuntimeException e) {
                if (ownTransaction) {
                    Transactions.rollbackQuietly(connection);
                }
                throw new IllegalStateException("Failed to read " + directory + " into " + name, e);
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    /** {@code read_parquet('<directory>/<what>.parquet')}, the source of an exported file. */
    static String readParquet(Path directory, String what) {
        return "read_parquet(" + path(directory, what + ".parquet") + ")";
    }

    private String fieldList(String alias) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(alias).append('.').append(Sql.quote(fields.get(i)));
        }
        return list.toString();
    }

    private static void createDirectory(Path directory) {
        try {
            java.nio.file.Files.createDirectories(directory);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("Could not make the directory " + directory, e);
        }
    }

    private static String path(Path directory, String file) {
        return literal(directory.resolve(file).toAbsolutePath().toString());
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ---------- Writing ----------

    private void write(Connection connection, MeasureBatch batch, boolean replacing) {
        if (!batch.getFields().equals(fields)) {
            throw new IllegalArgumentException("That batch is for the key " + batch.getFields() + ", not " + fields);
        }
        writeLock.lock();
        try {
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
                    appendValues(connection, batch, idOfKey);
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
                    // Most often two writers adding the same new key at once: the next attempt
                    // finds it in the dictionary.
                }
            }
        }
        finally {
            writeLock.unlock();
        }
    }

    /** The dictionary id of each distinct key in the batch, adding any the dictionary lacks. */
    private int[] resolveCreating(Connection connection, MeasureBatch batch) throws SQLException {
        String staging = name + "__keys";
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            columns.append(i == 0 ? "" : ", ").append(Sql.quote(fields.get(i))).append(" VARCHAR");
        }
        Sql.execute(connection, "CREATE OR REPLACE TEMP TABLE " + Sql.quote(staging) + " (" + columns + ")");
        try (DuckDBAppender appender = Connections.duckDB(connection).createAppender("temp", "main", staging)) {
            for (int key = 0; key < batch.keyCount(); key++) {
                appender.beginRow();
                for (int field = 0; field < fields.size(); field++) {
                    appender.append(batch.keyPart(key, field));
                }
                appender.endRow();
            }
            // Explicit, never left to close(): DuckDB's Appender swallows a failed flush on close.
            appender.flush();
        }
        String match = matchOn("k", "s");
        Sql.execute(connection, "INSERT INTO " + Sql.quote(keyTable) + " SELECT nextval('" + sequence + "'), s.*"
                + " FROM (SELECT DISTINCT * FROM " + Sql.quote(staging) + ") s WHERE NOT EXISTS (SELECT 1 FROM "
                + Sql.quote(keyTable) + " k WHERE " + match + ")");

        Map<List<String>, Integer> ids = new HashMap<>(batch.keyCount() * 2);
        StringBuilder select = new StringBuilder("SELECT k.id");
        for (String field : fields) {
            select.append(", k.").append(Sql.quote(field));
        }
        try (PreparedStatement statement = connection.prepareStatement(select + " FROM " + Sql.quote(keyTable)
                + " k JOIN (SELECT DISTINCT * FROM " + Sql.quote(staging) + ") s ON " + match);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                List<String> parts = new ArrayList<>(fields.size());
                for (int field = 0; field < fields.size(); field++) {
                    parts.add(rows.getString(field + 2));
                }
                ids.put(List.copyOf(parts), rows.getInt(1));
            }
        }
        int[] result = new int[batch.keyCount()];
        for (int key = 0; key < batch.keyCount(); key++) {
            List<String> parts = new ArrayList<>(fields.size());
            for (int field = 0; field < fields.size(); field++) {
                parts.add(batch.keyPart(key, field));
            }
            Integer id = ids.get(List.copyOf(parts));
            if (id == null) {
                throw new IllegalStateException("Could not add a key to the dictionary of " + name + ": " + parts);
            }
            result[key] = id;
        }
        return result;
    }

    /** {@code k."a" = s."a" AND ...}, the join between the dictionary and a batch's keys. */
    private String matchOn(String left, String right) {
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

    private void appendValues(Connection connection, MeasureBatch batch, int[] idOfKey) throws SQLException {
        if (batch.valueCount() == 0) {
            return;
        }
        DuckDBConnection duckDB = Connections.duckDB(connection);
        try (DuckDBAppender appender = duckDB.createAppender(DuckDBConnection.DEFAULT_SCHEMA, valueTable)) {
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
        return "MeasureTable[" + name + " " + fields + (unitField == null ? "" : ", unit " + unitField) + "]";
    }
}
