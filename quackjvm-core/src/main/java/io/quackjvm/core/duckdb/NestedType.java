package io.quackjvm.core.duckdb;

import io.quackjvm.core.layout.ColumnarLayout;
import org.duckdb.DuckDBAppender;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A column that holds more than one value: a LIST, a STRUCT, a MAP, or an ARRAY of fixed size.
 *
 * <pre>
 * ColumnarLayout.ofRecord(Doc.class)          // List&lt;String&gt;, Map&lt;String,Integer&gt; and nested
 *                                             // records are recognised from the record's own types
 *
 * ColumnarLayout.builder(Doc.class)
 *         .listColumn("tags", String.class, Doc::tags)
 *         .mapColumn("counts", String.class, Integer.class, Doc::counts)
 *         .structColumn("owner", Owner.class, Doc::owner)
 *         .vectorColumn("embedding", 768, Doc::embedding)   // FLOAT[768], not FLOAT[]
 *         .rowFactory(...)
 *         .build();
 * </pre>
 *
 * <h2>How it is written</h2>
 *
 * <p>Through the appender, which has taken nested values since DuckDB 1.5: a {@code Collection} for a
 * list, a {@code Map} for a map, {@code beginStruct}/{@code endStruct} for a struct, and a
 * {@code float[]} or {@code double[]} for a fixed-size array. No Arrow, and no extra dependency - the
 * roadmap said the write path would need Arrow, and measuring said otherwise.</p>
 *
 * <h2>What is not supported</h2>
 *
 * <p>One level: a list of scalars, a struct of scalars, a map of scalars, a vector of numbers. A list of
 * structs, or a struct holding a list, is refused when the layout is built rather than written wrongly.
 * That is quackjvm's limit, not DuckDB's.</p>
 */
public final class NestedType {

    /** Which shape this is. */
    public enum Kind {
        /** {@code T[]} - any number of values of one type. */
        LIST,
        /** {@code STRUCT(a ..., b ...)} - a record's components, by name. */
        STRUCT,
        /** {@code MAP(K, V)} - keys to values. */
        MAP,
        /** {@code T[n]} - exactly n values, which is what vector search wants. */
        VECTOR
    }

    private final Kind kind;
    private final Class<?> elementType;
    private final Class<?> valueType;
    private final ColumnarLayout<?> structLayout;
    private final int size;
    /** What the Java side holds: a List, a Map, a record, or a primitive array. */
    private final Class<?> javaType;

    private NestedType(Kind kind, Class<?> elementType, Class<?> valueType, ColumnarLayout<?> structLayout,
                       int size, Class<?> javaType) {
        this.kind = kind;
        this.elementType = elementType;
        this.valueType = valueType;
        this.structLayout = structLayout;
        this.size = size;
        this.javaType = javaType;
    }

    /** {@code List<T>} as {@code T[]}. */
    public static NestedType list(Class<?> elementType) {
        checkScalar(elementType, "a list");
        return new NestedType(Kind.LIST, elementType, null, null, -1, List.class);
    }

    /**
     * {@code float[]} or {@code double[]} of any length, as {@code FLOAT[]} or {@code DOUBLE[]}.
     *
     * <p>An array's length is not part of its type, so this is what a {@code float[]} field becomes.
     * {@link #vector} is how to say a fixed size, which is what DuckDB's array functions need.</p>
     */
    public static NestedType primitiveList(Class<?> arrayType) {
        checkPrimitiveArray(arrayType);
        return new NestedType(Kind.LIST, arrayType == float[].class ? Float.class : Double.class, null, null,
                -1, arrayType);
    }

    /** {@code Map<K, V>} as {@code MAP(K, V)}. */
    public static NestedType map(Class<?> keyType, Class<?> valueType) {
        checkScalar(keyType, "a map key");
        checkScalar(valueType, "a map value");
        return new NestedType(Kind.MAP, keyType, valueType, null, -1, Map.class);
    }

