package io.quackjvm.core;

import io.quackjvm.core.catalog.Catalog;
import io.quackjvm.core.duckdb.ConnectionPool;
import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.guard.GuardedQuery;
import io.quackjvm.core.guard.Hardening;
import io.quackjvm.core.guard.QueryPolicy;
import io.quackjvm.core.internal.LendingConnection;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.live.LiveTable;
import io.quackjvm.core.live.LiveTables;
import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.sparse.SparseTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.udf.Udf;
import io.quackjvm.core.udf.Udfs;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A DuckDB database, and everything quackjvm does with it, behind one object that owns the
 * connections - so the code using it never holds one.
 *
 * <pre>
 * try (Quack db = Quack.open("live.duckdb")) {
 *     db.create(exposure);
 *     db.commit(MeasureRefresh.at(nine, "09:00").full(exposure, batch));
 *     db.query("SELECT count(*) FROM car").scalar(Long.class);
 *     db.vectors("note", "embedding").topK(question, 3);
 * }
 * </pre>
 *
 * <p>It is safe to share between threads. Every call borrows a pooled connection for as long as it
 * needs one and gives it back, on failure as well as success, so threads run on connections of their
 * own: a transaction belongs to a connection, and one shared between two threads would let one roll
 * back the other's work. What the database shares between its connections - an attached history, a
 * Java function, a live table, hardening - is done once, here, and every connection sees it.</p>
 *
 * <p>A transaction is {@link #inTransaction}: one connection, committed when the work returns and
 * rolled back when it throws. {@link #connection()} is the way down to DuckDB itself, for anything not
 * wrapped here. The connection-taking methods of every class underneath remain, for code that has a
 * pool of its own.</p>
 */
public final class Quack implements AutoCloseable {

    private final DuckDBConnection root;
    private final ConnectionPool pool;
    private final Connection lending;
    private final MeasureHistory history;
    private final List<LiveTable> liveTables = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private Quack(DuckDBConnection root, MeasureHistory history, QuackMetrics metrics, int connections) {
        this.root = root;
        this.history = history;
        this.pool = new ConnectionPool(root, connections, metrics);
        this.lending = LendingConnection.lending(pool, () -> closed);
        if (history != null) {
            history.attach(root);
        }
    }

    // ---------- opening ----------

    /** A database in a file, created if it is not there. */
    public static Quack open(String path) {
        return builder(path).open();
    }

    /** A database that lives only as long as this object. */
    public static Quack inMemory() {
        return builder(null).open();
    }

    /**
     * A database another part of the program already has open. The connection given is never used
     * or closed: this works on connections duplicated from it, and closes only those.
     */
    public static Quack on(DuckDBConnection existing) {
        try {
            return new Quack((DuckDBConnection) existing.duplicate(), null, null, defaultConnections());
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open a connection to the given database", e);
        }
    }

    /**
     * A separate in-memory database, hardened as {@link Hardening#sandbox()} hardens one, for queries
     * nobody has vetted: it cannot read files, reach the network or be reconfigured. It is a different
     * database from any other, so it holds only what is put into it.
     */
    public static Quack sandbox() {
        return new Quack(Connections.duckDB(Hardening.sandbox()), null, null, defaultConnections());
    }

    /** A database in a file, or in memory for a null path, with the options set before it opens. */
    public static Builder builder(String path) {
        return new Builder(path);
    }

    private static int defaultConnections() {
        return Math.max(4, Runtime.getRuntime().availableProcessors());
    }

    /** The options a database is opened with. */
    public static final class Builder {
        private final String path;
        private Hardening hardening = Hardening.NONE;
        private String historyLocation;
        private QuackMetrics metrics;
        private int connections = defaultConnections();

        private Builder(String path) {
            this.path = path;
        }

        /** Hardening, applied once when the database opens and before anything else runs on it. */
        public Builder hardening(Hardening hardening) {
            this.hardening = hardening;
            return this;
        }

        /** Where measures' history is kept: attached once, shipped to by {@link Quack#ship}. */
        public Builder history(String location) {
            this.historyLocation = location;
            return this;
        }

        /** Where the connection pool records what it does. */
        public Builder metrics(QuackMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        /** How many idle connections are kept for reuse. More can be open at once; the rest are closed. */
        public Builder connections(int idle) {
            if (idle < 1) {
                throw new IllegalArgumentException("Keep at least one connection: " + idle);
            }
            this.connections = idle;
            return this;
        }

        public Quack open() {
            DuckDBConnection root;
            try {
                root = (DuckDBConnection) DriverManager.getConnection(path == null ? "jdbc:duckdb:" : "jdbc:duckdb:" + path);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open " + (path == null ? "an in-memory database" : path), e);
            }
            try {
                MeasureHistory measureHistory = historyLocation == null ? null : MeasureHistory.at(historyLocation);
                Quack quack = new Quack(root, measureHistory, metrics, connections);
                if (hardening == Hardening.DATABASE) {
                    // After the history is attached: hardening stops a database attaching files.
                    Hardening.apply(root);
                }
                return quack;
            }
            catch (RuntimeException e) {
                Sql.closeQuietly(root);
                throw e;
            }
        }
    }

    // ---------- reading ----------

    /** A query, run when one of its terminal methods is called; each borrows a connection and returns it. */
    public Rows query(String sql, Object... parameters) {
        checkOpen();
        return Rows.of(lending, sql, parameters);
    }

    /** A measure query - as of a point, pivoted, filtered - run on a connection borrowed for it. */
    public Rows query(MeasureQuery query) {
        checkOpen();
        return query.run(lending);
    }

    /** A guard for queries nobody has vetted, on this database. Safe to share between threads. */
    public GuardedQuery guarded(QueryPolicy policy) {
        checkOpen();
        return GuardedQuery.on(lending, policy);
    }

    /** What this database holds, described: tables, columns, comments, profiles. */
    public Catalog catalog() {
        checkOpen();
        return Catalog.of(lending);
    }

    /** The rows whose vector in {@code column} is nearest a given one. */
    public io.quackjvm.core.vector.VectorSearch vectors(String table, String column) {
        checkOpen();
        return io.quackjvm.core.vector.VectorSearch.on(lending, table, column);
    }

    // ---------- writing ----------

    /** A statement that changes something, with {@code ?} parameters; how many rows it changed. */
    public int execute(String sql, Object... parameters) {
        return withConnection(connection -> Sql.executeUpdate(connection, sql, Arrays.asList(parameters)));
    }

    /** Creates the tables of the measures, and their history tables if there is a history. Safe to call again. */
    public void create(MeasureTable... measures) {
        withConnection(connection -> {
            for (MeasureTable measure : measures) {
                measure.create(connection);
            }
            MeasureRefresh.createLedger(connection);
            if (history != null) {
                history.create(connection, measures);
            }
            return null;
        });
    }

    /** Creates the tables of a sparse table. Safe to call again. */
    public void create(SparseTable table) {
        withConnection(connection -> {
            table.create(connection);
            return null;
        });
    }

    /**
     * Applies a refresh in a transaction of its own.
     *
     * @return false if a refresh with this id was committed before, in which case nothing was written
     */
    public boolean commit(MeasureRefresh refresh) {
        return withConnection(refresh::commit);
    }

    // ---------- measures' history ----------

    /** The history measures ship to; refuses if the database was opened without one. */
    public MeasureHistory history() {
        if (history == null) {
            throw new IllegalStateException("This database was opened without a history:"
                    + " Quack.builder(path).history(location).open()");
        }
        return history;
    }

    /** A query of a measure as it stood at a point, read across the history and what has not shipped yet. */
    public MeasureQuery asOf(MeasureTable measure, Instant at) {
        return measure.query().asOf(at, history());
    }

    /** Moves the measures' committed refreshes into the history. */
    public MeasureHistory.Shipped ship(MeasureTable... measures) {
        MeasureHistory to = history();
        return withConnection(connection -> to.ship(connection, measures));
    }

    /** Writes what the history holds of a measure to Parquet, a file per day of refresh, under {@code location}. */
    public MeasureHistory.Exported export(MeasureTable measure, String location) {
        MeasureHistory to = history();
        return withConnection(connection -> to.export(connection, measure, location));
    }

    // ---------- what lives as long as the database ----------

    /**
     * A Java collection, queryable as a table called {@code name}, read afresh by every query. Closed
     * with the database, or earlier through the handle.
     */
    public <O> LiveTable live(String name, Iterable<O> rows, ColumnarLayout<O> layout) {
        return register(() -> LiveTables.register(root, name, rows, layout));
    }

    /** As {@link #live(String, Iterable, ColumnarLayout)}, asking the supplier for the rows at every query. */
    public <O> LiveTable live(String name, Supplier<Iterable<O>> rows, ColumnarLayout<O> layout) {
        return register(() -> LiveTables.register(root, name, rows, layout));
    }

    /** A copy of the rows as they are now, queryable as a table called {@code name}. */
    public <O> LiveTable snapshot(String name, Iterable<O> rows, ColumnarLayout<O> layout) {
        return register(() -> LiveTables.snapshot(root, name, rows, layout));
    }

    private LiveTable register(Supplier<LiveTable> registration) {
        checkOpen();
        LiveTable table;
        // The root connection is used by nothing but registrations, one at a time.
        synchronized (root) {
            table = registration.get();
        }
        liveTables.add(table);
        return table;
    }

    /** A Java method callable from SQL, of one argument. A null argument is a null result. */
    public <A, R> Udf function(String name, Class<R> returns, Class<A> a, Function<A, R> function) {
        checkOpen();
        synchronized (root) {
            return Udfs.register(root, name, returns, a, function);
        }
    }

    /** A Java method callable from SQL, of two arguments. A null in either is a null result. */
    public <A, B, R> Udf function(String name, Class<R> returns, Class<A> a, Class<B> b,
                                  BiFunction<A, B, R> function) {
        checkOpen();
        synchronized (root) {
            return Udfs.register(root, name, returns, a, b, function);
        }
    }

    /** A Java method callable from SQL, of three arguments. A null in any is a null result. */
    public <A, B, C, R> Udf function(String name, Class<R> returns, Class<A> a, Class<B> b, Class<C> c,
                                     Udfs.Fn3<A, B, C, R> function) {
        checkOpen();
        synchronized (root) {
            return Udfs.register(root, name, returns, a, b, c, function);
        }
    }

    // ---------- transactions ----------

    /**
     * Runs the work in one transaction on one connection: committed when it returns, rolled back
     * when it throws. A conflict with another writer surfaces as the exception the commit threw;
     * the work is not retried, since it may have done things outside the database.
     */
    public void inTransaction(Tx.Work work) {
        fromTransaction(tx -> {
            work.run(tx);
            return null;
        });
    }

    /** As {@link #inTransaction(Tx.Work)}, returning what the work returned. */
    public <T> T fromTransaction(Tx.Result<T> work) {
        checkOpen();
        Connection connection = pool.borrow();
        boolean committed = false;
        boolean broken = false;
        Tx tx = null;
        try {
            try {
                connection.setAutoCommit(false);
            }
            catch (SQLException e) {
                broken = true;
                throw new IllegalStateException("Failed to start a transaction", e);
            }
            tx = new Tx(connection);
            T result = work.apply(tx);
            tx.end();
            try {
                connection.commit();
            }
            catch (SQLException e) {
                // The driver cannot be trusted with a connection whose commit failed.
                broken = true;
                throw new IllegalStateException("The transaction could not be committed: " + e.getMessage(), e);
            }
            committed = true;
            return result;
        }
        catch (RuntimeException | Error e) {
            throw e;
        }
        catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        finally {
            if (tx != null) {
                tx.end();
            }
            if (!committed && !broken) {
                try {
                    connection.rollback();
                }
                catch (SQLException e) {
                    broken = true;
                }
            }
            pool.release(connection, !broken, broken);
        }
    }

    // ---------- the way down ----------

    /**
     * A connection of this database's own, for anything not wrapped here. Closing it gives it back,
     * rolling back whatever was left uncommitted; until then it is the caller's alone.
     */
    public Connection connection() {
        checkOpen();
        return Connections.managed(pool.borrow(), pool, null);
    }

    public boolean isOpen() {
        return !closed;
    }

    /** Takes away the live tables still registered, closes the pooled connections, then the database. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        synchronized (root) {
            for (LiveTable table : liveTables) {
                try {
                    table.close();
                }
                catch (RuntimeException ignored) {
                    // Closing the database takes the view with it; what matters is the next one.
                }
            }
            liveTables.clear();
            closed = true;
        }
        pool.close();
        Sql.closeQuietly(root);
    }

    private <T> T withConnection(ConnectionWork<T> work) {
        checkOpen();
        Connection connection = pool.borrow();
        try {
            return work.apply(connection);
        }
        catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        finally {
            pool.release(connection);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("This database has been closed");
        }
    }

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @Override
    public String toString() {
        return "Quack[" + (closed ? "closed" : "open") + (history == null ? "" : ", history " + history.getLocation()) + "]";
    }
}
