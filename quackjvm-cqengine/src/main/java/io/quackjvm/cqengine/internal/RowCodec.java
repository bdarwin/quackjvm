package io.quackjvm.cqengine.internal;

import io.quackjvm.core.duckdb.ColumnDef;

import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Converts objects to and from the columns of the {@code cq_objects} table.
 *
 * <p>Two implementations are provided: {@link BlobRowCodec}, which stores each object as one
 * serialized BLOB (a drop-in replacement for any object type), and {@link ColumnarRowCodec},
 * which shreds each object into one typed column per field so that DuckDB's columnar
 * compression applies.</p>
 */
public interface RowCodec<O> {

    /** The columns which hold the object's state, excluding the primary key column. */
    List<ColumnDef> columns();

    /** Writes the object's state into {@code row}, starting at {@code offset}, in column order. */
    void writeRow(O object, Object[] row, int offset, QueryOptions queryOptions);

    /** Reconstructs an object from a JDBC row whose first state column is at {@code startIndex}. */
    O readObject(ResultSet resultSet, int startIndex) throws SQLException;
}
