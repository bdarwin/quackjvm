package io.quackjvm.agent.catalog;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.internal.SqlScanner;
import io.quackjvm.core.json.JsonReader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Which columns of which tables a query reads, without running it - for filling a lazy column
 * cache with just what a query needs before running it.
 *
 * <pre>
 * try (QueryColumns planner = QueryColumns.of(connection)) {
 *     QueryColumns.Read read = planner.read("SELECT host, sum(metric_7) FROM t WHERE metric_9 &gt; 3 GROUP BY host");
 *     if (read.isKnown()) {
 *         read.columns();        // {t=[metric_9, host, metric_7]}
 *     }
 *     else {
 *         read.reason();         // why not; assume every column
 *     }
 * }
 * </pre>
 *
 * <h2>How</h2>
 *
 * <p>DuckDB plans the query and the plan says what each table scan reads: {@code EXPLAIN (FORMAT
 * JSON)}, whose {@code SEQ_SCAN} nodes carry the table and its projected columns. The planning
 * happens in a database of its own - in memory, with no file access, holding empty tables with the
 * source's columns and its views - so it never touches the data and never runs the query. Three
 * optimizers are turned off there, because each hides a column from the plan: statistics
 * propagation and empty-result pull-up see the tables are empty and drop the scans, and filter
 * pushdown moves a filter's columns out of the projections. A column a scan names only in its
 * remaining filter text is counted as read too: reading one too many is safe for a cache, one too
 * few is not.</p>
 *
 * <h2>When it cannot tell</h2>
 *
 * <p>{@link Read#isKnown()} is false, with a reason, when the query cannot be planned there, or
 * its plan cannot be trusted: more than one statement; a {@code PIVOT}, which DuckDB rewrites into
 * several statements and plans only the last of - on 2.0.0 the plan of a pivot leaves out the very
 * columns it pivots on and sums; a table that is not in the source's {@code main} schema; anything
 * reading a file, which the planning database cannot reach. The answer to "cannot tell" is every
 * column.</p>
 *
 * <p>The planning database copies the source's schema when it is made; {@link #refresh()} copies it
 * again after the source's tables change. Measured on DuckDB 2.0.0 with a table of 6,000 columns:
 * the planner is made in 82 ms, a query reading three of them is answered in 3 ms, and
 * {@code SELECT *} in 158 ms.</p>
 */
public final class QueryColumns implements AutoCloseable {

    /** What a query reads, or why that cannot be told. */
    public static final class Read {

        private final Map<String, Set<String>> columns;
        private final String reason;

        private Read(Map<String, Set<String>> columns, String reason) {
            this.columns = columns;
            this.reason = reason;
        }

        /** Whether the columns are known; when not, assume every column of every table. */
        public boolean isKnown() {
            return reason == null;
        }

        /**
         * For each table scanned, the columns read from it, in the order the plan names them. A table
         * scanned for its rows alone - {@code count(*)} - maps to an empty set. Empty when unknown.
         */
        public Map<String, Set<String>> columns() {
            return columns;
        }

        /** Why the columns are not known, or null when they are. */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return isKnown() ? "Read" + columns : "Read[unknown: " + reason + "]";
        }
    }

    private static final java.util.regex.Pattern PIVOT =
            java.util.regex.Pattern.compile("\\bPIVOT\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final String OPTIMIZERS_THAT_HIDE_COLUMNS =
            "statistics_propagation,empty_result_pullup,filter_pushdown";

    private final Connection source;
    private Connection planner;
    private Map<String, List<String>> tableColumns = new LinkedHashMap<>();
    private final List<java.util.function.Consumer<Connection>> functions = new ArrayList<>();

    private QueryColumns(Connection source) {
        this.source = source;
        refresh();
    }

    /** A planner with the schema of the database this connection belongs to. */
    public static QueryColumns of(Connection source) {
        return new QueryColumns(source);
    }

    /** As {@link #of(Connection)}, for a {@link Quack}'s database. */
    public static QueryColumns of(Quack quack) {
        return of(quack.sharedConnection());
    }

    /** Copies the source's tables and views again, after they change. */
    public synchronized void refresh() {
        closePlanner();
        Properties settings = new Properties();
        settings.setProperty("enable_external_access", "false");
        settings.setProperty("autoload_known_extensions", "false");
        settings.setProperty("autoinstall_known_extensions", "false");
        try {
            planner = DriverManager.getConnection("jdbc:duckdb:", settings);
            tableColumns = copySchema();
            try (Statement statement = planner.createStatement()) {
                statement.execute("SET disabled_optimizers = '" + OPTIMIZERS_THAT_HIDE_COLUMNS + "'");
            }
            for (java.util.function.Consumer<Connection> function : functions) {
                function.accept(planner);
            }
        }
        catch (SQLException e) {
            closePlanner();
            throw new IllegalStateException("Failed to make a database to plan queries in", e);
        }
    }

    /**
     * Registers a Java function on the database queries are planned in, so that a query calling it
     * can be planned - {@code planner.register(c -> Udfs.register(c, "label", String.class, Long.class,
     * fn))}. Kept across {@link #refresh()}. Planning reads no rows, so the function is not called
     * for any.
     */
    public synchronized void register(java.util.function.Consumer<Connection> function) {
        functions.add(function);
        function.accept(planner);
    }

    /**
     * What the query reads.
     *
     * @param parameters values for its {@code ?} placeholders, which the plan can depend on
     */
    public synchronized Read read(String sql, Object... parameters) {
        if (sql == null || SqlScanner.isBlankTail(sql)) {
            return unknown("there is no statement");
        }
        if (SqlScanner.hasStatementSeparator(sql)) {
            // Preparing several statements would run all but the last - here, in the planner, but
            // still: and only one could be described.
            return unknown("it is more than one statement");
        }
        if (PIVOT.matcher(sql).find()) {
            // DuckDB rewrites a pivot into several statements and plans only the last: on 2.0.0 the
            // plan of PIVOT t ON host USING sum(x) names every column but host and x - exactly the
            // two it needs. Measured, so a pivot anywhere in the text is not answered. A string that
            // merely says pivot is not answered either, which is the safe way to be wrong.
            return unknown("it pivots, and DuckDB plans a pivot as several statements, of which the plan shows only"
                    + " the last");
        }
        String body = sql.trim().replaceAll(";\\s*$", "");
        List<Object> plans = new ArrayList<>();
        try (PreparedStatement statement = planner.prepareStatement("EXPLAIN (FORMAT JSON) " + body)) {
            for (int i = 0; i < parameters.length; i++) {
                DuckDBTypes.bind(statement, i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    plans.add(JsonReader.parse(rows.getString(2)));
                }
            }
        }
        catch (SQLException | RuntimeException e) {
            return unknown("DuckDB could not plan it here: " + firstLine(e));
        }
        Map<String, Set<String>> read = new LinkedHashMap<>();
        for (Object plan : plans) {
            collect(plan, read);
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        read.forEach((table, columns) -> frozen.put(table, Collections.unmodifiableSet(columns)));
        return new Read(Collections.unmodifiableMap(frozen), null);
    }

    // ---------- the plan ----------

    @SuppressWarnings("unchecked")
    private void collect(Object node, Map<String, Set<String>> read) {
        if (node instanceof List<?> list) {
            for (Object child : list) {
                collect(child, read);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        Object extra = map.get("extra_info");
        if ("SEQ_SCAN".equals(map.get("name")) && extra instanceof Map<?, ?> info) {
            String table = tableName(String.valueOf(info.get("Table")));
            Set<String> columns = read.computeIfAbsent(table, t -> new LinkedHashSet<>());
            columns.addAll(names(info.get("Projections")));
            Object filters = info.get("Filters");
            if (filters != null) {
                // Over rather than under: any of the table's columns the filter text names.
                String text = String.join("\n", names(filters));
                for (String column : tableColumns.getOrDefault(table, List.of())) {
                    if (mentions(text, column)) {
                        columns.add(column);
                    }
                }
            }
        }
        collect(map.get("children"), read);
    }

    /** {@code memory.main.t} is {@code t}; another schema keeps its name. */
    private static String tableName(String qualified) {
        String[] parts = qualified.split("\\.", 3);
        if (parts.length == 3 && "main".equals(parts[1])) {
            return parts[2];
        }
        return parts.length == 3 ? parts[1] + "." + parts[2] : qualified;
    }

    /** The plan says "" for none, a string for one, a list for several. */
    private static List<String> names(Object projections) {
        List<String> names = new ArrayList<>();
        if (projections instanceof List<?> list) {
            for (Object name : list) {
                names.add(String.valueOf(name));
            }
        }
        else if (projections != null && !String.valueOf(projections).isEmpty()) {
            for (String name : String.valueOf(projections).split("\n")) {
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static boolean mentions(String text, String column) {
        int at = text.indexOf(column);
        while (at >= 0) {
            boolean before = at == 0 || !isIdentifier(text.charAt(at - 1));
            int end = at + column.length();
            boolean after = end >= text.length() || !isIdentifier(text.charAt(end));
            if (before && after) {
                return true;
            }
            at = text.indexOf(column, at + 1);
        }
        return false;
    }

    private static boolean isIdentifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---------- the planning database ----------

    /** Empty tables with the source's columns, then its views; the columns of each table. */
    private Map<String, List<String>> copySchema() throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, StringBuilder> tables = new LinkedHashMap<>();
        try (PreparedStatement statement = source.prepareStatement("SELECT table_name, column_name, data_type"
                + " FROM duckdb_columns() WHERE database_name = current_database() AND schema_name = 'main'"
                + " AND NOT internal AND table_name IN (SELECT table_name FROM duckdb_tables()"
                + " WHERE database_name = current_database() AND schema_name = 'main')"
                + " ORDER BY table_name, column_index");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String table = rows.getString(1);
                String column = rows.getString(2);
                columns.computeIfAbsent(table, t -> new ArrayList<>()).add(column);
                StringBuilder ddl = tables.computeIfAbsent(table, t -> new StringBuilder());
                ddl.append(ddl.length() == 0 ? "" : ", ").append(quote(column)).append(' ').append(rows.getString(3));
            }
        }
        try (Statement statement = planner.createStatement()) {
            for (Map.Entry<String, StringBuilder> table : tables.entrySet()) {
                statement.execute("CREATE TABLE " + quote(table.getKey()) + " (" + table.getValue() + ")");
            }
        }
        List<String> views = new ArrayList<>();
        try (PreparedStatement statement = source.prepareStatement("SELECT sql FROM duckdb_views()"
                + " WHERE database_name = current_database() AND schema_name = 'main' AND NOT internal");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                views.add(rows.getString(1));
            }
        }
        // A view can be made of views: create what can be, then try the rest again, until nothing
        // more can be. A view over something the planner cannot see stays out, and a query over it
        // cannot be planned - which says so.
        boolean progress = true;
        while (!views.isEmpty() && progress) {
            progress = false;
            for (java.util.Iterator<String> it = views.iterator(); it.hasNext(); ) {
                try (Statement statement = planner.createStatement()) {
                    statement.execute(it.next());
                    it.remove();
                    progress = true;
                }
                catch (SQLException notYet) {
                    // depends on a view not made yet, or on something out of reach
                }
            }
        }
        return columns;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static Read unknown(String reason) {
        return new Read(Map.of(), "Cannot tell which columns it reads: " + reason + ". Assume every column.");
    }

    private static String firstLine(Exception e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private void closePlanner() {
        if (planner != null) {
            try {
                planner.close();
            }
            catch (SQLException ignored) {
                // nothing to keep
            }
            planner = null;
        }
    }

    @Override
    public synchronized void close() {
        closePlanner();
    }
}
