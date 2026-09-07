package io.quackjvm.cqengine.internal;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.simple.ExistsIn;

import java.lang.reflect.Field;

/**
 * Reads the parts of a CQEngine {@link ExistsIn} query.
 *
 * <p>{@code ExistsIn} keeps the foreign collection, the foreign key attribute and the foreign
 * restriction in package-private fields with no accessors, so they are read reflectively. This is
 * deliberate: it means an application which already calls
 * {@code QueryFactory.existsIn(...)} gets the SQL join for free, with no change to its code. If the
 * fields ever move, {@link #isAvailable()} turns false and every join falls back to CQEngine's own
 * evaluation - slower, but still correct.</p>
 */
public final class ExistsInQuery<O, F, A> {

    private static final Field FOREIGN_COLLECTION;
    private static final Field FOREIGN_KEY_ATTRIBUTE;
    private static final Field FOREIGN_RESTRICTIONS;

    static {
        FOREIGN_COLLECTION = field("foreignCollection");
        FOREIGN_KEY_ATTRIBUTE = field("foreignKeyAttribute");
        FOREIGN_RESTRICTIONS = field("foreignRestrictions");
    }

    private static Field field(String name) {
        try {
            Field field = ExistsIn.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Whether this JVM allows the query to be inspected, and joins therefore to be pushed down. */
    public static boolean isAvailable() {
        return FOREIGN_COLLECTION != null && FOREIGN_KEY_ATTRIBUTE != null && FOREIGN_RESTRICTIONS != null;
    }

    private final Attribute<O, A> localKeyAttribute;
    private final IndexedCollection<F> foreignCollection;
    private final Attribute<F, A> foreignKeyAttribute;
    private final Query<F> foreignRestrictions;

    private ExistsInQuery(Attribute<O, A> localKeyAttribute, IndexedCollection<F> foreignCollection,
                          Attribute<F, A> foreignKeyAttribute, Query<F> foreignRestrictions) {
        this.localKeyAttribute = localKeyAttribute;
        this.foreignCollection = foreignCollection;
        this.foreignKeyAttribute = foreignKeyAttribute;
        this.foreignRestrictions = foreignRestrictions;
    }

    /** @return the query's parts, or null if they cannot be read. */
    @SuppressWarnings("unchecked")
    public static <O, F, A> ExistsInQuery<O, F, A> of(Query<O> query) {
        if (!(query instanceof ExistsIn) || !isAvailable()) {
            return null;
        }
        ExistsIn<O, F, A> existsIn = (ExistsIn<O, F, A>) query;
        try {
            return new ExistsInQuery<>(
                    existsIn.getAttribute(),
                    (IndexedCollection<F>) FOREIGN_COLLECTION.get(existsIn),
                    (Attribute<F, A>) FOREIGN_KEY_ATTRIBUTE.get(existsIn),
                    (Query<F>) FOREIGN_RESTRICTIONS.get(existsIn));
        }
        catch (Exception e) {
            return null;
        }
    }

    public Attribute<O, A> getLocalKeyAttribute() {
        return localKeyAttribute;
    }

    public IndexedCollection<F> getForeignCollection() {
        return foreignCollection;
    }

    public Attribute<F, A> getForeignKeyAttribute() {
        return foreignKeyAttribute;
    }

    /** The restriction on the foreign collection, or null when there is none. */
    public Query<F> getForeignRestrictions() {
        return foreignRestrictions;
    }
}
