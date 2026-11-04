package io.quackjvm.core.page;

import io.quackjvm.core.duckdb.DuckDBTypes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Where a row stands in a paged query: its values of the {@link Pages#orderBy} columns, in that
 * order. Taken from a {@link Page}, or made from a row the application already has - a search hit,
 * say - to jump to it with {@link Pages#around}.
 */
public final class PageKey {

    private final List<Object> values;

    private PageKey(Object[] values) {
        this.values = Collections.unmodifiableList(Arrays.asList(values.clone()));
    }

    /** The key of the row with these values of the order columns. */
    public static PageKey of(Object... values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("A key needs the value of each order column");
        }
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("A key cannot hold a null: the order columns must have a value"
                        + " in every row");
            }
        }
        Object[] normal = new Object[values.length];
        for (int i = 0; i < values.length; i++) {
            normal[i] = normal(values[i]);
        }
        return new PageKey(normal);
    }

    /**
     * One form for each value, so that a key made from Java values equals the key of the same row
     * read back: the driver returns a {@code java.sql.Timestamp} where the application has a
     * {@code LocalDateTime}, an {@code Integer} where it has a {@code long}.
     */
    static Object normal(Object value) {
        Object sql = DuckDBTypes.toSqlValue(value);
        if (sql instanceof Integer || sql instanceof Short || sql instanceof Byte) {
            return ((Number) sql).longValue();
        }
        if (sql instanceof Float f) {
            return f.doubleValue();
        }
        return sql;
    }

    public List<Object> values() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PageKey key && values.equals(key.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "PageKey" + values;
    }
}
