package com.duckcq.internal;

import com.duckcq.layout.ColumnarLayout;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores each object shredded into one typed column per field, as described by a
 * {@link ColumnarLayout}. This is what lets DuckDB compress the object store column by column.
 */
public final class ColumnarRowCodec<O> implements RowCodec<O> {

    private final ColumnarLayout<O> layout;
    private final List<ColumnDef> columns;
    private final DuckDBTypes.ColumnReader<?>[] readers;
    @SuppressWarnings("rawtypes")
    private final ColumnarLayout.Column[] accessors;
    private final int width;

    public ColumnarRowCodec(ColumnarLayout<O> layout) {
        this.layout = layout;
        this.width = layout.getColumns().size();
        List<ColumnDef> columns = new ArrayList<>(width);
        this.readers = new DuckDBTypes.ColumnReader<?>[width];
        this.accessors = new ColumnarLayout.Column[width];
        int i = 0;
        for (ColumnarLayout.Column<O, ?> column : layout.getColumns()) {
            columns.add(new ColumnDef(column.getName(), column.getType()));
            readers[i] = DuckDBTypes.readerFor(column.getType());
            accessors[i] = column;
            i++;
        }
        this.columns = Collections.unmodifiableList(columns);
    }

    @Override
    public List<ColumnDef> columns() {
        return columns;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeRow(O object, Object[] row, int offset, QueryOptions queryOptions) {
        for (int i = 0; i < width; i++) {
            row[offset + i] = accessors[i].getValue(object);
        }
    }

    @Override
    public O readObject(ResultSet resultSet, int startIndex) throws SQLException {
        Object[] values = new Object[width];
        for (int i = 0; i < width; i++) {
            values[i] = readers[i].read(resultSet, startIndex + i);
        }
        return layout.createObject(values);
    }

    public ColumnarLayout<O> getLayout() {
        return layout;
    }
}
