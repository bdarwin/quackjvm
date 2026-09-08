package io.quackjvm.core.arrow;

import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.layout.ColumnarLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds objects from Arrow columnar batches, given the layout which shredded them.
 *
 * <p>This is the point of the Arrow path. Materialising objects out of a query result is the single
 * largest cost in a columnar store - the values are already in the JVM, and reading them through
 * JDBC pays a virtual call and a boxing allocation each. Reading whole columns instead removes
 * that, and the object construction is all that remains.</p>
 */
public final class ArrowObjectReader<O> {

    private final ColumnarLayout<O> layout;
    private final Class<?>[] columnTypes;
    private final int columnOffset;

    /**
     * @param columnOffset which Arrow column the layout's first field is at, so that a leading
     *                     primary key column can be skipped
     */
    public ArrowObjectReader(ColumnarLayout<O> layout, int columnOffset) {
        this.layout = layout;
        this.columnOffset = columnOffset;
        List<ColumnarLayout.Column<O, ?>> columns = layout.getColumns();
        this.columnTypes = new Class<?>[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            columnTypes[i] = columns.get(i).getType();
        }
    }

    /** Rebuilds the object at the given row of the batch. */
    public O readObject(ArrowBatch batch, int row) {
        Object[] values = new Object[columnTypes.length];
        for (int i = 0; i < columnTypes.length; i++) {
            values[i] = convert(batch.getValue(columnOffset + i, row), columnTypes[i]);
        }
        return layout.createObject(values);
    }

    /** Rebuilds every object in the batch. */
    public List<O> readBatch(ArrowBatch batch) {
        int rowCount = batch.getRowCount();
        List<O> objects = new ArrayList<>(rowCount);
        for (int row = 0; row < rowCount; row++) {
            objects.add(readObject(batch, row));
        }
        return objects;
    }

    /**
     * Arrow's Java type for a column is not always the type the layout declared - a DATE arrives as
     * a LocalDate whatever the field is, an enum arrives as its stored ordinal - so values go
     * through the same conversion the JDBC path uses.
     */
    private static Object convert(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return value;
        }
        return DuckDBTypes.fromSqlValue(value, type);
    }

    /** The Java type of the primary key column, for reading it alongside the object. */
    public static Object readKey(ArrowBatch batch, int column, int row, Class<?> keyType) {
        return convert(batch.getValue(column, row), keyType);
    }
}
