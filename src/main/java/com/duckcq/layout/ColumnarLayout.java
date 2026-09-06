package com.duckcq.layout;

import com.duckcq.internal.DuckDBTypes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Describes how to shred an object into one typed DuckDB column per field, and how to
 * rebuild the object from a row.
 *
 * <p>Supplying a layout to
 * {@link com.duckcq.persistence.DuckDBPersistence.Builder#columnarLayout(ColumnarLayout)}
 * switches the object store from "one serialized BLOB per object" to a real columnar table.
 * DuckDB can then apply dictionary, run-length and FSST compression per column, which
 * typically shrinks the stored data several-fold and makes full scans much faster.</p>
 *
 * <p>Three ways to build one:</p>
 * <pre>
 * // 1. Java records - components and canonical constructor are discovered automatically
 * ColumnarLayout.ofRecord(Car.class);
 *
 * // 2. Any class - all instance fields are read and written reflectively
 * ColumnarLayout.reflective(Car.class);
 *
 * // 3. Explicit, when you want control over the columns or a custom factory
 * ColumnarLayout.builder(Car.class)
 *         .column("carId", Integer.class, Car::getCarId)
 *         .column("name", String.class, Car::getName)
 *         .rowFactory(values -&gt; new Car((Integer) values[0], (String) values[1]))
 *         .build();
 * </pre>
 *
 * <p>Every column type must be one DuckDB understands; see {@link DuckDBTypes}. A field of an
 * unsupported type is rejected when the layout is built, not at run time.</p>
 */
public final class ColumnarLayout<O> {

    /** Rebuilds an object from the column values read back from DuckDB, in column order. */
    public interface RowFactory<O> {
        O create(Object[] values);
    }

    /** One mapped column: its name, its Java type, and how to read it from an object. */
    public static final class Column<O, V> {
        private final String name;
        private final Class<V> type;
        private final Function<O, V> accessor;

        Column(String name, Class<V> type, Function<O, V> accessor) {
            this.name = name;
            this.type = type;
            this.accessor = accessor;
        }

        public String getName() {
            return name;
        }

        public Class<V> getType() {
            return type;
        }

        public Object getValue(O object) {
            return accessor.apply(object);
        }
    }

    private final Class<O> objectType;
    private final List<Column<O, ?>> columns;
    private final RowFactory<O> rowFactory;

    private ColumnarLayout(Class<O> objectType, List<Column<O, ?>> columns, RowFactory<O> rowFactory) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A columnar layout must have at least one column: " + objectType);
        }
        for (Column<O, ?> column : columns) {
            // Fail fast rather than at the first insert.
            DuckDBTypes.sqlTypeFor(column.getType());
        }
        this.objectType = objectType;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.rowFactory = rowFactory;
    }

    public Class<O> getObjectType() {
        return objectType;
    }

    public List<Column<O, ?>> getColumns() {
        return columns;
    }

    public O createObject(Object[] values) {
        return rowFactory.create(values);
    }

    // ---------- Factory methods ----------

    public static <O> Builder<O> builder(Class<O> objectType) {
        return new Builder<>(objectType);
    }

    /**
     * Derives a layout from a record's components, reading them through their accessors and
     * rebuilding objects through the canonical constructor.
     */
    public static <R> ColumnarLayout<R> ofRecord(Class<R> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a record; "
                    + "use ColumnarLayout.reflective() or ColumnarLayout.builder() instead");
        }
        RecordComponent[] components = recordType.getRecordComponents();
        List<Column<R, ?>> columns = new ArrayList<>(components.length);
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            columns.add(column(component.getName(), boxed(component.getType()), object -> invoke(accessor, object)));
        }
        Constructor<R> canonicalConstructor;
        try {
            canonicalConstructor = recordType.getDeclaredConstructor(parameterTypes);
            canonicalConstructor.setAccessible(true);
        }
        catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No canonical constructor found for record " + recordType.getName(), e);
        }
        return new ColumnarLayout<>(recordType, columns, values -> newInstance(canonicalConstructor, values));
    }

    /**
     * Derives a layout from all non-static, non-transient instance fields of any class,
     * reading and writing them reflectively. Objects are allocated without calling a
     * constructor, in the same way as deserialization frameworks do.
     *
     * <p>Records are delegated to {@link #ofRecord(Class)}, because their fields cannot be
     * written reflectively.</p>
     */
    public static <O> ColumnarLayout<O> reflective(Class<O> objectType) {
        if (objectType.isRecord()) {
            return ofRecord(objectType);
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = objectType; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                fields.add(field);
            }
        }
        List<Column<O, ?>> columns = new ArrayList<>(fields.size());
        for (Field field : fields) {
            columns.add(column(field.getName(), boxed(field.getType()), object -> get(field, object)));
        }
        Field[] fieldArray = fields.toArray(new Field[0]);
        return new ColumnarLayout<>(objectType, columns, values -> {
            O instance = ObjectAllocator.allocate(objectType);
            for (int i = 0; i < fieldArray.length; i++) {
                set(fieldArray[i], instance, values[i]);
            }
            return instance;
        });
    }

    // ---------- Builder ----------

    public static final class Builder<O> {
        private final Class<O> objectType;
        private final List<Column<O, ?>> columns = new ArrayList<>();
        private RowFactory<O> rowFactory;

        Builder(Class<O> objectType) {
            this.objectType = objectType;
        }

        public <V> Builder<O> column(String name, Class<V> type, Function<O, V> accessor) {
            columns.add(new Column<>(name, boxed(type), accessor::apply));
            return this;
        }

        /** How to rebuild an object from the column values, in the order the columns were added. */
        public Builder<O> rowFactory(RowFactory<O> rowFactory) {
            this.rowFactory = rowFactory;
            return this;
        }

        public ColumnarLayout<O> build() {
            if (rowFactory == null) {
                throw new IllegalStateException("A rowFactory is required to rebuild " + objectType.getName()
                        + " from its columns");
            }
            return new ColumnarLayout<>(objectType, columns, rowFactory);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <O, V> Column<O, V> column(String name, Class<?> type, Function<O, Object> accessor) {
        return new Column(name, type, accessor);
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> boxed(Class<?> type) {
        if (!type.isPrimitive()) return (Class<T>) type;
        if (type == int.class) return (Class<T>) Integer.class;
        if (type == long.class) return (Class<T>) Long.class;
        if (type == double.class) return (Class<T>) Double.class;
        if (type == float.class) return (Class<T>) Float.class;
        if (type == boolean.class) return (Class<T>) Boolean.class;
        if (type == short.class) return (Class<T>) Short.class;
        if (type == byte.class) return (Class<T>) Byte.class;
        if (type == char.class) return (Class<T>) Character.class;
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to read " + method, e);
        }
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to read " + field, e);
        }
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to write " + field + " with value " + value, e);
        }
    }

    private static <R> R newInstance(Constructor<R> constructor, Object[] values) {
        try {
            return constructor.newInstance(values);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to invoke " + constructor + " with " + java.util.Arrays.toString(values), e);
        }
    }
}
