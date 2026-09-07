package io.quackjvm.cqengine.query;

import io.quackjvm.core.sql.JoinPair;

import io.quackjvm.cqengine.internal.ForeignQueryTranslator;
import io.quackjvm.cqengine.internal.IndexTable;
import io.quackjvm.cqengine.internal.JoinTarget;
import io.quackjvm.cqengine.internal.ObjectTable;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.SqlFragment;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A join between two collections stored in the same DuckDB database, returning the matched objects
 * in pairs.
 *
 * <p>Where {@code existsIn()} answers "which of these objects have a match", this answers "give me
 * the matches" - the join people usually mean, and one CQEngine has no way to express.</p>
 *
 * <pre>
 * try (Stream&lt;JoinPair&lt;Vehicle, Person&gt;&gt; pairs = database.join(vehicles, people)
 *         .on(Vehicle.OWNER_ID, Person.PERSON_ID)
 *         .whereRight(equal(Person.COUNTRY, "FR"))
 *         .stream()) {
 *     pairs.forEach(pair -&gt; System.out.println(pair.left() + " owned by " + pair.right()));
 * }
 * </pre>
 *
 * <p>The stream holds a database connection and <b>must be closed</b>. An object whose join
 * attribute holds several values appears once per match, as a SQL join would.</p>
 */
public final class Join<L, R> {

    private final JoinTarget<L> left;
    private final JoinTarget<R> right;
    private final Supplier<Connection> connections;
    private final QueryOptions queryOptions = new QueryOptions();

    private Attribute<L, ?> leftAttribute;
    private Attribute<R, ?> rightAttribute;
    private Query<L> leftRestriction;
    private Query<R> rightRestriction;

    public Join(JoinTarget<L> left, JoinTarget<R> right, Supplier<Connection> connections) {
        this.left = left;
        this.right = right;
        this.connections = connections;
    }

    /** The attributes to match on, one from each collection. */
    public Join<L, R> on(Attribute<L, ?> leftAttribute, Attribute<R, ?> rightAttribute) {
        this.leftAttribute = leftAttribute;
        this.rightAttribute = rightAttribute;
        return this;
    }

    /** Restricts the left-hand collection. */
    public Join<L, R> whereLeft(Query<L> restriction) {
        this.leftRestriction = restriction;
        return this;
    }

    /** Restricts the right-hand collection. */
    public Join<L, R> whereRight(Query<R> restriction) {
        this.rightRestriction = restriction;
        return this;
    }

    /**
     * Runs the join. The returned stream is lazy and holds a database connection, so close it -
     * ideally with try-with-resources.
     */
    public Stream<JoinPair<L, R>> stream() {
        requireJoinAttributes();
        ObjectTable<L, ?> leftTable = left.objectTable();
        ObjectTable<R, ?> rightTable = right.objectTable();

        SqlFragment sql = buildSelect(leftTable.selectList("l") + ", " + rightTable.selectList("r"));
        Connection connection = connections.get();
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql.getSql());
            Sql.bindAll(statement, sql.getParameters(), 1);
            ResultSet resultSet = statement.executeQuery();
            int rightOffset = leftTable.getColumnCount() + 1;

