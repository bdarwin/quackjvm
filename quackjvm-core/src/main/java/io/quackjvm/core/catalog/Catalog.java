package io.quackjvm.core.catalog;

import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.Comments;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What is in the database, and what it means: the tables, their columns, what each column holds and
 * what it is measured in.
 *
 * <pre>
 * Catalog catalog = Catalog.of(connection);
 *
 * for (TableInfo table : catalog.tables()) { ... }
 * TableInfo car = catalog.describe("car");
 * Profile profile = catalog.profile("car");
 * </pre>
 *
 * <h2>Where the descriptions live</h2>
 *
 * <p>In the database, as DuckDB comments, so they survive in a file database, travel with it, and can
 * be read with SQL by anything at all - {@code SELECT comment FROM duckdb_columns()}. Not in a
 * registry of ours that has to be kept in step.</p>
 *
 * <p>A comment that carries a unit is stored as a small JSON object,
 * {@code {"description":"what it sold for","unit":"USD"}}, because a comment is one string and this
 * needs to be two. A comment written by anyone else - by hand, by a migration - is read as a plain
 * description, so nothing has to know about the convention to work with it.</p>
 */
public final class Catalog {

    private final Connection connection;

    private Catalog(Connection connection) {
        this.connection = connection;
    }

    public static Catalog of(Connection connection) {
        return new Catalog(connection);
    }

    // ---------- reading ----------

    /** Every table in the database, with its columns, in name order. */
    public List<TableInfo> tables() {
        List<TableInfo> tables = new ArrayList<>();
        for (String[] row : query("SELECT table_name, comment, estimated_size FROM duckdb_tables()"
                + " WHERE database_name = current_database() ORDER BY table_name", 3)) {
            Comments comment = Comments.parse(row[1]);
            tables.add(new TableInfo(row[0], comment.description(), rowsOf(row[2]), columnsOf(row[0], null)));
        }
        return tables;
    }

    /** One table, with its columns. */
    public TableInfo describe(String table) {
        return describe(table, null);
    }

