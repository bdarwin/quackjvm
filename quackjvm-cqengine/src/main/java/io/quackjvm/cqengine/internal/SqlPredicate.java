package io.quackjvm.cqengine.internal;

import io.quackjvm.core.duckdb.Sql;

import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.simple.Between;
import com.googlecode.cqengine.query.simple.Equal;
import com.googlecode.cqengine.query.simple.GreaterThan;
import com.googlecode.cqengine.query.simple.Has;
import com.googlecode.cqengine.query.simple.In;
import com.googlecode.cqengine.query.simple.LessThan;
import com.googlecode.cqengine.query.simple.StringStartsWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Translates the subset of CQEngine queries which a DuckDB index can answer into a SQL
 * predicate over a single column, plus the values to bind to it.
 *
 * <p>The translated queries are exactly those which CQEngine's own SQLite indexes support:
 * {@code equal}, {@code in}, {@code lessThan(OrEqualTo)}, {@code greaterThan(OrEqualTo)},
 * {@code between}, {@code startsWith} and {@code has}. Everything else is answered by
 * CQEngine itself, by filtering objects on-heap.</p>
 */
public final class SqlPredicate {

    /** A predicate which matches every row. */
    public static final SqlPredicate ALWAYS_TRUE = new SqlPredicate("", Collections.emptyList());

    private final String sql;
    private final List<Object> parameters;

    private SqlPredicate(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = parameters;
    }

    /** The SQL predicate, or the empty string if the query matches all indexed rows. */
    public String getSql() {
        return sql;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public boolean isAlwaysTrue() {
        return sql.isEmpty();
    }

    /** Renders {@code " WHERE <predicate>"}, or the empty string when the predicate is always true. */
    public String toWhereClause() {
        return sql.isEmpty() ? "" : " WHERE " + sql;
    }

    /** Combines this predicate with an additional SQL fragment. */
    public SqlPredicate and(String additionalSql, Object... additionalParameters) {
        List<Object> combined = new ArrayList<>(parameters.size() + additionalParameters.length);
        combined.addAll(parameters);
        Collections.addAll(combined, additionalParameters);
        // The additional parameters are bound after this predicate's own parameters, so the
        // additional fragment must come second in the rendered SQL.
        String combinedSql = sql.isEmpty() ? additionalSql : "(" + sql + ") AND " + additionalSql;
        return new SqlPredicate(combinedSql, combined);
    }

    /**
     * @param query  a CQEngine query on the attribute which the given column stores
     * @param column the quoted name of the column holding the attribute value
     * @return the equivalent SQL predicate
     * @throws IllegalStateException if the query is not one a DuckDB index can answer
     */
    public static <O, A> SqlPredicate render(Query<O> query, String column) {
        Class<?> queryClass = query.getClass();

        if (queryClass == Has.class) {
            // Only non-null attribute values are stored, so every row matches.
            return ALWAYS_TRUE;
        }
        if (queryClass == Equal.class) {
            @SuppressWarnings("unchecked")
            Equal<O, A> equal = (Equal<O, A>) query;
            return new SqlPredicate(column + " = ?", one(equal.getValue()));
        }
        if (queryClass == In.class) {
            @SuppressWarnings("unchecked")
            In<O, A> in = (In<O, A>) query;
            List<Object> values = new ArrayList<>(in.getValues());
            if (values.isEmpty()) {
                return new SqlPredicate("1 = 0", Collections.emptyList());
            }
            return new SqlPredicate(column + " IN " + Sql.placeholders(values.size()), values);
        }
        if (queryClass == LessThan.class) {
            LessThan<?, ?> lessThan = (LessThan<?, ?>) query;
            String operator = lessThan.isValueInclusive() ? " <= ?" : " < ?";
            return new SqlPredicate(column + operator, one(lessThan.getValue()));
        }
        if (queryClass == GreaterThan.class) {
            GreaterThan<?, ?> greaterThan = (GreaterThan<?, ?>) query;
            String operator = greaterThan.isValueInclusive() ? " >= ?" : " > ?";
            return new SqlPredicate(column + operator, one(greaterThan.getValue()));
        }
        if (queryClass == Between.class) {
            Between<?, ?> between = (Between<?, ?>) query;
            String lowerOperator = between.isLowerInclusive() ? " >= ?" : " > ?";
            String upperOperator = between.isUpperInclusive() ? " <= ?" : " < ?";
            List<Object> parameters = new ArrayList<>(2);
            parameters.add(between.getLowerValue());
            parameters.add(between.getUpperValue());
            return new SqlPredicate(column + lowerOperator + " AND " + column + upperOperator, parameters);
        }
        if (queryClass == StringStartsWith.class) {
            @SuppressWarnings("unchecked")
            StringStartsWith<O, ? extends CharSequence> startsWith = (StringStartsWith<O, ? extends CharSequence>) query;
            return renderStartsWith(column, startsWith.getValue().toString());
        }
        throw new IllegalStateException("Query " + queryClass.getName() + " is not supported by a DuckDB index");
    }

    /**
     * Renders {@code startsWith} as a half-open range so that the index on the column can be used,
     * falling back to {@code starts_with()} when the prefix cannot be incremented.
     */
    private static SqlPredicate renderStartsWith(String column, String prefix) {
        if (prefix.isEmpty()) {
            return ALWAYS_TRUE;
        }
        char lastCharacter = prefix.charAt(prefix.length() - 1);
        if (lastCharacter == Character.MAX_VALUE || Character.isSurrogate(lastCharacter)) {
            return new SqlPredicate("starts_with(" + column + ", ?)", one(prefix));
        }
        String upperBoundExclusive = prefix.substring(0, prefix.length() - 1) + (char) (lastCharacter + 1);
        List<Object> parameters = new ArrayList<>(2);
        parameters.add(prefix);
        parameters.add(upperBoundExclusive);
        return new SqlPredicate(column + " >= ? AND " + column + " < ?", parameters);
    }

    private static List<Object> one(Object value) {
        return Collections.singletonList(value);
    }
}
