package io.quackjvm.core.journal;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.page.Pages;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * </pre>
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
 * <p>One writer: the methods that write take turns. Readers need no turn - DuckDB gives each query
 * the rows committed when it began - so a page read while a stream appends sees whole rows only.
 * Each write commits on its own; DuckDB's write-ahead log makes a write that was cut off by the
 * process ending be either all there or not there when the file is opened again.</p>
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
    private final String key;
    private final List<String> searchable;
    private final TableWriter writer;
    private final int version;

    private Journal(Quack quack, String table, ColumnarLayout<O> layout, List<String> searchable, int version) {
        this.quack = quack;
        this.table = table;
        this.layout = layout;
        this.columns = layout.toColumnDefs();
        this.key = columns.get(0).getName();
        this.searchable = searchable;
        this.writer = new TableWriter(table, columns, false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
        this.version = version;
    }

    /** A journal kept in this table, of objects of this layout; the layout's first column is the key. */
    public static <O> Builder<O> builder(Quack quack, String table, ColumnarLayout<O> layout) {
        return new Builder<>(quack, table, layout);
    }

    // ---------- writing ----------

    /** Adds a record. One whose key is already there is refused: a journal adds, {@link #update} changes. */
    public synchronized void append(O record) {
        append(List.of(record));
    }

    /** Adds records in one transaction: all of them, or none if one is refused. */
    public synchronized void append(Iterable<O> records) {
        quack.inTransaction(tx -> {
            List<Object[]> rows = new ArrayList<>();
            for (O record : records) {
                rows.add(layout.toRow(record));
            }
            try {
                writer.write(tx.connection(), rows.iterator(), false);
            }
            catch (RuntimeException e) {
                throw refused(e);
            }
        });
    }

    /**
     * Changes some columns of the record with this key - the text of a message as more of it
     * arrives, say. The key itself cannot change.
     *
     * @return false if there is no record with the key
     */
    public synchronized boolean update(Object key, Map<String, ?> changes) {
        if (changes.isEmpty()) {
            return get(key).isPresent();
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(Sql.quote(table)).append(" SET ");
        List<Object> values = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, ?> change : new TreeMap<>(changes).entrySet()) {
            String column = column(change.getKey());
            if (column.equals(this.key)) {
                throw new IllegalArgumentException("The key, " + this.key + ", cannot be changed");
            }
            sql.append(i++ == 0 ? "" : ", ").append(Sql.quote(column)).append(" = ?");
            values.add(change.getValue());
        }
        sql.append(" WHERE ").append(Sql.quote(this.key)).append(" = ?");
        values.add(key);
        return quack.execute(sql.toString(), values.toArray()) > 0;
    }

    // ---------- reading ----------

    /** The record with this key. */
    public Optional<O> get(Object key) {
        List<O> found = select(" WHERE " + Sql.quote(this.key) + " = ?", List.of(key), -1);
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
     * The records in which every word appears, in any case, in one searchable column - newest first,
     * by key. Each hit says which column, with the text around the first word.
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
        where.append(" ORDER BY ").append(Sql.quote(key)).append(" DESC");
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

    // ---------- underneath ----------

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
            if (message.contains("Duplicate key") || message.contains("violates primary key")) {
                return new IllegalArgumentException("A record with that " + key + " is already in " + table
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
            int latest = migrations.isEmpty() ? 0 : ((TreeMap<Integer, List<String>>) migrations).lastKey();
            TableWriter writer = new TableWriter(table, columns, false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
            int[] reached = new int[1];
            quack.inTransaction(tx -> {
                Connection connection = tx.connection();
                Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + VERSIONS
                        + " (name VARCHAR PRIMARY KEY, version INTEGER NOT NULL)");
                if (!writer.tableExists(connection)) {
                    writer.createTable(connection, true);
                    setVersion(connection, latest);
                    reached[0] = latest;
                }
                else {
                    reached[0] = versionOf(connection);
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
            return new Journal<>(quack, table, layout, List.copyOf(searchable), reached[0]);
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
