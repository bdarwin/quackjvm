package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A question about a {@link MeasureTable}: which fields become rows, which field becomes the
 * columns, which values to include, and what unit to express them in.
 *
 * <pre>
 * measures.query().rows("a", "b").columns("point").where("unit", "U1").run(connection);
 *
 *   a  b     5y     10y    30y
 *   x  y   1.25    0.80   0.31
 *   x  z   0.42    0.19   0.05
 *
 * measures.query().rows("point").columns("b").where("a", "x").run(connection);
 * </pre>
 *
 * <p>Values are totalled per key first, and the dictionary joined only then: on ten million values
 * that is 20 ms against 227 for the other order, because the join and the columns are then applied
 * to thousands of rows rather than millions.</p>
 *
 * <p><b>Units are not added together.</b> If the measure has a unit field, a query must either put
 * it in the rows or the columns, narrow to one unit, or {@link #convertTo convert} - otherwise it is
 * refused, because a total across two units is a wrong number that looks right.</p>
 */
public final class MeasureQuery {

    private final MeasureTable table;
    /** Where to read from: the measure's own tables, or exported files. */
    private String keySource;
    private String valueSource;
    private final List<String> rows = new ArrayList<>();
    private String columnField;
    private final Map<String, List<String>> filters = new LinkedHashMap<>();
    private long[] records;
    private String targetUnit;
    private String ratesTable;
    private String fromColumn = "unit";
    private String toColumn = "to_unit";
    private String factorColumn = "factor";

    MeasureQuery(MeasureTable table) {
        this.table = table;
        this.keySource = Sql.quote(table.getKeyTable());
        this.valueSource = Sql.quote(table.getValueTable());
    }

    /**
     * Reads from files a measure was exported to, rather than from its tables - so that archived
     * values can be asked the same questions without loading them back in.
     *
     * @see MeasureTable#export
     */
    public MeasureQuery from(java.nio.file.Path directory) {
        this.keySource = MeasureTable.readParquet(directory, "key");
        this.valueSource = MeasureTable.readParquet(directory, "value");
        return this;
    }

    /** The fields to group by, one column each, in this order. */
    public MeasureQuery rows(String... fields) {
        for (String field : fields) {
            table.checkField(field);
            rows.add(field);
        }
        return this;
    }

    /** The field whose values become columns. Without one, there is a single total column. */
    public MeasureQuery columns(String field) {
        table.checkField(field);
        this.columnField = field;
        return this;
    }

    /** Keeps only values whose field is one of these. */
    public MeasureQuery where(String field, String... values) {
        table.checkField(field);
        if (values.length == 0) {
            throw new IllegalArgumentException("where(" + field + ") needs at least one value");
        }
        filters.computeIfAbsent(field, f -> new ArrayList<>()).addAll(List.of(values));
        return this;
    }

    /** Keeps only these records. */
    public MeasureQuery records(long... recordIds) {
        this.records = recordIds.clone();
        return this;
    }

    /**
     * Converts every value into one unit before adding it up, using a table of your own with a
     * from-unit, a to-unit and a factor: {@code value * factor}. Rows whose unit has no factor to
     * the target unit are left out, so an incomplete table shows as missing values rather than a
     * wrong total.
     *
     * @param unit  the unit to express the answer in
     * @param rates the table of factors, with columns {@code unit}, {@code to_unit} and {@code factor}
     */
    public MeasureQuery convertTo(String unit, String rates) {
        return convertTo(unit, rates, "unit", "to_unit", "factor");
    }

    /** As {@link #convertTo(String, String)}, with the rates table's own column names. */
    public MeasureQuery convertTo(String unit, String rates, String from, String to, String factor) {
        if (table.getUnitField() == null) {
            throw new IllegalStateException(table.getName() + " has no unit field, so there is nothing to convert");
        }
        this.targetUnit = unit;
        this.ratesTable = rates;
        this.fromColumn = from;
        this.toColumn = to;
        this.factorColumn = factor;
        return this;
    }

    /**
     * The SQL this query runs, and the values to bind to it. Handy for joining your own tables to
     * the answer, or for looking at what it does.
     */
    public Statement statement(Connection connection) {
        checkUnits();
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("WITH totals AS (SELECT key_id, sum(value) AS total FROM ")
                .append(valueSource);
        List<String> conditions = new ArrayList<>();
        if (records != null) {
            if (records.length == 0) {
                conditions.add("false");
            }
            else {
                conditions.add("record_id IN " + Sql.placeholders(records.length));
                for (long record : records) {
                    parameters.add(record);
                }
            }
        }
        if (!filters.isEmpty()) {
            StringBuilder keys = new StringBuilder("key_id IN (SELECT id FROM ")
                    .append(keySource).append(" WHERE ");
            appendFilters(keys, parameters);
            conditions.add(keys.append(')').toString());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" GROUP BY key_id) SELECT ");

        for (String field : rows) {
            sql.append("k.").append(Sql.quote(field)).append(", ");
        }
        String measure = ratesTable == null ? "t.total" : "t.total * r." + Sql.quote(factorColumn);
        if (columnField == null) {
            sql.append("sum(").append(measure).append(") AS total");
        }
        else {
            List<String> columns = columnValues(connection);
            if (columns.isEmpty()) {
                sql.append("sum(").append(measure).append(") AS total");
            }
            for (int i = 0; i < columns.size(); i++) {
                String value = columns.get(i);
                sql.append(i == 0 ? "" : ", ").append("sum(").append(measure).append(") FILTER (WHERE k.")
                        .append(Sql.quote(columnField)).append(" = ").append(literal(value)).append(") AS ")
                        .append(Sql.quote(value));
            }
        }
        sql.append(" FROM totals t JOIN ").append(keySource).append(" k ON k.id = t.key_id");
        if (ratesTable != null) {
            sql.append(" JOIN ").append(Sql.quote(ratesTable)).append(" r ON r.").append(Sql.quote(fromColumn))
                    .append(" = k.").append(Sql.quote(table.getUnitField())).append(" AND r.")
                    .append(Sql.quote(toColumn)).append(" = ?");
            parameters.add(targetUnit);
        }
        if (!rows.isEmpty()) {
            // The row fields by name, not GROUP BY ALL: ORDER BY then may use an expression over
            // them, which GROUP BY ALL - which groups only what the select list holds - refuses.
            sql.append(" GROUP BY ");
            for (int i = 0; i < rows.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append("k.").append(Sql.quote(rows.get(i)));
            }
            sql.append(" ORDER BY ");
            for (int i = 0; i < rows.size(); i++) {
                sql.append(i == 0 ? "" : ", ").append(orderExpression(rows.get(i)));
            }
        }
        return new Statement(sql.toString(), parameters);
    }

    /** Runs the query. The connection is closed when the rows are. */
    public Rows run(Connection ownedConnection) {
        Statement statement = statement(ownedConnection);
        return Rows.of(ownedConnection, statement.sql(), statement.parameters().toArray());
    }

    /** The SQL of this query, with its values written in - for reading, not for running. */
    public String sql(Connection connection) {
        Statement statement = statement(connection);
        String sql = statement.sql();
        for (Object parameter : statement.parameters()) {
            sql = sql.replaceFirst("\\?", parameter instanceof String text ? literal(text) : String.valueOf(parameter));
        }
        return sql;
    }

    /**
     * How to order by a field: by the order its values were declared in, if there is one, and by the
     * value itself otherwise. Text order puts "10y" before "1d", which is why the order exists.
     */
    private String orderExpression(String field) {
        List<String> order = table.orderOf(field);
        if (order.isEmpty()) {
            return "k." + Sql.quote(field);
        }
        StringBuilder list = new StringBuilder("array_position([");
        for (int i = 0; i < order.size(); i++) {
            list.append(i == 0 ? "" : ", ").append(literal(order.get(i)));
        }
        // Values the order does not name sort after the ones it does, among themselves by text.
        return list.append("], k.").append(Sql.quote(field)).append("), k.").append(Sql.quote(field)).toString();
    }

    /** The values that will become columns, in the field's declared order, or text order. */
    public List<String> columnValues(Connection connection) {
        if (columnField == null) {
            return List.of();
        }
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(Sql.quote(columnField)).append(" FROM ")
                .append(keySource);
        Map<String, List<String>> others = new LinkedHashMap<>(filters);
        others.remove(columnField);
        if (!others.isEmpty()) {
            sql.append(" WHERE ");
            appendFilters(sql, parameters, others);
        }
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            Sql.bindAll(statement, parameters, 1);
            try (ResultSet found = statement.executeQuery()) {
                while (found.next()) {
                    values.add(found.getString(1));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the columns of " + table.getName(), e);
        }
        List<String> order = table.orderOf(columnField);
        values.sort((left, right) -> {
            int leftAt = order.indexOf(left);
            int rightAt = order.indexOf(right);
            if (leftAt != rightAt) {
                // Not in the declared order: after those that are.
                return Integer.compare(leftAt < 0 ? Integer.MAX_VALUE : leftAt, rightAt < 0 ? Integer.MAX_VALUE : rightAt);
            }
            return left.compareTo(right);
        });
        return values;
    }

    private void appendFilters(StringBuilder sql, List<Object> parameters) {
        appendFilters(sql, parameters, filters);
    }

    private void appendFilters(StringBuilder sql, List<Object> parameters, Map<String, List<String>> which) {
        boolean first = true;
        for (Map.Entry<String, List<String>> filter : which.entrySet()) {
            sql.append(first ? "" : " AND ").append(Sql.quote(filter.getKey())).append(" IN ")
                    .append(Sql.placeholders(filter.getValue().size()));
            parameters.addAll(filter.getValue());
            first = false;
        }
    }

    /**
     * A total may not add values in different units. Either the unit is one of the rows or the
     * columns, or the query keeps one unit, or it converts.
     */
    private void checkUnits() {
        String unit = table.getUnitField();
        if (unit == null || ratesTable != null || rows.contains(unit) || unit.equals(columnField)) {
            return;
        }
        List<String> kept = filters.get(unit);
        if (kept != null && kept.size() == 1) {
            return;
        }
        throw new IllegalStateException("This would add up values in different units, which is never right."
                + " Put '" + unit + "' in rows(...) or columns(...), narrow to one with where(\"" + unit
                + "\", ...), or convertTo(unit, rates).");
    }

    /** A value from the dictionary, as a SQL string - it can hold a quote. */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** SQL and the values to bind to it. */
    public record Statement(String sql, List<Object> parameters) {
    }
}
