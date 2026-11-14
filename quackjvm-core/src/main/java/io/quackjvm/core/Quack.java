package io.quackjvm.core;

import io.quackjvm.core.duckdb.ConnectionPool;
import io.quackjvm.core.duckdb.Connections;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.internal.LendingConnection;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureQuery;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.sparse.SparseTable;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBConnection;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * A DuckDB database, and everything quackjvm does with it, behind one object that owns the
 * connections - so the code using it never holds one.
 *
 * <pre>
 * try (Quack db = Quack.open("live.duckdb")) {
 *     db.create(exposure);
 *     db.commit(MeasureRefresh.at(nine, "09:00").full(exposure, batch));
 *     db.query("SELECT count(*) FROM car").scalar(Long.class);
 * }
 * </pre>
 *
 * <p>It is safe to share between threads. Every call borrows a pooled connection for as long as it
 * needs one and gives it back, on failure as well as success, so threads run on connections of their
 * own: a transaction belongs to a connection, and one shared between two threads would let one roll
 * back the other's work. What the database shares between its connections - an attached history, a
 * setting, a registered function - is done once, here, and every connection sees it.</p>
 *
 * <p>A transaction is {@link #inTransaction}: one connection, committed when the work returns and
 * rolled back when it throws. {@link #connection()} is the way down to DuckDB itself, for anything not
 * wrapped here. The connection-taking methods of every class underneath remain, for code that has a
 * pool of its own.</p>
 *
 * <p>Guarded queries, the catalog, live tables, Java functions and vector search are in
 * {@code quackjvm-agent}, and take a {@code Quack} as well as a connection.</p>
 */
public final class Quack implements AutoCloseable {

    private final DuckDBConnection root;
    private final ConnectionPool pool;
    private final Connection lending;
    private final MeasureHistory history;
    private final List<AutoCloseable> registered = new CopyOnWriteArrayList<>();
    private final Map<String, IcebergCatalog> catalogs;
    private final List<ObjectStore> stores;
    private final int retries;
    private volatile boolean closed;

    private Quack(DuckDBConnection root, MeasureHistory history, QuackMetrics metrics, int connections,
                  Map<String, IcebergCatalog> catalogs, List<ObjectStore> stores) {
        this.root = root;
        this.history = history;
        this.catalogs = Map.copyOf(catalogs);
        this.stores = List.copyOf(stores);
        this.retries = catalogs.values().stream().mapToInt(IcebergCatalog::retries).max().orElse(0);
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
            return new Quack((DuckDBConnection) existing.duplicate(), null, null, defaultConnections(), Map.of(), List.of());
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to open a connection to the given database", e);
        }
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
        private final Properties settings = new Properties();
        private final List<ObjectStore> stores = new ArrayList<>();
        private final Map<String, IcebergCatalog> catalogs = new LinkedHashMap<>();
        private String historyLocation;
        private QuackMetrics metrics;
        private int connections = defaultConnections();

        private Builder(String path) {
            this.path = path;
        }

        /**
         * DuckDB settings the database is opened with, such as {@code threads} or
         * {@code memory_limit}. Some can only be set here, before anything else runs.
         */
        public Builder settings(Properties settings) {
            this.settings.putAll(settings);
            return this;
        }

        /**
         * An S3-compatible store that SQL may read and write {@code s3://} locations in. Called
         * again, adds another - each for the {@link ObjectStore#scope scope} it was given.
         */
        public Builder objectStore(ObjectStore store) {
            if (store == null) {
                throw new IllegalArgumentException("No object store given");
            }
            stores.add(store);
            return this;
        }

        /**
         * An Iceberg catalog, attached under a name: its tables are then {@code name.schema.table}
         * in SQL. A hardened database - external access off - is let reach the catalog's URL; the
         * {@code s3://} folders its tables are in must be allowed as for any other.
         */
        public Builder catalog(String name, IcebergCatalog catalog) {
            if (name == null || name.isEmpty() || catalog == null) {
                throw new IllegalArgumentException("A catalog needs a name and the catalog");
            }
            if (catalogs.putIfAbsent(name, catalog) != null) {
                throw new IllegalArgumentException("A catalog is already attached as " + name);
            }
            return this;
        }

        /** Where measures' history is kept: attached once, shipped to by {@link Quack#ship}. */
        public Builder history(String location) {
            this.historyLocation = location;
            return this;
        }

        /**
         * Opens the file read-only: nothing can be written, and other processes may open it
         * read-only at the same time - but not while one has it open for writing. See
         * {@link DatabaseInUse}.
         */
        public Builder readOnly(boolean readOnly) {
            if (readOnly) {
                settings.setProperty("duckdb.read_only", "true");
            }
            else {
                settings.remove("duckdb.read_only");
            }
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

        /**
         * Opens the database.
         *
         * @throws DatabaseInUse if another process has the file open in a way that excludes this one
         */
        public Quack open() {
            DuckDBConnection root;
            Properties opening = new Properties();
            opening.putAll(settings);
            // DuckDB loads no extension once external access is off, and changes nothing once the
            // configuration is locked: with a store or a catalog to set up, both wait until it is.
            boolean preparing = !stores.isEmpty() || !catalogs.isEmpty();
            Object externalAccess = preparing ? opening.remove("enable_external_access") : null;
            Object lock = preparing ? opening.remove("lock_configuration") : null;
            if (externalAccess != null && !Boolean.parseBoolean(externalAccess.toString()) && !catalogs.isEmpty()) {
                allowCatalogs(opening);
            }
            try {
                root = (DuckDBConnection) DriverManager.getConnection(
                        path == null ? "jdbc:duckdb:" : "jdbc:duckdb:" + path, opening);
            }
            catch (SQLException e) {
                DatabaseInUse inUse = path == null ? null : DatabaseInUse.from(path, e);
                if (inUse != null) {
                    throw inUse;
                }
                throw new IllegalStateException("Failed to open " + (path == null ? "an in-memory database" : path), e);
            }
            try {
                if (preparing) {
                    if (!stores.isEmpty()) {
                        ObjectStore.prepare(root, stores);
                    }
                    int index = 0;
                    for (Map.Entry<String, IcebergCatalog> catalog : catalogs.entrySet()) {
                        IcebergCatalog.attach(root, catalog.getKey(), catalog.getValue(), !stores.isEmpty(), ++index);
                    }
                    if (externalAccess != null && !Boolean.parseBoolean(externalAccess.toString())) {
                        Sql.execute(root, "SET enable_external_access = false");
                    }
                    if (lock != null && Boolean.parseBoolean(lock.toString())) {
                        Sql.execute(root, "SET lock_configuration = true");
                    }
                }
                MeasureHistory measureHistory = historyLocation == null ? null : MeasureHistory.at(historyLocation);
                return new Quack(root, measureHistory, metrics, connections, catalogs, stores);
            }
            catch (RuntimeException e) {
                Sql.closeQuietly(root);
                throw e;
            }
        }

        /** Adds each catalog's URL to {@code allowed_directories}, as a DuckDB list literal. */
        private void allowCatalogs(Properties opening) {
            String list = opening.getProperty("allowed_directories", "[]").trim();
            StringBuilder more = new StringBuilder();
            for (IcebergCatalog catalog : catalogs.values()) {
                more.append(", '").append(catalog.allowedFolder().replace("\\", "\\\\").replace("'", "\\'")).append('\'');
            }
            String body = list.substring(1, list.length() - 1).trim();
            opening.setProperty("allowed_directories", "[" + (body.isEmpty() ? more.substring(2) : body + more) + "]");
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

    // ---------- writing ----------

    /**
     * A statement that changes something, with {@code ?} parameters; how many rows it changed. One
     * that writes to an {@link IcebergCatalog}'s table and conflicts with another writer's commit is
     * run again, up to the catalog's {@link IcebergCatalog#retries retries}: a statement fails or
     * commits as a whole, so running it again is safe.
     */
    public int execute(String sql, Object... parameters) {
        for (int attempt = 0; ; attempt++) {
            try {
                return withConnection(connection -> Sql.executeUpdate(connection, sql, Arrays.asList(parameters)));
            }
            catch (IllegalStateException e) {
                if (attempt >= retries || !IcebergCatalog.isConflict(e)) {
                    throw e;
                }
                pause(attempt);
            }
        }
    }

    /**
     * Writes a table to a Parquet file at a location: a path, or an {@code s3://} location in an
     * {@link Builder#objectStore object store}. Replaces a file already there.
     *
     * @return how many rows were written
     */
    public long exportTable(String table, String location) {
        if (location == null || location.isEmpty()) {
            throw new IllegalArgumentException("No location to export " + table + " to");
        }
        return withConnection(connection -> (long) Sql.executeUpdate(connection, "COPY " + Sql.quote(table)
                + " TO '" + location.replace("'", "''") + "' (FORMAT parquet)", List.of()));
    }

    /** Creates a table with a column per field of the layout, keyed on the first. Safe to call again. */
    public <O> void createTable(String name, ColumnarLayout<O> layout) {
        withConnection(connection -> {
            Tx.writer(name, layout).createTable(connection, true);
            return null;
        });
    }

    /**
     * Writes the objects into the table {@code name}, replacing any already stored under the same key
     * - the layout's first column - in one transaction, so a failure leaves the table as it was.
     *
     * @return how many rows were written
     */
    public <O> long write(String name, ColumnarLayout<O> layout, Iterable<O> objects) {
        return fromTransaction(tx -> tx.write(name, layout, objects));
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
     * Registers something on the database itself - a function, a table function - which every
     * connection then sees. Registrations run one at a time; one that returns an
     * {@link AutoCloseable} is closed with the database, unless closed before.
     */
    public <T> T register(Function<DuckDBConnection, T> registration) {
        checkOpen();
        T result;
        // The root connection is used by nothing but registrations, one at a time.
        synchronized (root) {
            result = registration.apply(root);
        }
        if (result instanceof AutoCloseable closeable) {
            registered.add(closeable);
        }
        return result;
    }

    // ---------- transactions ----------

    /**
     * Runs the work in one transaction on one connection: committed when it returns, rolled back
     * when it throws. A conflict with another writer surfaces as the exception the commit threw -
     * {@link CommitConflict} for an {@link IcebergCatalog}'s table. The work is not run again, since
     * it may have done things outside the database: {@link #retrying} does, for work that does not.
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
                if (IcebergCatalog.isConflict(e)) {
                    throw new CommitConflict("The transaction could not be committed, as another writer"
                            + " committed first: " + e.getMessage(), e);
                }
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

    /**
     * As {@link #inTransaction}, but run again from the start when the commit meets another writer's
     * ({@link CommitConflict}), up to the catalog's {@link IcebergCatalog#retries retries}, after a
     * short random wait. The work may therefore run more than once: it should do nothing but read
     * and write the database.
     */
    public void retrying(Tx.Work work) {
        for (int attempt = 0; ; attempt++) {
            try {
                inTransaction(work);
                return;
            }
            catch (CommitConflict e) {
                if (attempt >= retries) {
                    throw e;
                }
                pause(attempt);
            }
        }
    }

    // ---------- Iceberg tables' upkeep ----------

    /**
     * Tidies an {@link IcebergCatalog}'s table, {@code catalog.schema.table}:
     * <ol>
     *     <li>merges its small files into larger ones;</li>
     *     <li>removes the snapshots taken more than {@code keepFor} ago, but the current one and any a
     *     branch or tag points to - the table can no longer be read as it stood then;</li>
     *     <li>deletes from storage the files in the table's folders that no remaining snapshot refers
     *     to and that are older than {@code keepFor}.</li>
     * </ol>
     * Every insert adds a file and a snapshot, and both stay until removed: measured, 200 one-row
     * inserts took a fresh query from 34 ms to as much as 273 ms, and the merge brought it back to 29.
     *
     * <p>{@code keepFor} is also what keeps another writer's work safe: a file it has written but
     * not yet committed is referred to by no snapshot, and only its age protects it. Keep it longer
     * than any write takes - hours, not seconds.</p>
     *
     * <p>Files are deleted through this database's {@link ObjectStore}s, or from the local disk; files
     * elsewhere are counted in {@link Tidied#filesKept}. A table whose properties put files outside its
     * own folders has nothing deleted.</p>
     */
    public Tidied tidy(String table, Duration keepFor) {
        if (keepFor == null || keepFor.isNegative()) {
            throw new IllegalArgumentException("How long to keep snapshots for cannot be negative: " + keepFor);
        }
        String[] parts = table == null ? new String[0] : table.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("A table is catalog.schema.table: " + table);
        }
        IcebergCatalog catalog = catalogs.get(parts[0]);
        if (catalog == null) {
            throw new IllegalArgumentException("No catalog is attached as " + parts[0] + ": " + catalogs.keySet());
        }
        return withConnection(connection -> {
            try {
                return new IcebergTidy(connection, catalog, stores, parts[0], parts[1], parts[2]).tidy(keepFor);
            }
            catch (IOException e) {
                throw new IllegalStateException("Could not tidy " + table + ": " + catalog.scrub(e.getMessage()), e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while tidying " + table, e);
            }
        });
    }

    /** How long to wait before attempt {@code attempt + 1}: random, so writers that met do not meet again. */
    static long backoff(int attempt) {
        long ceiling = Math.min(1_000, 20L << Math.min(attempt, 10));
        return 5 + ThreadLocalRandom.current().nextLong(ceiling);
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(backoff(attempt));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry a commit", e);
        }
    }

    // ---------- the way down ----------

    /**
     * A connection that borrows a pooled one for each statement and gives it back, for a helper that
     * keeps a connection for life. Safe to share between threads; it cannot hold a transaction, and
     * closing it does nothing.
     */
    public Connection sharedConnection() {
        checkOpen();
        return lending;
    }

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

    /** Closes what was registered and is still open, then the pooled connections, then the database. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        synchronized (root) {
            for (AutoCloseable closeable : registered) {
                try {
                    closeable.close();
                }
                catch (Exception ignored) {
                    // Closing the database takes the registration with it; what matters is the next one.
                }
            }
            registered.clear();
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
