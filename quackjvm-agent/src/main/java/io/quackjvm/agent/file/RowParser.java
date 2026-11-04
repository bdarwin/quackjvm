package io.quackjvm.agent.file;

import java.util.BitSet;

/**
 * Splits one raw row into typed cells, in the order of the table's columns. Called from several
 * threads at once, so it must keep no state between rows.
 */
@FunctionalInterface
public interface RowParser {

    /**
     * Fills {@code cells} - one per column, of the column's Java type, null for no value.
     *
     * @param rowNo the row's number, counted from 0
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
}
