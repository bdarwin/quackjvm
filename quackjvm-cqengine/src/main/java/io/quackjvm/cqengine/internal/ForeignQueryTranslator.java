package io.quackjvm.cqengine.internal;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.SqlFragment;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.logical.And;
import com.googlecode.cqengine.query.logical.Not;
import com.googlecode.cqengine.query.logical.Or;
import com.googlecode.cqengine.query.simple.All;
import com.googlecode.cqengine.query.simple.ExistsIn;
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
        return keysMatching(target, query, null);
    }

    /**
     * @param resolver resolves a foreign collection referenced by an {@code existsIn} inside the
     *                 query, so joins nested in an {@code and}/{@code or} translate too. May be null.
     */
    public static <F> SqlFragment keysMatching(JoinTarget<F> target, Query<F> query, JoinResolver resolver) {
        if (query == null || query instanceof All) {
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName()), List.of());
        }
        if (query instanceof None) {
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName()) + " WHERE 1 = 0", List.of());
        }
        if (query instanceof And) {
            return combine(target, ((And<F>) query).getChildQueries(), " INTERSECT ", resolver);
        }
        if (query instanceof Or) {
            return combine(target, ((Or<F>) query).getChildQueries(), " UNION ", resolver);
        }
        if (query instanceof Not) {
            SqlFragment negated = keysMatching(target, ((Not<F>) query).getNegatedQuery(), resolver);
            if (negated == null) {
                return null;
            }
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName())
                    + " EXCEPT " + negated.getSql(), negated.getParameters());
        }
        if (query instanceof ExistsIn) {
            return keysMatchingJoin(target, query, resolver);
        }
        if (query instanceof SimpleQuery) {
            return keysMatchingSimpleQuery(target, (SimpleQuery<F, ?>) query);
        }
        return null;
    }

    /** Looks up the DuckDB tables behind another CQEngine collection. */
    public interface JoinResolver {
        <T> JoinTarget<T> targetFor(com.googlecode.cqengine.IndexedCollection<T> collection);
    }

    /**
     * Translates an {@code existsIn} into the keys of local objects whose join attribute matches
     * something in the foreign collection - so a join nested inside an {@code and} or {@code or}
     * becomes part of the same statement.
     */
    private static <F> SqlFragment keysMatchingJoin(JoinTarget<F> target, Query<F> query, JoinResolver resolver) {
        if (resolver == null) {
            return null;
        }
        ExistsInQuery<F, Object, Object> existsIn = ExistsInQuery.of(query);
        if (existsIn == null) {
            return null;
        }
        JoinTarget<Object> foreign = resolver.targetFor(existsIn.getForeignCollection());
        if (foreign == null) {
            return null;
        }
        SqlFragment foreignValues = foreignKeyValues(foreign, existsIn.getForeignKeyAttribute(),
                existsIn.getForeignRestrictions(), resolver);
        if (foreignValues == null) {
            return null;
        }
        Attribute<F, ?> localKeyAttribute = existsIn.getLocalKeyAttribute();
        if (localKeyAttribute.equals(target.primaryKeyAttribute())) {
            return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                    + " FROM " + Sql.quote(target.objectTableName())
                    + " WHERE " + Sql.quote(ObjectTable.KEY_COLUMN) + " IN (" + foreignValues.getSql() + ")",
                    foreignValues.getParameters());
        }
        String indexTable = target.indexTableFor(localKeyAttribute);
        if (indexTable == null) {
            return null;
        }
        return new SqlFragment("SELECT " + Sql.quote(ObjectTable.KEY_COLUMN)
                + " FROM " + Sql.quote(indexTable)
                + " WHERE " + Sql.quote(IndexTable.VALUE_COLUMN) + " IN (" + foreignValues.getSql() + ")",
                foreignValues.getParameters());
    }

    private static <F> SqlFragment combine(JoinTarget<F> target, Iterable<Query<F>> children, String operator,
                                           JoinResolver resolver) {
        List<SqlFragment> fragments = new ArrayList<>();
        for (Query<F> child : children) {
            SqlFragment fragment = keysMatching(target, child, resolver);
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
        return foreignKeyValues(target, foreignKeyAttribute, restriction, null);
    }

    public static <F> SqlFragment foreignKeyValues(JoinTarget<F> target, Attribute<F, ?> foreignKeyAttribute,
                                                   Query<F> restriction, JoinResolver resolver) {
        SqlFragment keys = keysMatching(target, restriction, resolver);
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
