package io.quackjvm.agent.file;

/**
 * A column whose value the application works out from the whole raw row - a total over 1,500 of a
 * row's 6,000 cells, say - added to an open table with {@link FileTable#column}. Like a lazy column,
 * it is stored the first time a query reads it, by reading the file again for just that column, and
 * from then on with every {@link FileTable#update}.
 *
 * <p>Called on the loading threads, several at once, so it must keep no state between rows. It is
 * called only for rows the parser keeps ({@link RowParser#isRow}).</p>
 */
@FunctionalInterface
public interface RowFunction {

    /**
     * The column's value for this row: of the column's Java type, or null for none.
     *
     * @param rowNo the row's number in the source, counted from 0
     */
    Object apply(String raw, long rowNo);
}
