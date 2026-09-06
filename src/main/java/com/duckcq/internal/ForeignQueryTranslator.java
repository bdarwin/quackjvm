package com.duckcq.internal;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.logical.And;
import com.googlecode.cqengine.query.logical.Not;
import com.googlecode.cqengine.query.logical.Or;
import com.googlecode.cqengine.query.simple.All;
import com.googlecode.cqengine.query.simple.None;
import com.googlecode.cqengine.query.simple.SimpleQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a CQEngine query over a DuckDB-backed collection into a SQL subquery selecting the
 * primary keys of the objects it matches.
 *
 * <p>This is what makes a join possible in one statement. CQEngine evaluates {@code existsIn()} by
 * asking the foreign collection about one object at a time; expressing the same restriction as SQL
 * lets the whole join run inside DuckDB.</p>
 *
 * <p>Every method returns null when the query cannot be expressed - an attribute with no index
 * table, or a query type with no SQL equivalent. Callers must then fall back to CQEngine's own
 * evaluation, which is slower but always correct.</p>
 */
public final class ForeignQueryTranslator {

    private ForeignQueryTranslator() {
    }

    /**
     * @return SQL selecting the primary keys of objects in {@code target} matching {@code query},
     * or null if the query cannot be translated
     */
    public static <F> SqlFragment keysMatching(JoinTarget<F> target, Query<F> query) {
        if (query == null || query instanceof All) {
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName()), List.of());
        }
        if (query instanceof None) {
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName()) + " WHERE 1 = 0", List.of());
        }
        if (query instanceof And) {
            return combine(target, ((And<F>) query).getChildQueries(), " INTERSECT ");
        }
        if (query instanceof Or) {
            return combine(target, ((Or<F>) query).getChildQueries(), " UNION ");
        }
        if (query instanceof Not) {
            SqlFragment negated = keysMatching(target, ((Not<F>) query).getNegatedQuery());
            if (negated == null) {
                return null;
            }
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName())
                    + " EXCEPT " + negated.getSql(), negated.getParameters());
        }
        if (query instanceof SimpleQuery) {
            return keysMatchingSimpleQuery(target, (SimpleQuery<F, ?>) query);
        }
        return null;
    }

    private static <F> SqlFragment combine(JoinTarget<F> target, Iterable<Query<F>> children, String operator) {
        List<SqlFragment> fragments = new ArrayList<>();
        for (Query<F> child : children) {
            SqlFragment fragment = keysMatching(target, child);
            if (fragment == null) {
                return null;
            }
            fragments.add(fragment);
        }
        if (fragments.isEmpty()) {
            return null;
        }
        if (fragments.size() == 1) {
            return fragments.get(0);
        }
        // Each branch is parenthesised so that INTERSECT and UNION nest correctly.
        List<SqlFragment> wrapped = new ArrayList<>(fragments.size());
        for (SqlFragment fragment : fragments) {
            wrapped.add(fragment.wrap("(", ")"));
        }
        return SqlFragment.join(operator, wrapped);
    }

    private static <F> SqlFragment keysMatchingSimpleQuery(JoinTarget<F> target, SimpleQuery<F, ?> query) {
        Attribute<F, ?> attribute = query.getAttribute();
        if (attribute.equals(target.primaryKeyAttribute())) {
            // The restriction is on the primary key, so it can be applied to the object table itself.
            SqlPredicate predicate = renderOrNull(query, Sql.quote(ObjectTable.KEY_COLUMN));
            if (predicate == null) {
                return null;
            }
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName()) + predicate.toWhereClause(),
                    predicate.getParameters());
        }
        String indexTable = target.indexTableFor(attribute);
        if (indexTable == null) {
            return null;
        }
        SqlPredicate predicate = renderOrNull(query, Sql.quote(IndexTable.VALUE_COLUMN));
        if (predicate == null) {
            return null;
        }
        return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                + " FROM " + Sql.quote(indexTable) + predicate.toWhereClause(), predicate.getParameters());
    }

    /**
     * Selects the values a foreign key attribute takes across the foreign objects matching the
     * restriction - the right-hand side of the join.
     */
    public static <F> SqlFragment foreignKeyValues(JoinTarget<F> target, Attribute<F, ?> foreignKeyAttribute,
                                                   Query<F> restriction) {
        SqlFragment keys = keysMatching(target, restriction);
        if (keys == null) {
            return null;
        }
        if (foreignKeyAttribute.equals(target.primaryKeyAttribute())) {
            // The join is on the foreign collection's own primary key: its keys are the values.
            return keys;
        }
        String indexTable = target.indexTableFor(foreignKeyAttribute);
        if (indexTable == null) {
            return null;
        }
        boolean unrestricted = restriction == null || restriction instanceof All;
        if (unrestricted) {
            return new SqlFragment("SELECT DISTINCT " + Sql.quote(IndexTable.VALUE_COLUMN)
                    + " FROM " + Sql.quote(indexTable), List.of());
        }
        return new SqlFragment("SELECT DISTINCT " + Sql.quote(IndexTable.VALUE_COLUMN)
                + " FROM " + Sql.quote(indexTable)
                + " WHERE " + Sql.quote(ObjectTable.KEY_COLUMN) + " IN (" + keys.getSql() + ")",
                keys.getParameters());
    }

    private static SqlPredicate renderOrNull(Query<?> query, String column) {
        try {
            return SqlPredicate.render(query, column);
        }
        catch (IllegalStateException e) {
            // A query type with no SQL equivalent; the caller falls back to CQEngine.
            return null;
        }
    }
}
