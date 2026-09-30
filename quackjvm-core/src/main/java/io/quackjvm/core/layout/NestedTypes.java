package io.quackjvm.core.layout;

import io.quackjvm.core.duckdb.NestedType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

/**
 * Working out what a field of a record or a class holds, from the type it declares.
 *
 * <p>A {@code List<String>} says its element type in its generic type, which is not erased for a
 * field or a record component - so a layout taken from a record knows to make it {@code VARCHAR[]}
 * without being told. A raw {@code List} does not, and is refused with a message saying to declare the
 * element type on the builder instead.</p>
 *
 * <p>A {@code float[]} becomes a list, {@code FLOAT[]}, because nothing in the type says how long it
 * is. {@code vectorColumn(name, 768, ...)} is how to say {@code FLOAT[768]}.</p>
 */
final class NestedTypes {

    private NestedTypes() {
    }

    /** The nested type this field holds, or null when it holds one value. */
    static NestedType of(Type genericType, Class<?> rawType) {
        if (rawType == null) {
            return null;
        }
        if (rawType.isRecord()) {
            return NestedType.struct(rawType);
        }
        if (rawType == float[].class || rawType == double[].class) {
            // Length is not part of the type, so this is a list. vectorColumn(...) says otherwise.
            return NestedType.primitiveList(rawType);
        }
        if (Collection.class.isAssignableFrom(rawType)) {
            Class<?> element = argument(genericType, 0);
            if (element == null) {
                throw new IllegalArgumentException("A " + rawType.getSimpleName() + " column does not say what it"
                        + " holds. Use listColumn(name, ElementType.class, accessor) on the builder.");
            }
            return NestedType.list(element);
        }
        if (Map.class.isAssignableFrom(rawType)) {
            Class<?> key = argument(genericType, 0);
            Class<?> value = argument(genericType, 1);
            if (key == null || value == null) {
                throw new IllegalArgumentException("A " + rawType.getSimpleName() + " column does not say what it"
                        + " holds. Use mapColumn(name, KeyType.class, ValueType.class, accessor) on the builder.");
            }
            return NestedType.map(key, value);
        }
        return null;
    }

    private static Class<?> argument(Type genericType, int position) {
        if (genericType instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (position < arguments.length && arguments[position] instanceof Class<?> type) {
                return type;
            }
        }
        return null;
    }
}