    /** A record as {@code STRUCT(component type, ...)}. */
    public static NestedType struct(Class<?> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a record, so it cannot be a STRUCT."
                    + " Give the column a record type, or map it to columns of its own.");
        }
        ColumnarLayout<?> layout = ColumnarLayout.ofRecord(recordType);
        for (ColumnarLayout.Column<?, ?> component : layout.getColumns()) {
            if (component.getNested() != null) {
                throw new IllegalArgumentException("A STRUCT of " + recordType.getSimpleName() + " would hold '"
                        + component.getName() + "', which is itself nested. quackjvm writes one level:"
                        + " flatten it, or keep it in a table of its own.");
            }
            checkScalar(component.getType(), "a struct field");
        }
        return new NestedType(Kind.STRUCT, recordType, null, layout, -1, recordType);
    }

    /**
     * {@code float[]} or {@code double[]} of exactly this many values - {@code FLOAT[768]} rather than
     * {@code FLOAT[]}, which is what DuckDB's array functions, and any index over them, need.
     */
    public static NestedType vector(Class<?> arrayType, int size) {
        checkPrimitiveArray(arrayType);
        if (size <= 0) {
            throw new IllegalArgumentException("A vector's size must be positive: " + size);
        }
        return new NestedType(Kind.VECTOR, arrayType, null, null, size, arrayType);
    }

    private static void checkPrimitiveArray(Class<?> arrayType) {
        if (arrayType != float[].class && arrayType != double[].class) {
            throw new IllegalArgumentException("A vector column is a float[] or a double[], not "
                    + arrayType.getSimpleName());
        }
    }

    /** What the Java side of this column holds. */
    public Class<?> getJavaType() {
        return javaType;
    }

    public Kind getKind() {
        return kind;
    }

    /** The element type of a list or vector, the key type of a map, the record type of a struct. */
    public Class<?> getElementType() {
        return elementType;
    }

    /** The value type of a map, or null. */
    public Class<?> getValueType() {
        return valueType;
    }

    /** How many values a vector holds, or -1. */
    public int getSize() {
        return size;
    }

    /** The DuckDB type for this column. */
    public String sqlType() {
        switch (kind) {
            case LIST:
                return DuckDBTypes.sqlTypeFor(elementType) + "[]";
            case MAP:
                return "MAP(" + DuckDBTypes.sqlTypeFor(elementType) + ", " + DuckDBTypes.sqlTypeFor(valueType) + ")";
            case VECTOR:
                return (javaType == float[].class ? "FLOAT" : "DOUBLE") + "[" + size + "]";
            case STRUCT:
            default:
                StringBuilder sql = new StringBuilder("STRUCT(");
                List<? extends ColumnarLayout.Column<?, ?>> components = structLayout.getColumns();
                for (int i = 0; i < components.size(); i++) {
                    sql.append(i == 0 ? "" : ", ").append('"').append(components.get(i).getName()).append("\" ")
                            .append(DuckDBTypes.sqlTypeFor(components.get(i).getType()));
                }
                return sql.append(')').toString();
        }
    }

    /** Writes one value of this column through the appender. */
    @SuppressWarnings("unchecked")
    public void append(DuckDBAppender appender, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
            return;
        }
        switch (kind) {
            case LIST -> {
                List<Object> asSql = new ArrayList<>();
                if (value instanceof Collection<?> collection) {
                    for (Object element : collection) {
                        asSql.add(DuckDBTypes.toSqlValue(element));
                    }
                }
                else if (value.getClass().isArray()) {
                    // Including float[] and double[], whose elements are not Objects.
                    int length = java.lang.reflect.Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        asSql.add(DuckDBTypes.toSqlValue(java.lang.reflect.Array.get(value, i)));
                    }
                }
                else {
                    throw new IllegalArgumentException("A list column takes a Collection or an array, not "
                            + value.getClass().getName());
                }
                appender.append(asSql);
            }
            case MAP -> {
                Map<?, ?> given = (Map<?, ?>) value;
                Map<Object, Object> asSql = new LinkedHashMap<>();
                given.forEach((key, mapped) -> asSql.put(DuckDBTypes.toSqlValue(key),
                        DuckDBTypes.toSqlValue(mapped)));
                appender.append(asSql);
            }
            case VECTOR -> {
                if (javaType == float[].class) {
                    float[] values = (float[]) value;
                    checkVectorSize(values.length);
                    appender.append(values);
                }
                else {
                    double[] values = (double[]) value;
                    checkVectorSize(values.length);
                    appender.append(values);
                }
            }
            case STRUCT -> {
                ColumnarLayout<Object> layout = (ColumnarLayout<Object>) structLayout;
                Object[] row = layout.toRow(value);
                appender.beginStruct();
                for (int i = 0; i < row.length; i++) {
                    DuckDBTypes.append(appender, row[i], layout.getColumns().get(i).getType());
                }
                appender.endStruct();
            }
        }
    }

    /** Turns what JDBC hands back into the Java value the layout expects. */
    public Object fromSql(Object value) {
        if (value == null) {
            return null;
        }
        try {
            switch (kind) {
                case LIST -> {
                    Object[] elements = elementsOf(value);
                    if (javaType != null && javaType.isArray()) {
                        return toPrimitiveArray(elements);
                    }
                    List<Object> list = new ArrayList<>(elements.length);
                    for (Object element : elements) {
                        list.add(DuckDBTypes.fromSqlValue(element, elementType));
                    }
                    return list;
                }
                case VECTOR -> {
                    return toPrimitiveArray(elementsOf(value));
                }
                case MAP -> {
                    Map<?, ?> given = (Map<?, ?>) value;
                    Map<Object, Object> map = new LinkedHashMap<>();
                    given.forEach((key, mapped) -> map.put(DuckDBTypes.fromSqlValue(key, elementType),
                            DuckDBTypes.fromSqlValue(mapped, valueType)));
                    return map;
                }
                case STRUCT -> {
                    Object[] attributes = value instanceof java.sql.Struct struct ? struct.getAttributes()
                            : ((Map<?, ?>) value).values().toArray();
                    List<? extends ColumnarLayout.Column<?, ?>> components = structLayout.getColumns();
                    Object[] values = new Object[components.size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = DuckDBTypes.fromSqlValue(attributes[i], components.get(i).getType());
                    }
                    return createStruct(values);
                }
                default -> throw new IllegalStateException("Unknown nested kind " + kind);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read a " + kind + " value back", e);
        }
    }

    /** A float[] or double[] from what JDBC handed back, with a null element read as zero. */
    private Object toPrimitiveArray(Object[] elements) {
        if (javaType == double[].class) {
            double[] vector = new double[elements.length];
            for (int i = 0; i < elements.length; i++) {
                vector[i] = elements[i] == null ? 0d : ((Number) elements[i]).doubleValue();
            }
            return vector;
        }
        float[] vector = new float[elements.length];
        for (int i = 0; i < elements.length; i++) {
            vector[i] = elements[i] == null ? 0f : ((Number) elements[i]).floatValue();
        }
        return vector;
    }

    @SuppressWarnings("unchecked")
    private Object createStruct(Object[] values) {
        return ((ColumnarLayout<Object>) structLayout).createObject(values);
    }

    private static Object[] elementsOf(Object value) throws SQLException {
        if (value instanceof java.sql.Array array) {
            Object inner = array.getArray();
            if (inner instanceof Object[] objects) {
                return objects;
            }
            int length = java.lang.reflect.Array.getLength(inner);
            Object[] elements = new Object[length];
            for (int i = 0; i < length; i++) {
                elements[i] = java.lang.reflect.Array.get(inner, i);
            }
            return elements;
        }
        if (value instanceof Object[] objects) {
            return objects;
        }
        if (value instanceof Collection<?> collection) {
            return collection.toArray();
        }
        throw new IllegalStateException("Expected a list from DuckDB, got " + value.getClass().getName());
    }

    private void checkVectorSize(int given) {
        if (given != size) {
            throw new IllegalArgumentException("This column holds vectors of " + size + " values, and this one has "
                    + given + ". A fixed-size array is fixed - pad it, or declare the column as a list instead.");
        }
    }

    private static void checkScalar(Class<?> type, String where) {
        if (type == null) {
            throw new IllegalArgumentException("The type of " + where + " must be given");
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                || (type.isArray() && type != byte[].class) || type.isRecord()) {
            throw new IllegalArgumentException(type.getSimpleName() + " cannot be " + where
                    + ": quackjvm writes one level of nesting. Flatten it, or keep it in a table of its own.");
        }
        DuckDBTypes.sqlTypeFor(type);
    }

    @Override
    public String toString() {
        return sqlType();
    }
}
