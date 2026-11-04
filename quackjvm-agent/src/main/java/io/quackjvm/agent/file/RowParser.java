package io.quackjvm.agent.file;

import java.util.BitSet;

/**
 * Splits one raw row into typed cells, in the order of the table's columns. Called from several
 * threads at once, so it must keep no state between rows.
 *
 * <p>It can also leave a row out, through {@link #isRow}: a CSV's header line, a blank line, or a
 * line that goes on with a quoted value begun on an earlier line. A row left out is not stored and
 * not counted by {@code count(*)}; the rows kept keep their own row numbers in {@code quackjvm_row}.</p>
 */
@FunctionalInterface
public interface RowParser {

    /**
     * Fills {@code cells} - one per column, of the column's Java type, null for no value. Called only
     * for rows {@link #isRow} keeps.
     *
     * @param rowNo the row's number in the source, counted from 0
     */
    void parse(String raw, long rowNo, Object[] cells);

    /**
     * As {@link #parse(String, long, Object[])}, when only the columns set in {@code wanted} are
     * needed - a wide table storing two of 6,000 columns. Cells not wanted are ignored, so a parser
     * may skip them; this one parses them all.
     */
    default void parse(String raw, long rowNo, Object[] cells, BitSet wanted) {
        parse(raw, rowNo, cells);
    }

    /**
     * Whether the row is a row of the table at all. A header, a blank line, or a line inside a
     * quoted value that spans lines is not: return false and it is left out. True unless overridden.
     *
     * <p>Decide from {@code raw} and {@code rowNo} alone - never from which columns are wanted, nor
     * from rows seen before. The file is read again, for just some columns, when a lazy column is
     * stored, and every pass must leave out the same rows. A table that finds a pass disagreeing
     * refuses to store the column, with that reason. Called from several threads at once.</p>
     *
     * <p>A row left out still counts as read: the next {@link FileTable#update} goes on after it.</p>
     *
     * @param rowNo the row's number in the source, counted from 0
     */
    default boolean isRow(String raw, long rowNo) {
        return true;
    }
}