            PreparedStatement toClose = statement;
            Iterator<JoinPair<L, R>> iterator = new Iterator<>() {
                private Boolean hasNext;

                @Override
                public boolean hasNext() {
                    if (hasNext == null) {
                        try {
                            hasNext = resultSet.next();
                        }
                        catch (SQLException e) {
                            throw new IllegalStateException("Failed to read a joined row", e);
                        }
                    }
                    return hasNext;
                }

                @Override
                public JoinPair<L, R> next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    hasNext = null;
                    try {
                        return new JoinPair<>(leftTable.readObject(resultSet, 1),
                                rightTable.readObject(resultSet, rightOffset));
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to materialise a joined row", e);
                    }
                }
            };

            Stream<JoinPair<L, R>> stream = StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
                            false)
                    .onClose(() -> {
                        Sql.closeQuietly(resultSet);
                        Sql.closeQuietly(toClose);
                        Sql.closeQuietly(connection);
                    });
            return applyUntranslatedRestrictions(stream);
        }
        catch (SQLException | RuntimeException e) {
            Sql.closeQuietly(statement);
            Sql.closeQuietly(connection);
            throw e instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Failed to run the join", e);
        }
    }

    /** @return how many pairs the join produces, without materialising any objects. */
    public long count() {
        requireJoinAttributes();
        if (untranslated(leftRestriction, left) || untranslated(rightRestriction, right)) {
            try (Stream<JoinPair<L, R>> stream = stream()) {
                return stream.count();
            }
        }
        SqlFragment sql = buildSelect("count(*)");
        try (Connection connection = connections.get()) {
            return Sql.queryLong(connection, sql.getSql(), sql.getParameters());
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to count the join", e);
        }
    }

    /**
     * A restriction which could not be turned into SQL is applied here instead, on the objects the
     * join returns. Slower than filtering in the database, but it keeps every query answerable.
     */
    private Stream<JoinPair<L, R>> applyUntranslatedRestrictions(Stream<JoinPair<L, R>> stream) {
        Stream<JoinPair<L, R>> filtered = stream;
        if (untranslated(leftRestriction, left)) {
            Query<L> restriction = leftRestriction;
            filtered = filtered.filter(pair -> restriction.matches(pair.left(), queryOptions));
        }
        if (untranslated(rightRestriction, right)) {
            Query<R> restriction = rightRestriction;
            filtered = filtered.filter(pair -> restriction.matches(pair.right(), queryOptions));
        }
        return filtered;
    }

    private <T> boolean untranslated(Query<T> restriction, JoinTarget<T> target) {
        return restriction != null && ForeignQueryTranslator.keysMatching(target, restriction) == null;
    }

    private void requireJoinAttributes() {
        if (leftAttribute == null || rightAttribute == null) {
            throw new IllegalStateException("Call on(leftAttribute, rightAttribute) to say what to join on");
        }
    }

    /** Builds {@code SELECT <projection> FROM ... JOIN ... WHERE ...} for this join. */
    private SqlFragment buildSelect(String projection) {
        List<Object> parameters = new ArrayList<>();
        StringBuilder from = new StringBuilder();
        from.append(" FROM ").append(Sql.quote(left.objectTableName())).append(" l");

        String leftValue = joinValueColumn(left, leftAttribute, "l", "li", from);
        String rightValue;
        from.append(" JOIN ").append(Sql.quote(right.objectTableName())).append(" r");
        StringBuilder rightJoins = new StringBuilder();
        rightValue = joinValueColumn(right, rightAttribute, "r", "ri", rightJoins);
        from.append(rightJoins);
        from.append(" ON ").append(leftValue).append(" = ").append(rightValue);

        StringBuilder where = new StringBuilder();
        appendRestriction(where, parameters, left, leftRestriction, "l");
        appendRestriction(where, parameters, right, rightRestriction, "r");

        return new SqlFragment("SELECT " + projection + from + where, parameters);
    }

    /**
     * The column holding the join value for one side. When the join attribute is that collection's
     * primary key it is the key column itself; otherwise the attribute's index table is joined in.
     */
    private <T> String joinValueColumn(JoinTarget<T> target, Attribute<T, ?> attribute, String tableAlias,
                                       String indexAlias, StringBuilder joins) {
        if (attribute.equals(target.primaryKeyAttribute())) {
            return ObjectTable.keyColumn(tableAlias);
        }
        String indexTable = target.indexTableFor(attribute);
        if (indexTable == null) {
            throw new IllegalStateException("Cannot join on '" + attribute.getAttributeName()
                    + "': it is neither the primary key of its collection nor indexed with DuckDBIndex. "
                    + "Add an index on it, or join on the primary key.");
        }
        joins.append(" JOIN ").append(Sql.quote(indexTable)).append(' ').append(indexAlias)
                .append(" ON ").append(ObjectTable.keyColumn(indexAlias))
                .append(" = ").append(ObjectTable.keyColumn(tableAlias));
        return Sql.quote(indexAlias) + "." + Sql.quote(IndexTable.VALUE_COLUMN);
    }

    private <T> void appendRestriction(StringBuilder where, List<Object> parameters, JoinTarget<T> target,
                                       Query<T> restriction, String alias) {
        if (restriction == null) {
            return;
        }
        SqlFragment keys = ForeignQueryTranslator.keysMatching(target, restriction);
        if (keys == null) {
            // Applied on the returned objects instead; see applyUntranslatedRestrictions.
            return;
        }
        where.append(where.isEmpty() ? " WHERE " : " AND ")
                .append(ObjectTable.keyColumn(alias)).append(" IN (").append(keys.getSql()).append(')');
        parameters.addAll(keys.getParameters());
    }
}
