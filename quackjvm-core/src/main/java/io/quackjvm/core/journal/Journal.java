package io.quackjvm.core.journal;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.page.PageKey;
import io.quackjvm.core.page.Pages;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An application's own records, kept in a table that grows: written once, perhaps changed while
 * they are still being written, read back a page at a time and searched.
 *
 * <pre>
 * Journal&lt;Message&gt; messages = Journal.builder(quack, "message", ColumnarLayout.ofRecord(Message.class))
 *         .index("session", "ts")
 *         .searchable("text")
 *         .migrate(2, "ALTER TABLE message ADD COLUMN cost DOUBLE")   // for a later version of the app
 *         .open();
 *
 * messages.append(message);                                 // keyed by the layout's first column
 * messages.update(id, Map.of("text", soFar, "done", true)); // a message that grows as it streams in
 * messages.search("connection refused", 20);                // hits, newest first, with a snippet
 * Pages page = messages.pages("session = ?", sessionId).orderBy("ts", "id").size(50);
 * messages.deleteWhere("session = ?", sessionId);           // a session the person removed
 * </pre>
 *
 * <h2>Keys</h2>
 *
 * <p>The key is the layout's first column, or the columns {@link Builder#keyColumns} names - a chat
 * and a number within it, say. A key of several columns is passed to {@link #get}, {@link #update}
 * and {@link #delete} as a {@link PageKey}. A {@link Builder#sequence sequence} column is numbered by
 * the journal: 1, 2, 3... within each group, under the writer's turn.</p>
 *
 * <h2>Versions</h2>
 *
 * <p>A table made by this journal starts at the latest version its migrations name, with every
 * column the layout has, and no migration runs. A table made by an earlier version of the
 * application is moved up one migration at a time, each in a transaction with the version it
 * reaches, so a failed one leaves the table as it was. The version is kept in a small table,
 * {@code quackjvm_journal}. A table with no version there - made before it had migrations - is at
 * version 0. After the migrations the table must have the layout's columns in the layout's order:
 * a column a migration adds goes last, so it goes last in the layout too. Rows written before a
 * column existed read it as null.</p>
 *
 * <h2>Writing and reading</h2>
 *
 * <p>One writer: the methods that write take turns - every journal object over the same table of
 * the same {@link Quack}, not just this one. Readers need no turn - DuckDB gives each query
 * the rows committed when it began - so a page read while a stream appends sees whole rows only.
 * Each write commits on its own; DuckDB's write-ahead log makes a write that was cut off by the
 * process ending be either all there or not there when the file is opened again.</p>
 *
 * <h2>Deleting</h2>
 *
 * <p>{@link #delete} and {@link #deleteWhere} take the writer's turn and commit in one go: after a
 * crash, all of a {@code deleteWhere} is done or none of it. A page read meanwhile sees the rows as
 * they were when it began, or as they are after; a deleted row is simply not on the next page.
 * DuckDB reuses a deleted row's space for the rows written after it, so a journal that is added to
 * and deleted from levels off rather than growing. It does not give the space back to the file
 * system: to shrink a file after deleting most of it, copy the database into a new file and open
 * that one instead - see the paging docs.</p>
 *
 * <h2>Searching</h2>
 *
 * <p>Every word must appear, in any case, in one of the searchable columns: {@code ILIKE}, not
 * DuckDB's full-text extension. That extension is not in the JDBC driver and would have to be
 * downloaded, which a hardened database refuses, and its index does not follow new rows. A search
 * is a scan: measured at 12 ms over 100,000 records and 17 ms over a million, on a busy machine.</p>
 */
public final class Journal<O> {

    /** Where each journal's version is kept. */
    public static final String VERSIONS = "quackjvm_journal";

    private final Quack quack;
    private final String table;
    private final ColumnarLayout<O> layout;
    private final List<ColumnDef> columns;
    private final List<String> keys;
    private final List<String> newest;
    private final List<String> searchable;
    private final Sequence sequence;
    private final TableWriter writer;
    private final int version;
    /** The writer's turn: shared by every journal over this table of this database in the JVM. */
    private final Object turn;

    /** One turn per database and table. Weak, so a closed database's turns go with it. */
    private static final Map<Quack, Map<String, Object>> TURNS = new java.util.WeakHashMap<>();

    private static Object turnOf(Quack quack, String table) {
        synchronized (TURNS) {
            return TURNS.computeIfAbsent(quack, q -> new HashMap<>())
                    .computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> new Object());
        }
    }

    private Journal(Builder<O> builder, int version) {
        this.quack = builder.quack;
        this.table = builder.table;
        this.layout = builder.layout;
        this.columns = layout.toColumnDefs();
        this.keys = builder.keys();
        this.newest = builder.newest.isEmpty() ? keys : List.copyOf(builder.newest);
        this.searchable = List.copyOf(builder.searchable);
        this.sequence = builder.sequence == null ? null : new Sequence(builder.sequence, builder.within, layout);
        this.writer = new TableWriter(table, columns, false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
        this.version = version;
        this.turn = turnOf(quack, table);
    }

    /** A journal kept in this table, of objects of this layout; the layout's first column is the key. */
    public static <O> Builder<O> builder(Quack quack, String table, ColumnarLayout<O> layout) {
        return new Builder<>(quack, table, layout);
    }

    // ---------- writing ----------

    /**
     * Adds a record. One whose key is already there is refused: a journal adds, {@link #update}
     * changes. A {@link Builder#sequence sequence} column that is null or 0 gets the next number.
     */
    public void append(O record) {
        synchronized (turn) {
            append(List.of(record));
        }
    }

    /** Adds records in one transaction: all of them, or none if one is refused. */
    public void append(Iterable<O> records) {
        synchronized (turn) {
            write(records);
        }
    }

    /** As {@link #append(Object)}, and gives the record back as it was stored - with its number, say. */
    public O appendAndGet(O record) {
        synchronized (turn) {
            Object[] row = write(List.of(record)).get(0);
            return find(keyCondition(keyOf(row))).orElseThrow();
        }
    }

    /** How many times an append whose numbers another writer took first is numbered again. */
    static final int RENUMBER = 10;

    private List<Object[]> write(Iterable<O> given) {
        List<O> records = new ArrayList<>();
        given.forEach(records::add);   // walked again if numbered again
        for (int attempt = 0; ; attempt++) {
            List<Object[]> rows = new ArrayList<>();
            boolean[] numberedAny = new boolean[1];
            try {
                quack.inTransaction(tx -> {
                    Map<List<Object>, Long> numbered = new HashMap<>();
                    for (O record : records) {
                        Object[] row = layout.toRow(record);
                        if (sequence != null) {
                            numberedAny[0] |= sequence.number(tx.connection(), table, row, numbered);
                        }
                        rows.add(row);
                    }
                    writer.write(tx.connection(), rows.iterator(), false);
                });
                return rows;
            }
            catch (RuntimeException e) {
                RuntimeException refused = refused(e);
                // Another journal object writing the same table took the numbers first - a clash
                // DuckDB finds at commit. Numbered again, they go after its.
                if (refused instanceof IllegalArgumentException && numberedAny[0] && attempt < RENUMBER) {
                    // Both commits can fail at once, and both would pick the same numbers again:
                    // a wait of its own lets one go first.
                    pause(attempt);
                    continue;
                }
                throw refused;
            }
        }
    }

    /**
     * Changes some columns of the record with this key - the text of a message as more of it
     * arrives, say. The key itself cannot change. A key of several columns is a {@link PageKey}.
     *
     * @return false if there is no record with the key
     */
    public boolean update(Object key, Map<String, ?> changes) {
        synchronized (turn) {
            Condition where = keyCondition(key);
            if (changes.isEmpty()) {
                return find(where).isPresent();
            }
            StringBuilder sql = new StringBuilder("UPDATE ").append(Sql.quote(table)).append(" SET ");
            List<Object> values = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, ?> change : new TreeMap<>(changes).entrySet()) {
                String column = column(change.getKey());
                if (keys.contains(column)) {
                    throw new IllegalArgumentException("The key, " + String.join(", ", keys) + ", cannot be changed");
                }
                sql.append(i++ == 0 ? "" : ", ").append(Sql.quote(column)).append(" = ?");
                values.add(change.getValue());
            }
            sql.append(where.sql);
            values.addAll(where.values);
            return quack.execute(sql.toString(), values.toArray()) > 0;
        }
    }

    /**
     * Removes the record with this key - a key of several columns is a {@link PageKey}.
     *
     * @return false if there was no record with the key
     */
    public boolean delete(Object key) {
        synchronized (turn) {
            Condition where = keyCondition(key);
            return quack.execute("DELETE FROM " + Sql.quote(table) + where.sql, where.values.toArray()) > 0;
        }
    }

    /**
     * Removes the records that match a condition - SQL after {@code WHERE}, with {@code ?} for each
     * parameter, as {@link #pages} takes it. All of them go in one commit, or none. Removing every
     * record has to be asked for in so many words: {@code deleteWhere("true")}.
     *
     * @return how many records were removed
     */
    public long deleteWhere(String where, Object... parameters) {
        synchronized (turn) {
            if (where == null || where.isBlank()) {
                throw new IllegalArgumentException("deleteWhere needs a condition. To remove every record of " + table
                        + ", write deleteWhere(\"true\").");
            }
            long[] removed = new long[1];
            quack.inTransaction(tx -> removed[0] = Sql.executeUpdate(tx.connection(), "DELETE FROM " + Sql.quote(table)
                    + " WHERE " + where, Arrays.asList(parameters)));
            return removed[0];
        }
    }

    // ---------- reading ----------

    /** The record with this key; a key of several columns is a {@link PageKey}. */
    public Optional<O> get(Object key) {
        return find(keyCondition(key));
    }

    private Optional<O> find(Condition where) {
        List<O> found = select(where.sql, where.values, -1);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** How many records there are. */
    public long count() {
        return quack.query("SELECT count(*) FROM " + Sql.quote(table)).scalar(Long.class);
    }

    /**
     * Pages of the records that match a condition - SQL after {@code WHERE}, with {@code ?} for each
     * parameter, or null for all of them. Say the order with {@link Pages#orderBy}; turn a page into
     * records with {@code page.as(layout)}.
     */
    public Pages pages(String where, Object... parameters) {
        String sql = "SELECT * FROM " + Sql.quote(table) + (where == null || where.isBlank() ? "" : " WHERE " + where);
        return Pages.of(quack, sql, parameters);
    }

    /** One record that matched a search: the record, the column the words were found in, and the text around them. */
    public record Hit<O>(O record, String column, String snippet) {
    }

    /**
     * The records in which every word appears, in any case, in one searchable column - newest first:
     * by the columns {@link Builder#newestBy} names, else by the key, highest first. Each hit says
     * which column, with the text around the first word.
     */
    public List<Hit<O>> search(String text, int limit) {
        if (searchable.isEmpty()) {
            throw new IllegalStateException("No column of " + table + " is searchable: say which with searchable(...)");
        }
        List<String> words = words(text);
        if (words.isEmpty() || limit < 1) {
            return List.of();
        }
        StringBuilder where = new StringBuilder(" WHERE ");
        List<Object> parameters = new ArrayList<>();
        for (int c = 0; c < searchable.size(); c++) {
            where.append(c == 0 ? "(" : " OR (");
            for (int w = 0; w < words.size(); w++) {
                where.append(w == 0 ? "" : " AND ").append(Sql.quote(searchable.get(c))).append(" ILIKE ? ESCAPE '\\'");
                parameters.add("%" + escapeLike(words.get(w)) + "%");
            }
            where.append(')');
        }
        where.append(" ORDER BY ");
        for (int i = 0; i < newest.size(); i++) {
            where.append(i == 0 ? "" : ", ").append(Sql.quote(newest.get(i))).append(" DESC");
        }
        List<O> records = select(where.toString(), parameters, limit);
        List<Hit<O>> hits = new ArrayList<>(records.size());
        for (O record : records) {
            hits.add(hit(record, words));
        }
        return hits;
    }

    public String table() {
        return table;
    }

    public ColumnarLayout<O> layout() {
        return layout;
    }

    /** The version the table is at. */
    public int version() {
        return version;
    }

    /** The columns of the key, in order. */
    public List<String> keyColumns() {
        return keys;
    }

    // ---------- underneath ----------

    private static void pause(int attempt) {
        try {
            Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 2L + Math.min(50, 1L << attempt)));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while appending", e);
        }
    }

    /** {@code WHERE} a key, and the values it binds. */
    private record Condition(String sql, List<Object> values) {
    }

    private Condition keyCondition(Object key) {
        List<Object> values;
        if (key instanceof PageKey pageKey) {
            values = pageKey.values();
        }
        else if (keys.size() == 1) {
            if (key == null) {
                throw new IllegalArgumentException("A key cannot be null");
            }
            values = List.of(key);
        }
        else {
            throw new IllegalArgumentException("The key of " + table + " is " + keys + ": pass PageKey.of("
                    + String.join(", ", keys) + "), not " + key);
        }
        if (values.size() != keys.size()) {
            throw new IllegalArgumentException("The key of " + table + " is " + keys + ", and " + key + " has "
                    + values.size() + " value(s)");
        }
        StringBuilder sql = new StringBuilder(" WHERE ");
        for (int i = 0; i < keys.size(); i++) {
            sql.append(i == 0 ? "" : " AND ").append(Sql.quote(keys.get(i))).append(" = ?");
        }
        return new Condition(sql.toString(), values);
    }

    private Object keyOf(Object[] row) {
        Object[] values = new Object[keys.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row[index(keys.get(i))];
        }
        return values.length == 1 ? values[0] : PageKey.of(values);
    }

    private int index(String column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(column)) {
                return i;
            }
        }
        throw new IllegalStateException("No column " + column);
    }

    private List<O> select(String rest, List<Object> parameters, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append(Sql.quote(columns.get(i).getName()));
        }
        sql.append(" FROM ").append(Sql.quote(table)).append(rest);
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        List<ColumnarLayout.Column<O, ?>> layoutColumns = layout.getColumns();
        try (Connection connection = quack.connection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            Sql.bindAll(statement, parameters, 1);
            try (ResultSet result = statement.executeQuery()) {
                List<O> records = new ArrayList<>();
                while (result.next()) {
                    Object[] values = new Object[layoutColumns.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = DuckDBTypes.read(result, i + 1, layoutColumns.get(i).getType());
                    }
                    records.add(layout.createObject(values));
                }
                return records;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read " + table + ": " + e.getMessage(), e);
        }
    }

    private Hit<O> hit(O record, List<String> words) {
        String first = words.get(0).toLowerCase(Locale.ROOT);
        for (String column : searchable) {
            Object value = layout.getColumn(column).getValue(record);
            if (value == null) {
                continue;
            }
            String text = value.toString();
            String lower = text.toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                all &= lower.contains(word.toLowerCase(Locale.ROOT));
            }
            if (all) {
                return new Hit<>(record, column, snippet(text, lower.indexOf(first), first.length()));
            }
        }
        return new Hit<>(record, searchable.get(0), "");
    }

    /** Up to 60 characters each side of the match, on word boundaries where there are some. */
    static String snippet(String text, int at, int length) {
        int from = Math.max(0, at - 60);
        int to = Math.min(text.length(), at + length + 60);
        if (from > 0) {
            int space = text.indexOf(' ', from);
            from = space >= 0 && space < at ? space + 1 : from;
        }
        if (to < text.length()) {
            int space = text.lastIndexOf(' ', to);
            to = space > at + length ? space : to;
        }
        return (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ") + (to < text.length() ? "…" : "");
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text != null) {
            for (String word : text.trim().split("\\s+")) {
                if (!word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        return words;
    }

    private static String escapeLike(String word) {
        return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String column(String name) {
        for (ColumnDef column : columns) {
            if (column.getName().equalsIgnoreCase(name)) {
                return column.getName();
            }
        }
        throw new IllegalArgumentException("No column " + name + " in " + table + ": "
                + columns.stream().map(ColumnDef::getName).toList());
    }

    private RuntimeException refused(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("duplicate key") || lower.contains("violates primary key")) {
                return new IllegalArgumentException("A record with that " + String.join(", ", keys) + " is already in "
                        + table
                        + ": a journal adds records, and update(...) changes one. DuckDB said: "
                        + message.split("\n")[0], e);
            }
        }
        return e;
    }

    @Override
    public String toString() {
        return "Journal[" + table + ", version " + version + "]";
    }

    /** What a journal is made with: indexes, searchable columns, and the migrations to its latest version. */
    public static final class Builder<O> {

        private final Quack quack;
        private final String table;
        private final ColumnarLayout<O> layout;
        private final List<List<String>> indexes = new ArrayList<>();
        private final List<String> searchable = new ArrayList<>();
        private final Map<Integer, List<String>> migrations = new TreeMap<>();
        private final List<String> keyColumns = new ArrayList<>();
        private final List<String> newest = new ArrayList<>();
        private String sequence;
        private final List<String> within = new ArrayList<>();

        private Builder(Quack quack, String table, ColumnarLayout<O> layout) {
            if (layout.getColumns().isEmpty()) {
                throw new IllegalArgumentException("A journal needs at least a key column");
            }
            this.quack = quack;
            this.table = table;
            this.layout = layout;
        }

        /** An index over these columns, for finding the records of one session, say. */
        public Builder<O> index(String... columns) {
            if (columns.length == 0) {
                throw new IllegalArgumentException("An index needs at least one column");
            }
            indexes.add(Arrays.asList(columns));
            return this;
        }

        /**
         * The columns of the key, when it is not the layout's first column alone - a chat and a
         * number within it, say. Two records with the same key are refused.
         */
        public Builder<O> keyColumns(String... columns) {
            if (columns.length == 0) {
                throw new IllegalArgumentException("A key needs at least one column");
            }
            keyColumns.clear();
            keyColumns.addAll(Arrays.asList(columns));
            return this;
        }

        /**
         * The columns that say how new a record is - a time, say - for {@link Journal#search} to give
         * the newest first. Without them it goes by the key, highest first.
         */
        public Builder<O> newestBy(String... columns) {
            newest.clear();
            newest.addAll(Arrays.asList(columns));
            return this;
        }

        /**
         * A number column the journal fills when a record is appended with it null or 0: one more
         * than the highest so far, within the group {@link #within} names - 1, 2, 3... per chat, say.
         * It is worked out under the writer's turn, so two appends never get the same number.
         */
        public Builder<O> sequence(String column) {
            this.sequence = column;
            return this;
        }

        /** The columns whose values make the groups a {@link #sequence} counts within. */
        public Builder<O> within(String... columns) {
            if (sequence == null) {
                throw new IllegalStateException("within(...) says what a sequence counts within: name it with"
                        + " sequence(...) first");
            }
            within.clear();
            within.addAll(Arrays.asList(columns));
            return this;
        }

        List<String> keys() {
            return keyColumns.isEmpty() ? List.of(layout.getColumns().get(0).getName()) : List.copyOf(keyColumns);
        }

        /** Text columns {@link Journal#search} looks in. */
        public Builder<O> searchable(String... columns) {
            searchable.addAll(Arrays.asList(columns));
            return this;
        }

        /**
         * The statements that move the table from the version before to this one - usually
         * {@code ALTER TABLE ... ADD COLUMN}. Versions start at 1.
         */
        public Builder<O> migrate(int version, String... statements) {
            if (version < 1) {
                throw new IllegalArgumentException("Versions start at 1: " + version);
            }
            if (migrations.containsKey(version)) {
                throw new IllegalArgumentException("Version " + version + " has its migration already");
            }
            if (statements.length == 0) {
                throw new IllegalArgumentException("A migration needs at least one statement");
            }
            migrations.put(version, List.of(statements));
            return this;
        }

        /** Makes the table, or brings it up to the latest version, and checks it fits the layout. */
        public Journal<O> open() {
            List<ColumnDef> columns = layout.toColumnDefs();
            for (String column : searchable) {
                if (layout.getColumn(column) == null) {
                    throw new IllegalArgumentException("No column " + column + " to search in the layout");
                }
            }
            canonical(keyColumns);
            canonical(newest);
            canonical(within);
            if (sequence != null) {
                sequence = canonical(sequence);
            }
            List<String> keys = keys();
            List<String> named = new ArrayList<>(keys);
            named.addAll(newest);
            named.addAll(within);
            if (sequence != null) {
                named.add(sequence);
            }
            for (String column : named) {
                if (layout.getColumn(column) == null) {
                    throw new IllegalArgumentException("No column " + column + " in the layout of " + table + ": "
                            + layout.getColumns().stream().map(ColumnarLayout.Column::getName).toList());
                }
            }
            if (sequence != null) {
                Sequence.check(layout, sequence, within);
            }
            int latest = migrations.isEmpty() ? 0 : ((TreeMap<Integer, List<String>>) migrations).lastKey();
            TableWriter writer = new TableWriter(table, columns, false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
            int[] reached = new int[1];
            quack.inTransaction(tx -> {
                Connection connection = tx.connection();
                Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + VERSIONS
                        + " (name VARCHAR PRIMARY KEY, version INTEGER NOT NULL)");
                if (!writer.tableExists(connection)) {
                    writer.createTable(connection, keys.size() == 1 && keys.get(0).equals(columns.get(0).getName()));
                    if (keys.size() > 1 || !keys.get(0).equals(columns.get(0).getName())) {
                        Sql.execute(connection, "ALTER TABLE " + Sql.quote(table) + " ADD PRIMARY KEY ("
                                + String.join(", ", keys.stream().map(Sql::quote).toList()) + ")");
                    }
                    setVersion(connection, latest);
                    reached[0] = latest;
                }
                else {
                    reached[0] = versionOf(connection);
                    List<String> existing = primaryKey(connection);
                    if (!existing.isEmpty() && !lower(existing).equals(lower(keys))) {
                        throw new IllegalStateException(table + " is keyed by " + existing + ", not " + keys
                                + ": a migration can change the key, with ALTER TABLE.");
                    }
                }
            });
            if (reached[0] > latest) {
                throw new IllegalStateException(table + " is at version " + reached[0] + ", newer than the latest"
                        + " this application knows, " + latest + ": it was written by a later version of it.");
            }
            for (Map.Entry<Integer, List<String>> migration : migrations.entrySet()) {
                if (migration.getKey() <= reached[0]) {
                    continue;
                }
                int to = migration.getKey();
                try {
                    quack.inTransaction(tx -> {
                        for (String statement : migration.getValue()) {
                            Sql.execute(tx.connection(), statement);
                        }
                        setVersion(tx.connection(), to);
                    });
                }
                catch (RuntimeException e) {
                    throw new IllegalStateException("The migration of " + table + " to version " + to
                            + " failed, and the table is still at version " + reached[0] + ": " + rootMessage(e), e);
                }
                reached[0] = to;
            }
            try (Connection connection = quack.connection()) {
                writer.validateSchema(connection);
                for (List<String> index : indexes) {
                    StringBuilder name = new StringBuilder(table);
                    StringBuilder list = new StringBuilder();
                    for (int i = 0; i < index.size(); i++) {
                        name.append('_').append(index.get(i));
                        list.append(i == 0 ? "" : ", ").append(Sql.quote(index.get(i)));
                    }
                    Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote(name.append("_idx").toString())
                            + " ON " + Sql.quote(table) + " (" + list + ")");
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open " + table + ": " + e.getMessage(), e);
            }
            return new Journal<>(this, reached[0]);
        }

        /** The layout's own spelling of a column named in any case; a name it lacks is left as it is. */
        private String canonical(String name) {
            for (ColumnarLayout.Column<O, ?> column : layout.getColumns()) {
                if (column.getName().equalsIgnoreCase(name)) {
                    return column.getName();
                }
            }
            return name;
        }

        private void canonical(List<String> names) {
            names.replaceAll(this::canonical);
        }

        private List<String> primaryKey(Connection connection) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT constraint_column_names"
                    + " FROM duckdb_constraints() WHERE table_name = ? AND constraint_type = 'PRIMARY KEY'"
                    + " AND database_name = current_database() AND schema_name = current_schema()")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return List.of();
                    }
                    List<String> names = new ArrayList<>();
                    for (Object name : (Object[]) result.getArray(1).getArray()) {
                        names.add(String.valueOf(name));
                    }
                    return names;
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to read the key of " + table, e);
            }
        }

        private static List<String> lower(List<String> names) {
            return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
        }

        private int versionOf(Connection connection) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT version FROM " + VERSIONS
                    + " WHERE name = ?")) {
                statement.setString(1, table);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to read the version of " + table, e);
            }
        }

        private void setVersion(Connection connection, int version) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT OR REPLACE INTO " + VERSIONS
                    + " VALUES (?, ?)")) {
                statement.setString(1, table);
                statement.setInt(2, version);
                statement.executeUpdate();
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to record the version of " + table, e);
            }
        }

        private static String rootMessage(Throwable e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return String.valueOf(root.getMessage()).split("\n")[0];
        }
    }
}