    /**
     * One table, with the Java type of each column filled in from a layout - matched by column name,
     * so a column the layout does not have is left with a null Java type rather than guessed.
     */
    public TableInfo describe(String table, ColumnarLayout<?> layout) {
        List<String[]> rows = query("SELECT table_name, comment, estimated_size FROM duckdb_tables()"
                + " WHERE database_name = current_database() AND table_name = " + literal(table), 3);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No table named '" + table + "'. " + namesForMessage());
        }
        Comments comment = Comments.parse(rows.get(0)[1]);
        String description = comment.description() != null ? comment.description()
                : layout != null ? layout.getDescription() : null;
        return new TableInfo(table, description, rowsOf(rows.get(0)[2]), columnsOf(table, layout));
    }

    /** The names of the tables, for an error message or a prompt. */
    public List<String> tableNames() {
        List<String> names = new ArrayList<>();
        for (String[] row : query("SELECT table_name FROM duckdb_tables() WHERE database_name = current_database()"
                + " ORDER BY table_name", 1)) {
            names.add(row[0]);
        }
        return names;
    }

    /**
     * What is actually in a table: a row count, one summary per column, and a few rows to look at.
     *
     * <p>{@code SUMMARIZE} does the work, which means a full scan - see the numbers in
     * {@code docs/catalog.md}. Ask for it when something needs to decide what to query, not on every
     * request.</p>
     *
     * @param sampleRows how many rows to include as an example, zero for none
     */
    public Profile profile(String table, int sampleRows) {
        TableInfo info = describe(table);
        long rows = Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(table), List.of());
        List<ColumnProfile> columns = new ArrayList<>();
        for (String[] row : query("SUMMARIZE " + Sql.quote(table), 12)) {
            columns.add(new ColumnProfile(row[0], row[1], row[2], row[3], parseLong(row[4]), row[5], row[6],
                    parseDouble(row[11])));
        }
        List<Object[]> sample = new ArrayList<>();
        if (sampleRows > 0) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + Sql.quote(table)
                    + " LIMIT " + sampleRows);
                 ResultSet found = statement.executeQuery()) {
                int width = found.getMetaData().getColumnCount();
                while (found.next()) {
                    Object[] values = new Object[width];
                    for (int i = 0; i < width; i++) {
                        values[i] = found.getObject(i + 1);
                    }
                    sample.add(values);
                }
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to read a sample of " + table, e);
            }
        }
        return new Profile(table, info.description(), rows, columns, sample);
    }

    /** As {@link #profile(String, int)}, with five example rows. */
    public Profile profile(String table) {
        return profile(table, 5);
    }

    // ---------- writing ----------

    /** Records what a table holds, as a comment on the table. */
    public void describeTable(String table, String description) {
        Sql.execute(connection, Comments.tableStatement(table, description));
    }

    /** Records what a column holds, and optionally what it is measured in. */
    public void describeColumn(String table, String column, String description, String unit) {
        Sql.execute(connection, Comments.columnStatement(table,
                new ColumnDef(column, String.class).describedAs(description).measuredIn(unit)));
    }

    /**
     * Writes every description and unit a layout carries onto an existing table. Columns the layout
     * says nothing about are left alone.
     *
     * @return how many comments were written
     */
    public int apply(String table, ColumnarLayout<?> layout) {
        int written = 0;
        if (layout.getDescription() != null) {
            describeTable(table, layout.getDescription());
            written++;
        }
        for (ColumnarLayout.Column<?, ?> column : layout.getColumns()) {
            if (column.getDescription() != null || column.getUnit() != null) {
                describeColumn(table, column.getName(), column.getDescription(), column.getUnit());
                written++;
            }
        }
        return written;
    }

    /** Writes the descriptions and units carried by a set of column definitions. */
    public int apply(String table, List<ColumnDef> columns) {
        int written = 0;
        for (ColumnDef column : columns) {
            if (column.getDescription() != null || column.getUnit() != null) {
                describeColumn(table, column.getName(), column.getDescription(), column.getUnit());
                written++;
            }
        }
        return written;
    }

    // ---------- internals ----------

    private List<ColumnInfo> columnsOf(String table, ColumnarLayout<?> layout) {
        List<ColumnInfo> columns = new ArrayList<>();
        for (String[] row : query("SELECT column_name, data_type, is_nullable, comment, column_default"
                + " FROM duckdb_columns() WHERE database_name = current_database() AND table_name = "
                + literal(table) + " ORDER BY column_index", 5)) {
            Comments comment = Comments.parse(row[3]);
            Class<?> javaType = null;
            String unit = comment.unit();
            String description = comment.description();
            if (layout != null) {
                ColumnarLayout.Column<?, ?> column = layout.getColumn(row[0]);
                if (column != null) {
                    javaType = column.getType();
                    if (description == null) {
                        description = column.getDescription();
                    }
                    if (unit == null) {
                        unit = column.getUnit();
                    }
                }
            }
            if (javaType == null) {
                javaType = JavaTypes.forSqlType(row[1]);
            }
            columns.add(new ColumnInfo(row[0], row[1], javaType, "true".equalsIgnoreCase(row[2]), description,
                    unit, row[4]));
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("No table named '" + table + "'. " + namesForMessage());
        }
        return columns;
    }

    private String namesForMessage() {
        List<String> names = tableNames();
        return names.isEmpty() ? "The database has no tables." : "There is: " + String.join(", ", names) + ".";
    }

    private List<String[]> query(String sql, int columns) {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet found = statement.executeQuery()) {
            while (found.next()) {
                String[] row = new String[columns];
                for (int i = 0; i < columns; i++) {
                    row[i] = found.getString(i + 1);
                }
                rows.add(row);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the catalog: " + sql, e);
        }
        return rows;
    }

    private static long rowsOf(String estimate) {
        Long parsed = parseLong(estimate);
        return parsed == null ? -1 : parsed;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(Long.parseLong(value.trim()));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return value == null ? null : Double.valueOf(Double.parseDouble(value.trim().replace("%", "")));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** The Java type quackjvm would map a SQL type to, for a reader that has no layout. */
    static final class JavaTypes {

        private JavaTypes() {
        }

        static Class<?> forSqlType(String sqlType) {
            if (sqlType == null) {
                return null;
            }
            String type = sqlType.toUpperCase(java.util.Locale.ROOT);
            int bracket = type.indexOf('(');
            if (bracket > 0) {
                type = type.substring(0, bracket);
            }
            switch (type) {
                case "VARCHAR":
                case "CHAR":
                case "TEXT":
                    return String.class;
                case "BOOLEAN":
                    return Boolean.class;
                case "TINYINT":
                    return Byte.class;
                case "SMALLINT":
                    return Short.class;
                case "INTEGER":
                    return Integer.class;
                case "BIGINT":
                    return Long.class;
                case "HUGEINT":
                    return java.math.BigInteger.class;
                case "FLOAT":
                    return Float.class;
                case "DOUBLE":
                    return Double.class;
                case "DECIMAL":
                    return java.math.BigDecimal.class;
                case "UUID":
                    return java.util.UUID.class;
                case "BLOB":
                    return byte[].class;
                case "DATE":
                    return java.time.LocalDate.class;
                case "TIME":
                    return java.time.LocalTime.class;
                case "TIMESTAMP":
                    return java.time.LocalDateTime.class;
                case "TIMESTAMP WITH TIME ZONE":
                    return java.time.OffsetDateTime.class;
                default:
                    // LIST, STRUCT, MAP and anything else: no single Java type to name.
                    return null;
            }
        }
    }

    /** Whether quackjvm has a Java type for this SQL type at all. */
    public static boolean isMappable(String sqlType) {
        Class<?> type = JavaTypes.forSqlType(sqlType);
        return type != null && DuckDBTypes.isSupported(type);
    }
}
