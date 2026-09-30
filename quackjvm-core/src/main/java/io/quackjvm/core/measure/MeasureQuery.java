package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * measures.query().from(sept20, sept21).rows("a").columns("point").run(connection);
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
    /** Set when reading archived files instead of the live tables. */
    private List<String> files;
    /** Set when reading the contributions as they stood at a point in time. */
    private Instant asOf;
    private MeasureHistory history;
    private boolean showZeros;
    private boolean latestPerKey;
    private final List<String> rows = new ArrayList<>();
    private String columnField;
    private final Map<String, List<String>> filters = new LinkedHashMap<>();
    private long[] records;
    private List<String> partFilter = List.of();
    private List<String> writerFilter = List.of();
    private String targetUnit;
    private String ratesTable;
    private String fromColumn = "unit";
    private String toColumn = "to_unit";
    private String factorColumn = "factor";

    MeasureQuery(MeasureTable table) {
        this.table = table;
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

    /** Keeps only values written under these parts. */
    public MeasureQuery parts(String... parts) {
        if (parts.length == 0) {
            throw new IllegalArgumentException("parts() needs at least one");
        }
        this.partFilter = List.of(parts);
        return this;
    }

    /** Keeps only values written by these writers. */
    public MeasureQuery writtenBy(String... writers) {
        if (writers.length == 0) {
            throw new IllegalArgumentException("writtenBy() needs at least one");
        }
        this.writerFilter = List.of(writers);
        return this;
    }

    /**
     * Reads archived files rather than the live tables - one part or many at once, local or remote -
     * so archived values answer the same questions without being read back in.
     *
     * <p>A field a file does not have, because it was written before that field existed, takes its
     * default. Anything DuckDB can read works: a path, a glob, {@code s3://}, {@code http://}.</p>
     *
     * @see MeasureTable#archive
     */
    public MeasureQuery from(String... locations) {
        if (locations.length == 0) {
            throw new IllegalArgumentException("from() needs at least one file");
        }
        this.files = List.of(locations);
        return this;
    }

    /**
     * The measure as it stood at a point in time, from the contributions rather than the state: the
     * sum of everything contributed from the last full set at or before that point, up to it.
     *
     * <pre>
     * measures.query().asOf(nineOClock, history).rows("a").columns("point").run(connection);
     * </pre>
     *
     * <p>Reads the history and anything still waiting in the outbox, so a point a moment ago answers
     * the same whether the shipper has run or not. A contribution that has been shipped but not yet
     * cleared from the outbox is counted once.</p>
     *
     * <p>Keys whose contributions cancel out are left out, as a key with no value at all would be;
     * {@link #showZeros()} keeps them.</p>
     */
    public MeasureQuery asOf(Instant at, MeasureHistory history) {
        if (at == null || history == null) {
            throw new IllegalArgumentException("asOf needs a point in time and the history to read it from");
        }
        this.asOf = at;
        this.history = history;
        return this;
    }

    /** Keeps keys whose contributions cancel out, as zeros, rather than leaving them out. */
    public MeasureQuery showZeros() {
        this.showZeros = true;
        return this;
    }

    /**
     * Where two writers wrote the same value, keeps the one written last rather than adding both up.
     * Measured at 33.8 ms against 4.5 on two million values, so ask for it only when writers really
     * do overlap.
     */
    public MeasureQuery latestPerKey() {
        this.latestPerKey = true;
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
     * the answer, or for seeing what it does.
     */
    public Statement statement(Connection connection) {
        checkUnits();
        List<Object> parameters = new ArrayList<>();
        String measure = ratesTable == null ? "k.total" : "k.total * r." + Sql.quote(factorColumn);
        StringBuilder sql = new StringBuilder(asOf != null ? asOfTotals(connection, parameters)
                : files == null ? liveTotals(parameters)
                : fileTotals(connection, parameters));
        sql.append(" SELECT ");
        for (String field : rows) {
            sql.append("k.").append(Sql.quote(field)).append(", ");
        }
        List<String> columns = columnField == null ? List.of() : columnValues(connection);
        if (columns.isEmpty()) {
            sql.append("sum(").append(measure).append(") AS total");
        }
        else {
            for (int i = 0; i < columns.size(); i++) {
                String value = columns.get(i);
                sql.append(i == 0 ? "" : ", ").append("sum(").append(measure).append(") FILTER (WHERE k.")
                        .append(Sql.quote(columnField)).append(" = ").append(literal(value)).append(") AS ")
                        .append(Sql.quote(value));
            }
        }
        sql.append(" FROM totals k");
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

    /**
     * Totals per key from the live tables, the dictionary joined on afterwards - the order that
     * measured 20 ms against 227 on ten million values.
     */
    private String liveTotals(List<Object> parameters) {
        List<String> conditions = rowConditions(parameters, null);
        if (!filters.isEmpty()) {
            StringBuilder keys = new StringBuilder("key_id IN (SELECT id FROM ")
                    .append(Sql.quote(table.getKeyTable())).append(" WHERE ");
            appendFilters(keys, parameters, filters);
            conditions.add(keys.append(')').toString());
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        StringBuilder sql = new StringBuilder("WITH ");
        String source = Sql.quote(table.getValueTable());
        if (latestPerKey) {
            sql.append("latest AS (SELECT record_id, key_id, value FROM (SELECT record_id, key_id, value,"
                            + " row_number() OVER (PARTITION BY record_id, key_id ORDER BY written_at DESC) AS newest FROM ")
                    .append(source).append(where).append(") WHERE newest = 1), ");
            source = "latest";
            where = "";
        }
        return sql.append("perKey AS (SELECT key_id, sum(value) AS total FROM ").append(source).append(where)
                .append(" GROUP BY key_id), totals AS (SELECT ").append(table.fieldList("d"))
                .append(", t.total FROM perKey t JOIN ").append(Sql.quote(table.getKeyTable()))
                .append(" d ON d.id = t.key_id)").toString();
    }

    /**
     * Totals as of a point in time, from the contributions: everything from the last full set at or
     * before that point, up to it, added together.
     *
     * <p>The ledger is read from the history and from the live database at once, because the live one
     * holds refreshes not yet shipped and may have forgotten old ones the history keeps. Summing is
     * what makes this right when the same value was published twice - measured at 15.8 ms where
     * keeping the newest row per key with a window was 24.5.</p>
     */
    private String asOfTotals(Connection connection, List<Object> parameters) {
        if (latestPerKey) {
            throw new IllegalStateException("latestPerKey() has nothing to do as of a point in time:"
                    + " contributions are added up, which is what makes a value published twice come out right");
        }
        String contributions = history.contributionTable(table);
        history.checkNotPruned(connection, table, asOf, files == null ? null : MeasureTable.readParquet(files));
        List<String> present = MeasureTable.columnsOfQuery(connection, contributions);
        String at = MeasureTable.literal(LocalDateTime.ofInstant(asOf, ZoneOffset.UTC).toString()) + "::TIMESTAMP";
        String ledger = Sql.quote(MeasureRefresh.LEDGER);
        String measure = MeasureTable.literal(table.getName());
        // Exported files carry the refresh each row belongs to, so they can be read as of a point too -
        // which is how a point the history has pruned is still answerable, and how files alone are.
        List<String> inFiles = files == null ? List.of()
                : MeasureTable.columnsOfQuery(connection, MeasureTable.readParquet(files));
        StringBuilder sql = new StringBuilder("WITH ledger AS (SELECT refresh_id, refreshed_at, seq, kind FROM ")
                .append(history.refreshTable()).append(" WHERE measure = ").append(measure)
                .append(" UNION SELECT refresh_id, refreshed_at, seq, kind FROM ").append(ledger)
                .append(" WHERE measure = ").append(measure);
        if (files != null) {
            sql.append(" UNION SELECT refresh_id, refreshed_at, seq, kind FROM ")
                    .append(MeasureTable.readParquet(files));
        }
        sql.append("), since AS (SELECT coalesce(max(seq), 0) AS seq FROM ledger WHERE kind = 'full'")
                .append(" AND refreshed_at <= ").append(at)
                .append("), included AS (SELECT l.refresh_id FROM ledger l, since s WHERE l.refreshed_at <= ")
                .append(at).append(" AND l.seq >= s.seq), ");

        StringBuilder fields = new StringBuilder();
        StringBuilder fromDictionary = new StringBuilder();
        List<String> names = table.getFields();
        for (int i = 0; i < names.size(); i++) {
            fields.append(i == 0 ? "" : ", ").append(fieldExpression(names.get(i), present)).append(" AS ")
                    .append(Sql.quote(names.get(i)));
            fromDictionary.append(i == 0 ? "" : ", ").append("d.").append(Sql.quote(names.get(i)));
        }
        // The history, and whatever is still in the outbox - skipping outbox rows whose refresh has
        // been shipped, which is what a crash between the shipper's two transactions leaves behind.
        String onRecords = records == null ? "" : records.length == 0 ? " AND false"
                : " AND record_id IN " + inList(records);
        sql.append("src AS (SELECT ").append(fields).append(", record_id, value FROM ").append(contributions)
                .append(" WHERE refresh_id IN (SELECT refresh_id FROM included)").append(onRecords)
                .append(" UNION ALL SELECT ").append(fromDictionary).append(", c.record_id, c.value FROM ")
                .append(Sql.quote(table.getContributionTable())).append(" c JOIN ")
                .append(Sql.quote(table.getKeyTable())).append(" d ON d.id = c.key_id")
                .append(" WHERE c.refresh_id IN (SELECT refresh_id FROM included)")
                .append(onRecords.replace(" record_id", " c.record_id"))
                .append(" AND NOT EXISTS (SELECT 1 FROM ")
                .append(history.refreshTable()).append(" h WHERE h.refresh_id = c.refresh_id AND h.measure = ")
                .append(measure).append(")");
        if (files != null) {
            StringBuilder fromFiles = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                fromFiles.append(i == 0 ? "" : ", ").append(fieldExpression(names.get(i), inFiles)).append(" AS ")
                        .append(Sql.quote(names.get(i)));
            }
            // A refresh the history still holds is not taken from the files as well.
            sql.append(" UNION ALL SELECT ").append(fromFiles).append(", record_id, value FROM ")
                    .append(MeasureTable.readParquet(files))
                    .append(" f WHERE f.refresh_id IN (SELECT refresh_id FROM included)")
                    .append(onRecords.replace(" record_id", " f.record_id"))
                    .append(" AND NOT EXISTS (SELECT 1 FROM ").append(contributions)
                    .append(" h WHERE h.refresh_id = f.refresh_id)");
        }
        sql.append("), ");

        List<String> conditions = new ArrayList<>();
        if (!filters.isEmpty()) {
            StringBuilder fieldFilters = new StringBuilder();
            appendFilters(fieldFilters, parameters, filters);
            conditions.add("(" + fieldFilters + ")");
        }
        sql.append("totals AS (SELECT ").append(table.fieldList(null)).append(", sum(value) AS total FROM src");
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" GROUP BY ").append(table.fieldList(null));
        if (!showZeros) {
            sql.append(" HAVING sum(value) <> 0");
        }
        return sql.append(")").toString();
    }

    /** The same from archived files, where every row carries its key fields already. */
    private String fileTotals(Connection connection, List<Object> parameters) {
        List<String> present = MeasureTable.columnsOfQuery(connection, MeasureTable.readParquet(files));
        StringBuilder select = new StringBuilder();
        List<String> fields = table.getFields();
        for (int i = 0; i < fields.size(); i++) {
            select.append(i == 0 ? "" : ", ").append(fieldExpression(fields.get(i), present)).append(" AS ")
                    .append(Sql.quote(fields.get(i)));
        }
        List<String> conditions = rowConditions(parameters, present);
        if (!filters.isEmpty()) {
            StringBuilder fieldFilters = new StringBuilder();
            appendFilters(fieldFilters, parameters, filters, present);
            conditions.add("(" + fieldFilters + ")");
        }
        StringBuilder sql = new StringBuilder("WITH src AS (SELECT ").append(select)
                .append(", record_id, value").append(present.contains("written_at") ? ", written_at" : "")
                .append(" FROM ").append(MeasureTable.readParquet(files));
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append("), ");
        String source = "src";
        if (latestPerKey && present.contains("written_at")) {
            sql.append("latest AS (SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY record_id, ")
                    .append(table.fieldList(null)).append(" ORDER BY written_at DESC) AS newest FROM src)")
                    .append(" WHERE newest = 1), ");
            source = "latest";
        }
        return sql.append("totals AS (SELECT ").append(table.fieldList(null)).append(", sum(value) AS total FROM ")
                .append(source).append(" GROUP BY ").append(table.fieldList(null)).append(")").toString();
    }

    /** A field as it can be read from the files: its own column, or its default where absent. */
    private String fieldExpression(String field, List<String> present) {
        String fallback = table.defaultOf(field);
        if (!present.contains(field)) {
            if (fallback == null) {
                throw new IllegalStateException("Those files have no '" + field + "', and it has no default:"
                        + " field(\"" + field + "\", \"...\") says what older values should read as");
            }
            return literal(fallback);
        }
        return fallback == null ? Sql.quote(field) : "coalesce(" + Sql.quote(field) + ", " + literal(fallback) + ")";
    }

    /** Conditions on the value rows themselves: records, parts, writers. */
    private List<String> rowConditions(List<Object> parameters, List<String> present) {
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
        if (!partFilter.isEmpty() && (present == null || present.contains("part"))) {
            conditions.add("part IN " + Sql.placeholders(partFilter.size()));
            parameters.addAll(partFilter);
        }
        if (!writerFilter.isEmpty() && (present == null || present.contains("written_by"))) {
            conditions.add("written_by IN " + Sql.placeholders(writerFilter.size()));
            parameters.addAll(writerFilter);
        }
        return conditions;
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

    /** The values that will become columns, in the field's declared order, or text order. */
    public List<String> columnValues(Connection connection) {
        if (columnField == null) {
            return List.of();
        }
        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ");
        // Every filter, the one on the column field included: asking for one point means one column.
        Map<String, List<String>> others = new LinkedHashMap<>(filters);
        if (asOf != null) {
            // From what the answer will actually hold, so a key that has cancelled out brings no
            // column with it.
            sql = new StringBuilder(asOfTotals(connection, parameters)).append(" SELECT DISTINCT ")
                    .append(Sql.quote(columnField)).append(" FROM totals");
        }
        else if (files == null) {
            sql.append(Sql.quote(columnField)).append(" FROM ").append(Sql.quote(table.getKeyTable()));
            if (!others.isEmpty()) {
                sql.append(" WHERE ");
                appendFilters(sql, parameters, others);
            }
        }
        else {
            List<String> present = MeasureTable.columnsOfQuery(connection, MeasureTable.readParquet(files));
            sql.append(fieldExpression(columnField, present)).append(" FROM ").append(MeasureTable.readParquet(files));
            List<String> conditions = rowConditions(parameters, present);
            if (!others.isEmpty()) {
                StringBuilder fieldFilters = new StringBuilder();
                appendFilters(fieldFilters, parameters, others, present);
                conditions.add("(" + fieldFilters + ")");
            }
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ").append(String.join(" AND ", conditions));
            }
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
                // Values the order does not name sort after the ones it does.
                return Integer.compare(leftAt < 0 ? Integer.MAX_VALUE : leftAt,
                        rightAt < 0 ? Integer.MAX_VALUE : rightAt);
            }
            return left.compareTo(right);
        });
        return values;
    }

    private void appendFilters(StringBuilder sql, List<Object> parameters, Map<String, List<String>> which) {
        appendFilters(sql, parameters, which, null);
    }

    private void appendFilters(StringBuilder sql, List<Object> parameters, Map<String, List<String>> which,
                               List<String> present) {
        boolean first = true;
        for (Map.Entry<String, List<String>> filter : which.entrySet()) {
            sql.append(first ? "" : " AND ")
                    .append(present == null ? Sql.quote(filter.getKey()) : fieldExpression(filter.getKey(), present))
                    .append(" IN ").append(Sql.placeholders(filter.getValue().size()));
            parameters.addAll(filter.getValue());
            first = false;
        }
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

    /** Record ids written into the SQL, since they are numbers of our own and there are few of them. */
    private static String inList(long[] values) {
        StringBuilder list = new StringBuilder("(");
        for (int i = 0; i < values.length; i++) {
            list.append(i == 0 ? "" : ", ").append(values[i]);
        }
        return list.append(')').toString();
    }

    /** A value from the dictionary, as a SQL string - it can hold a quote. */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** SQL and the values to bind to it. */
    public record Statement(String sql, List<Object> parameters) {
    }
}
