package io.quackjvm.agent.file;

/**
 * Where a {@link FileTable}'s rows come from, raw: one string per row, as the application reads its
 * file. {@link LineSource} is one for text files, a line per row.
 *
 * <p>{@link #read} is called from several threads at once, for different rows, so it must be safe
 * to call that way.</p>
 */
public interface RowSource {

    /**
     * How many whole rows there are now. A file that grows says more next time; a last row still
     * being written is not counted until it is whole.
     */
    long rowCount();

    /** Rows {@code first} to {@code first + n - 1}, into {@code into[0..n-1]}. */
    void read(long first, int n, String[] into);

    /**
     * Something that changes when the file is replaced - rotated, or written again from the start -
     * compared with {@code equals} while the table is open. Across a restart the table compares the
     * file's first bytes instead, so this need not survive one.
     */
    Object identity();
}
