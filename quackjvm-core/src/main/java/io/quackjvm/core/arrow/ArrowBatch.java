package io.quackjvm.core.arrow;

/**
 * One columnar batch of a query result: a fixed number of rows, with each column readable by
 * position without boxing every value first.
 *
 * <p>Implementations are backed by Arrow vectors owned by the reader, so a batch is only valid
 * until the reader advances. Copy anything you need to keep.</p>
 */
public interface ArrowBatch {

    /** How many rows this batch holds. */
    int getRowCount();

    /** How many columns the result has. */
    int getColumnCount();

    /** True if the value at this position is SQL NULL. */
    boolean isNull(int column, int row);

    /**
     * Reads one value as the Java type the column maps to, or null.
     *
     * <p>Column and row are zero-based, unlike JDBC.</p>
     */
    Object getValue(int column, int row);

    /** Reads a value already known to be a long, without boxing through {@link #getValue}. */
    long getLong(int column, int row);

    int getInt(int column, int row);

    double getDouble(int column, int row);

    String getString(int column, int row);
}
