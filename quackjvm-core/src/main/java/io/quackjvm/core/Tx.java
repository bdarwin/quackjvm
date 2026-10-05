package io.quackjvm.core;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.internal.LendingConnection;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sparse.SparseTable;
import io.quackjvm.core.sql.Rows;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Iterator;

/**
 * One transaction, opened by {@link Quack#inTransaction} and ended by it: committed when the work
 * returns, rolled back when it throws. Everything done through a {@code Tx} runs on one connection,
 * inside that one transaction, so live state and a measure refresh commit together or not at all.
 *
 * <p>It offers what is correct inside a transaction and nothing else. Shipping, exporting, compacting
 * and pruning a history each run transactions of their own against two databases, and DuckDB writes
 * to only one attached database per transaction, so they are on {@link Quack} and not here.</p>
 *
 * <p>Used by the thread that opened it, and only until the work returns.</p>
 */
public final class Tx {

    private final Connection connection;
    private volatile boolean ended;

    Tx(Connection transactional) {
        this.connection = LendingConnection.bound(transactional, () -> ended);
    }

    /** A query, read inside this transaction - it sees what the transaction has written. */
    public Rows query(String sql, Object... parameters) {
        checkOpen();
        return Rows.of(connection, sql, parameters);
    }

    /** A measure query, read inside this transaction. */
    public Rows query(MeasureQuery query) {
        checkOpen();
        return query.run(connection);
    }

    /** A statement that changes something, with {@code ?} parameters; how many rows it changed. */
    public int execute(String sql, Object... parameters) {
        checkOpen();
        return Sql.executeUpdate(connection, sql, Arrays.asList(parameters));
    }

    /**
     * Applies a refresh as part of this transaction.
     *
     * @return false if a refresh with this id was committed before, in which case nothing was written
     */
    public boolean commit(MeasureRefresh refresh) {
        checkOpen();
        return refresh.commit(connection);
    }

    /**
     * Writes the objects into the table {@code name}, replacing any already stored under the same key
     * - the layout's first column. Part of this transaction, so the replacement is all or nothing.
     *
     * @return how many rows were written
     */
    public <O> long write(String name, ColumnarLayout<O> layout, Iterable<O> objects) {
        checkOpen();
        Iterator<O> each = objects.iterator();
        Iterator<Object[]> rows = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return each.hasNext();
            }

            @Override
            public Object[] next() {
                return layout.toRow(each.next());
            }
        };
        return writer(name, layout).write(connection, rows, true).rowsWritten;
    }

    /** Creates a table with a column per field of the layout, keyed on the first. Safe to call again. */
    public <O> void createTable(String name, ColumnarLayout<O> layout) {
        checkOpen();
        writer(name, layout).createTable(connection, true);
    }

    static TableWriter writer(String name, ColumnarLayout<?> layout) {
        return new TableWriter(name, layout.toColumnDefs(), false, TableWriter.DEFAULT_APPENDER_THRESHOLD);
    }

    /** Creates the tables of the measures, inside this transaction. Safe to call again. */
    public void create(MeasureTable... measures) {
        checkOpen();
        for (MeasureTable measure : measures) {
            measure.create(connection);
        }
    }

    /** Creates the tables of a sparse table, inside this transaction. Safe to call again. */
    public void create(SparseTable table) {
        checkOpen();
        table.create(connection);
    }

    /**
     * More work in this same transaction. DuckDB has no savepoints, so this is not a nested
     * transaction: a failure here fails the whole of the outer one.
     */
    public void inTransaction(Work work) {
        checkOpen();
        try {
            work.run(this);
        }
        catch (RuntimeException | Error e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * This transaction's connection, for what the entry point does not wrap. Closing it does nothing;
     * committing, rolling back or turning auto-commit on is refused, since ending the transaction is
     * the entry point's to do. It stops working when the transaction ends.
     */
    public Connection connection() {
        checkOpen();
        return connection;
    }

    public boolean isOpen() {
        return !ended;
    }

    void end() {
        ended = true;
    }

    private void checkOpen() {
        if (ended) {
            throw new IllegalStateException("This transaction has ended");
        }
    }

    /** Work done inside a transaction. */
    @FunctionalInterface
    public interface Work {
        void run(Tx tx) throws Exception;
    }

    /** Work done inside a transaction that produces a value. */
    @FunctionalInterface
    public interface Result<T> {
        T apply(Tx tx) throws Exception;
    }
}
