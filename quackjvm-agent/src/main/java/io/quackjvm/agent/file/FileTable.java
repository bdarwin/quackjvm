package io.quackjvm.agent.file;

import io.quackjvm.agent.catalog.QueryColumns;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.live.LiveTable;
import io.quackjvm.agent.live.LiveTables;
import io.quackjvm.agent.udf.Udfs;
import io.quackjvm.core.DatabaseInUse;
import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;
import org.duckdb.DuckDBConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleConsumer;

/**
 * A big file turned into a SQL table, kept on disk between runs and up to date as the file grows:
 * the application says how to read rows and split them, this does the rest.
 *
 * <pre>
 * FileTable log = FileTables.builder(path, columns, LineSource.of(path), parser)
 *         .cacheDir(cache).roomLimit(5L &lt;&lt; 30).version("viewer-3")
 *         .open();
 * log.update(signal, progress -&gt; bar.set(progress));     // the lines written since, or all of them
 * GuardedResult r = log.query("SELECT level, count(*) FROM t GROUP BY 1", QueryPolicy.readOnly(), signal);
 * </pre>
 *
 * <h2>What is kept</h2>
 *
 * <p>One DuckDB file per source file in the cache folder, named from the file's path, the columns
 * and the application's {@link Builder#version}. It holds the rows, how many there are, and a
 * fingerprint of the file's first bytes (up to 64 KB). Opened again after a restart, it is used as
 * it is: nothing is read from the source until {@link #update} asks for the rows written since.</p>
 *
 * <h2>Growing, and being replaced</h2>
 *
 * <p>{@link #update} appends the rows from the last one stored to {@link RowSource#rowCount()}. The
 * file counts as replaced - and the table is emptied and loaded again - when {@link
 * RowSource#identity()} changed since the last update, when it has fewer rows than are stored, or
 * when its first bytes no longer match the fingerprint; across a restart only the last two can be
 * told. Rows are parsed on several threads, a block at a time, and stored a batch of blocks at a time,
 * each batch in one transaction with the count of rows stored - so a query sees whole batches, and
 * an update that was stopped or cut off resumes after the last batch stored.</p>
 *
 * <h2>Wide files: lazy columns</h2>
 *
 * <p>With {@link Builder#lazyColumns(boolean)}, an update stores only the columns already asked for,
 * and {@link #query} first works out which columns the query reads - by planning it over an empty
 * table with the same columns, see {@link QueryColumns} - then stores those it lacks, then runs it.
 * A query that cannot be planned, or would add more columns than {@link
 * Builder#maxNewColumnsPerQuery}, is refused with the reason; {@link #whyNot} says so beforehand.</p>
 *
 * <h2>Rows that are not rows</h2>
 *
 * <p>A parser can leave out a source row - a header, a blank line, a line inside a quoted value -
 * through {@link RowParser#isRow}. It is not stored, but counts as read ({@link #rowsLoaded()}), so
 * growing and resuming are as before; {@link #rowsStored()} counts the rows held. The rows kept keep
 * their source row numbers. Every pass over the file must leave out the same rows: a lazy column
 * whose pass does not is refused.</p>
 *
 * <h2>Columns the application computes</h2>
 *
 * <p>{@link #column(ColumnDef, RowFunction)} adds, to an open table with lazy columns, a column the
 * application works out from the whole raw row - a total over many cells. It is planned and stored
 * as a lazy column is, and filled by updates from then on. It is not part of the cache's name, and
 * its stored values are dropped when the cache is opened again, until it is added again.</p>
 *
 * <h2>Java functions</h2>
 *
 * <p>{@link Builder#function} adds a Java function its queries can call - a label for a time in the
 * application's own time zone, say, or a test made from what the person typed - and {@link #function}
 * adds or replaces one on an open table. Only the application adds them; SQL cannot. A query running
 * when one is replaced keeps the one it began with. A cancelled query stops within a millisecond or
 * so even inside a slow function: DuckDB alone would let the function finish the chunk of 2,048 rows
 * it was given.</p>
 *
 * <h2>In a query session</h2>
 *
 * <p>{@link io.quackjvm.agent.session.QuerySession#register(String, FileTable)} attaches the cache to
 * the session's database, so its queries join the table with the others. While it is there, the
 * table is queried through the session; {@link #query} says so.</p>
 *
 * <h2>Hardening</h2>
 *
 * <p>The cache database is opened with no file, network or extension access and its settings
 * locked. Measured on DuckDB 2.0: SQL can then read none of the cache folder's other files - the
 * other caches - only this database's own file, its write-ahead log and its temp folder, which hold
 * nothing the table does not.</p>
 */
public final class FileTable implements AutoCloseable {

    static {
        io.quackjvm.agent.file.internal.FileTableAccess.set(new Access());
    }

    static final String DATA = "quackjvm_data";
    static final String META = "quackjvm_filetable";
    static final String ROW = "quackjvm_row";
    static final String STAGE = "quackjvm_stage";
    static final int FINGERPRINT_BYTES = 64 * 1024;
    /** Blocks parsed per thread before a batch is stored. */
    static final int BLOCKS_PER_THREAD = 8;

    private final Path file;
    private final List<ColumnDef> columns;
    private final RowSource source;
    private final RowParser parser;
    private final String name;
    private final int threads;
    private final int blockSize;
    private final boolean lazy;
    private final int maxNewColumns;
    private final Path cacheDir;
    private final long roomLimit;
    private final Path cacheFile;
    /** Where the rows are: a database of the table's own, or a session's with the cache attached. */
    private volatile Home home;
    /** One update at a time; the writer lock is taken per batch, so other work can come between. */
    private final ReentrantLock updating = new ReentrantLock();
    private final List<DuckDBConnection> loaders = new ArrayList<>();
    private final ReentrantLock writer = new ReentrantLock();
    /** Signalled when the table has a database again, after a session let go of it to move it. */
    private final java.util.concurrent.locks.Condition homed = writer.newCondition();
    private final AtomicLong rowsRead = new AtomicLong();
    private final QueryColumns planner;
    private final Connection plannerSource;
    private final TableFunctions functions = new TableFunctions();
    /** Columns the application computes, added with {@link #column}: by lower-case name, in the order added. */
    private volatile Map<String, Computed> computed = Map.of();
    /** Counts changes to the columns, for a session to plan over the new ones. */
    private final java.util.concurrent.atomic.AtomicInteger columnChanges = new java.util.concurrent.atomic.AtomicInteger();
    private Object identity;
    private volatile long rows;
    /** The rows stored: {@link #rows} less those the parser left out. */
    private volatile long kept;
    private volatile List<String> stored;
    private String fingerprint;
    private long fingerprintBytes;
    private volatile boolean closed;

    private FileTable(Builder b, Path cacheFile, Quack quack) {
        this.file = b.file.toAbsolutePath().normalize();
        this.columns = List.copyOf(b.columns);
        this.source = b.source;
        this.parser = b.parser;
        this.name = b.name;
        this.threads = b.threads;
        this.blockSize = b.blockSize;
        this.lazy = b.lazy;
        this.maxNewColumns = b.maxNewColumns;
        this.cacheDir = b.cacheDir;
        this.roomLimit = b.roomLimit;
        this.cacheFile = cacheFile;
        this.home = Home.own(quack);
        Connection planningSource = null;
        QueryColumns planning = null;
        if (lazy) {
            try {
                planningSource = DriverManager.getConnection("jdbc:duckdb:");
                StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(Sql.quote(name)).append(" (");
                for (int i = 0; i < columns.size(); i++) {
                    ddl.append(i == 0 ? "" : ", ").append(columns.get(i).toDdl());
                }
                Sql.execute(planningSource, ddl.append(')').toString());
                planning = QueryColumns.of(planningSource);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open the planning database", e);
            }
        }
        this.plannerSource = planningSource;
        this.planner = planning;
        for (java.util.function.Function<TableFunctions, TableFunctions.Registrar> added : b.functions) {
            register(added.apply(functions));
        }
    }

    // ---------- updating ----------

    /**
     * What an update did. {@code rowsAdded} and {@code rows} count the source's rows read, those the
     * parser left out included; {@link #rowsStored()} leaves them out.
     */
    public record Updated(long rowsAdded, boolean rebuilt, long rows) {
    }

    /**
     * Stores the rows written since the last update - or, if the file was replaced, empties the
     * table and stores them all. Stopped through the signal, it throws {@link QueryCancelled} and
     * keeps the batches stored by then, so the next update goes on from there.
     *
     * @param progress told the share of the rows stored so far, 0 to 1; may be null
     */
    public Updated update(CancelSignal signal, DoubleConsumer progress) {
        updating.lock();
        try {
            long target;
            boolean replaced;
            long before;
            writer.lock();
            try {
                awaitHome();
                target = source.rowCount();
                Object now = source.identity();
                replaced = (identity != null && !identity.equals(now)) || target < rows || !fingerprintMatches();
                identity = now;
                if (replaced) {
                    empty();
                }
                before = rows;
            }
            finally {
                writer.unlock();
            }
            long batch = (long) threads * BLOCKS_PER_THREAD * blockSize;
            while (rows < target) {
                if (signal != null && signal.poll()) {
                    throw new QueryCancelled(null);
                }
                // The writer lock per batch: a query storing lazy columns, or a session moving the
                // table to another database, goes between two batches rather than after them all.
                writer.lock();
                try {
                    awaitHome();
                    if (rows < target) {
                        appendBatch(rows, (int) Math.min(batch, target - rows), signal);
                    }
                }
                finally {
                    writer.unlock();
                }
                if (progress != null) {
                    progress.accept(target == 0 ? 1 : (double) rows / target);
                }
            }
            if (progress != null && target == 0) {
                progress.accept(1);
            }
            used();
            if (cacheDir != null) {
                FileTables.trim(cacheDir, roomLimit);
            }
            return new Updated(Math.max(0, rows - before), replaced, rows);
        }
        finally {
            updating.unlock();
        }
    }

    /** As {@link #update(CancelSignal, DoubleConsumer)}, unstoppable and silent. */
    public Updated update() {
        return update(null, null);
    }

    // ---------- querying ----------

    /**
     * Runs a guarded query over the table - called by the name given to the builder, {@code t} unless
     * set. With lazy columns, stores the columns it reads that are not stored yet, first.
     *
     * @throws QueryRejected if it is not a query the policy allows, or - with lazy columns - its
     *                       columns cannot be told or are too many to add; {@link #whyNot} says why
     */
    public GuardedResult query(String sql, QueryPolicy policy, CancelSignal signal) {
        checkOpen();
        checkOwnHome();
        if (lazy) {
            Set<String> needed = needed(sql);
            Set<String> missing = new LinkedHashSet<>(needed);
            missing.removeAll(stored);
            if (!missing.isEmpty()) {
                writer.lock();
                try {
                    missing.removeAll(stored);
                    if (!missing.isEmpty()) {
                        addColumns(missing, signal);
                    }
                }
                finally {
                    writer.unlock();
                }
            }
        }
        used();
        GuardedQuery guard = GuardedQuery.on(quack(), policy);
        if (functions.isEmpty()) {
            return guard.run(sql, signal);
        }
        for (int attempt = 0; ; attempt++) {
            functions.enter(signal);
            try {
                return guard.run(sql, signal);
            }
            catch (QueryFailed failed) {
                // A function stopped for another query's cancel: run again once that one has gone.
                if (!TableFunctions.stoppedHere(failed) || attempt >= RERUNS) {
                    throw failed;
                }
            }
            finally {
                functions.leave(signal);
            }
            functions.awaitClear(signal);
        }
    }

    /** How many times a query stopped for another query's cancel is run again. */
    static final int RERUNS = 10;

    // ---------- Java functions ----------

    /**
     * Adds a Java function the table's queries can call, or replaces the one of that name - as
     * {@link Udfs#register} does on a database. A query already running keeps the one it started
     * with; the next ones get this. A row with a null argument is null, without calling it.
     */
    public <A, R> void function(String name, Class<R> returns, Class<A> a, java.util.function.Function<A, R> function) {
        checkOpen();
        register(functions.add(name, returns, a, function));
    }

    /** As {@link #function(String, Class, Class, java.util.function.Function)}, of two arguments. */
    public <A, B, R> void function(String name, Class<R> returns, Class<A> a, Class<B> b,
                                   java.util.function.BiFunction<A, B, R> function) {
        checkOpen();
        register(functions.add(name, returns, a, b, function));
    }

    /** As {@link #function(String, Class, Class, java.util.function.Function)}, of three arguments. */
    public <A, B, C, R> void function(String name, Class<R> returns, Class<A> a, Class<B> b, Class<C> c,
                                      Udfs.Fn3<A, B, C, R> function) {
        checkOpen();
        register(functions.add(name, returns, a, b, c, function));
    }

    // ---------- columns the application computes ----------

    /**
     * Adds a column whose value the application works out from the whole raw row - the total of a
     * row's {@code cpu_*} cells, say. Queries can read it from now on. It is stored as a lazy column
     * is: the first time a query reads it, by reading the file again for just that column; from then
     * on {@link #update} fills it for new rows. Rows the parser leaves out get no value: they are not
     * stored.
     *
     * <p>It belongs to this session of the application, not to the cache: the cache file's name does
     * not depend on it. Values stored for one are dropped when the cache is opened again, so a column
     * of that name must be added again, and is computed again when a query reads it - never read
     * from a function that may since have changed.</p>
     *
     * <p>Adding one of the same name again replaces it: values stored by the earlier function are
     * dropped, and the next query reading it stores it again.</p>
     *
     * @param value called on the loading threads, several at once: it must keep no state between rows
     * @throws IllegalStateException    if the table does not have {@link Builder#lazyColumns lazy
     *                                  columns}, or is closed
     * @throws IllegalArgumentException if the name is one of the parser's columns, or {@code quackjvm_row}
     */
    public void column(ColumnDef column, RowFunction value) {
        java.util.Objects.requireNonNull(column, "column");
        java.util.Objects.requireNonNull(value, "value");
        checkOpen();
        if (!lazy) {
            throw new IllegalStateException("Computed columns need a table with lazyColumns(true): they are stored"
                    + " when a query first reads them");
        }
        String key = column.getName().toLowerCase(Locale.ROOT);
        if (column.getName().equalsIgnoreCase(ROW) || columns.stream().anyMatch(c -> c.getName().equalsIgnoreCase(key))) {
            throw new IllegalArgumentException("Column " + column.getName() + " is one of the parser's columns, or "
                    + ROW + ", which the table uses itself");
        }
        writer.lock();
        try {
            awaitHome();
            Computed before = computed.get(key);
            if (before != null) {
                dropStored(before.column().getName());
            }
            Map<String, Computed> was = computed;
            Map<String, Computed> next = new java.util.LinkedHashMap<>(was);
            next.remove(key);
            next.put(key, new Computed(column, value));
            // Known to the table before the planner, so a query planned with it finds it.
            computed = java.util.Collections.unmodifiableMap(next);
            try {
                replan(before == null ? null : before.column(), column);
            }
            catch (RuntimeException e) {
                computed = was;
                throw e;
            }
            columnChanges.incrementAndGet();
        }
        finally {
            writer.unlock();
        }
    }

    /** The columns added with {@link #column}, in the order added. */
    public List<ColumnDef> computedColumns() {
        return computed.values().stream().map(Computed::column).toList();
    }

    private record Computed(ColumnDef column, RowFunction function) {
    }

    /** Drops a computed column's stored values, if it has any: it is stored again when a query reads it. */
    private void dropStored(String column) {
        String have = stored.stream().filter(s -> s.equalsIgnoreCase(column)).findFirst().orElse(null);
        if (have == null) {
            return;
        }
        List<String> after = new ArrayList<>(stored);
        after.remove(have);
        quack().inTransaction(tx -> {
            Connection connection = tx.connection();
            Sql.execute(connection, "DROP VIEW IF EXISTS " + view());
            Sql.execute(connection, "ALTER TABLE " + data() + " DROP COLUMN " + Sql.quote(have));
            makeView(connection, after);
            recordStored(connection, after);
        });
        stored = List.copyOf(after);
    }

    /** The empty table lazy columns are planned over, with a computed column added or replaced. */
    private void replan(ColumnDef before, ColumnDef now) {
        try {
            if (before != null) {
                Sql.execute(plannerSource, "ALTER TABLE " + Sql.quote(name) + " DROP COLUMN " + Sql.quote(before.getName()));
            }
            Sql.execute(plannerSource, "ALTER TABLE " + Sql.quote(name) + " ADD COLUMN " + now.toDdl());
        }
        catch (RuntimeException e) {
            if (before != null) {
                try {
                    Sql.execute(plannerSource, "ALTER TABLE " + Sql.quote(name) + " ADD COLUMN IF NOT EXISTS "
                            + before.toDdl());
                }
                catch (RuntimeException again) {
                    e.addSuppressed(again);
                }
            }
            throw new IllegalArgumentException("Cannot add column " + now.getName() + " to " + name + ": " + e.getMessage(), e);
        }
        planner.refresh();
    }

    /** All the columns queries can read: the parser's, then the computed ones. */
    List<ColumnDef> allColumns() {
        Map<String, Computed> now = computed;
        if (now.isEmpty()) {
            return columns;
        }
        List<ColumnDef> all = new ArrayList<>(columns);
        for (Computed c : now.values()) {
            all.add(c.column());
        }
        return all;
    }

    int columnChanges() {
        return columnChanges.get();
    }

    /** On the table's database, and on the empty one lazy columns are planned over. */
    private void register(TableFunctions.Registrar registrar) {
        try (Connection connection = quack().connection()) {
            registrar.register(connection);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to register a function on " + name + ": " + e.getMessage(), e);
        }
        if (planner != null) {
            planner.register(registrar::register);
        }
    }

    /**
     * Why a query would be refused - by the guard, or because its columns cannot be told or are too
     * many to add - without running it; null if it would run.
     */
    public String whyNot(String sql) {
        checkOpen();
        checkOwnHome();
        String rejected = GuardedQuery.on(quack(), QueryPolicy.readOnly()).whyRejected(sql);
        if (rejected != null || !lazy) {
            return rejected;
        }
        try {
            needed(sql);
            return null;
        }
        catch (QueryRejected refused) {
            return refused.getMessage();
        }
    }

    // ---------- what is there ----------

    /**
     * How many of the source's rows have been read in: the next {@link #update} goes on from there.
     * Rows the parser left out ({@link RowParser#isRow}) are counted; {@link #rowsStored()} leaves
     * them out.
     */
    public long rowsLoaded() {
        return rows;
    }

    /** How many rows the table holds: {@link #rowsLoaded()} less the rows the parser left out. */
    public long rowsStored() {
        return kept;
    }

    /**
     * The columns stored: all of them, or with lazy columns those queries have asked for - computed
     * ones ({@link #column}) included.
     */
    public List<String> storedColumns() {
        return stored;
    }

    /** How many rows have been read from the source since this was opened - by updates, and by lazy columns. */
    public long rowsRead() {
        return rowsRead.get();
    }

    /** The cache file, or null when the table is kept in memory. */
    public Path cacheFile() {
        return cacheFile;
    }

    public String getName() {
        return name;
    }

    public Path getFile() {
        return file;
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        // A session it is registered in lets it go first - under the session's lock, which comes
        // before the table's.
        Runnable release = home.release();
        if (release != null) {
            release.run();
        }
        writer.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            homed.signalAll();
            leave();
            if (planner != null) {
                planner.close();
                Sql.closeQuietly(plannerSource);
            }
            if (cacheFile != null) {
                FileTables.OPEN.remove(cacheFile);
            }
        }
        finally {
            writer.unlock();
        }
    }

    @Override
    public String toString() {
        return "FileTable[" + file.getFileName() + " as " + name + ", " + rows + " rows"
                + (lazy ? ", " + stored.size() + " of " + allColumns().size() + " columns" : "")
                + (cacheFile == null ? ", in memory" : "") + "]";
    }

    // ---------- moving to a session's database and back ----------

    /**
     * Moves the table into another database - a {@link io.quackjvm.agent.session.QuerySession}'s -
     * with the cache file attached to it as {@code catalog}, so that queries there can join it. A
     * table kept in memory has nothing to attach, so its rows are read from the file again, there.
     * {@code release} is run, before anything else, when the table is closed.
     */
    void moveTo(Quack database, String catalog, Runnable release) {
        writer.lock();
        try {
            checkOpen();
            Home before = home;
            leave();
            try {
                enter(attached(database, catalog, release));
            }
            catch (RuntimeException e) {
                leave();
                try {
                    enter(reopened(before));   // back where it was
                }
                catch (RuntimeException again) {
                    e.addSuppressed(again);
                }
                throw e;
            }
        }
        finally {
            writer.unlock();
        }
    }

    /** Moves the table back to a database of its own: the cache file opened again, or memory. */
    void moveHome() {
        writer.lock();
        try {
            if (closed || home.owned()) {
                return;
            }
            leave();
            enter(ownHome());
        }
        finally {
            writer.unlock();
        }
    }

    /** Lets go of the session's database without going anywhere, for the table to be closed or moved. */
    void detach() {
        writer.lock();
        try {
            if (!closed && !home.owned()) {
                leave();
                home = new Home(null, null, null, false, null);
            }
        }
        finally {
            writer.unlock();
        }
    }

    boolean isHosted() {
        return !home.owned();
    }

    /** The files a session must let DuckDB use to attach the cache: none for a table in memory. */
    List<String> attachedFiles() {
        if (cacheFile == null) {
            return List.of();
        }
        Path real;
        try {
            real = cacheFile.toRealPath();
        }
        catch (IOException e) {
            real = cacheFile;
        }
        List<String> files = new ArrayList<>();
        for (Path database : new LinkedHashSet<>(List.of(cacheFile, real))) {
            // DuckDB 2.0 writes <db>.wal.checkpoint while it checkpoints: without it, the checkpoint
            // fails and the attached database is invalidated - measured.
            for (String suffix : new String[] {"", ".wal", ".wal.checkpoint"}) {
                files.add(database + suffix);
            }
        }
        return files;
    }

    List<ColumnDef> columns() {
        return columns;
    }

    boolean isLazy() {
        return lazy;
    }

    /** Stores lazy columns a session's query reads, refusing more than one query may add. */
    void store(Set<String> wanted, String sql, CancelSignal signal) {
        Set<String> missing = new LinkedHashSet<>();
        for (String column : wanted) {
            missing.add(column(column).getName());
        }
        missing.removeAll(stored);
        if (missing.isEmpty()) {
            return;
        }
        if (missing.size() > maxNewColumns) {
            throw new QueryRejected(sql, tooMany(missing.size()));
        }
        writer.lock();
        try {
            awaitHome();
            missing.removeAll(stored);
            if (!missing.isEmpty()) {
                addColumns(missing, signal);
            }
        }
        finally {
            writer.unlock();
        }
    }

    /** Why a session's query reading these columns would be refused; null if it would not. */
    String whyNotStore(Set<String> wanted) {
        Set<String> missing = new LinkedHashSet<>();
        for (String column : wanted) {
            missing.add(column(column).getName());
        }
        missing.removeAll(stored);
        return missing.size() > maxNewColumns ? tooMany(missing.size()) : null;
    }

    TableFunctions functions() {
        return functions;
    }

    private Home attached(Quack database, String catalog, Runnable release) {
        String target = cacheFile == null ? ":memory:" : cacheFile.toString();
        Sql.execute(database.sharedConnection(), "ATTACH " + literal(target) + " AS " + Sql.quote(catalog));
        return new Home(database, catalog, catalog + "_stage", false, release);
    }

    /** The home it left, again. */
    private Home reopened(Home before) {
        return before.owned() || before.quack() == null ? ownHome()
                : attached(before.quack(), before.catalog(), before.release());
    }

    private Home ownHome() {
        Quack quack = Quack.builder(cacheFile == null ? null : cacheFile.toString()).settings(Builder.hardened()).open();
        return Home.own(quack);
    }

    /** Starts the table in a home: its tables made or read, its staging and loaders, its functions. */
    private void enter(Home next) {
        home = next;
        start();
        for (TableFunctions.Registrar registrar : functions.all()) {
            try (Connection connection = next.quack().connection()) {
                registrar.register(connection);
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to register a function on " + name + ": " + e.getMessage(), e);
            }
        }
        if (cacheFile == null) {
            // In memory there was nothing to take along: the rows are read from the file again.
            identity = source.identity();
            long target = source.rowCount();
            long batch = (long) threads * BLOCKS_PER_THREAD * blockSize;
            while (rows < target) {
                appendBatch(rows, (int) Math.min(batch, target - rows), null);
            }
        }
        homed.signalAll();
    }

    /** Under the writer lock: waits while a session moves the table, then checks it is open. */
    private void awaitHome() {
        while (!closed && home.quack() == null) {
            homed.awaitUninterruptibly();
        }
        checkOpen();
    }

    /** Leaves the home it is in: loaders closed, staging and the cache detached, or its own database closed. */
    private void leave() {
        Home was = home;
        for (DuckDBConnection loader : loaders) {
            Sql.closeQuietly(loader);
        }
        loaders.clear();
        if (was.quack() == null) {
            return;
        }
        if (was.owned()) {
            was.quack().close();
            return;
        }
        for (String catalog : new String[] {was.stage(), was.catalog()}) {
            try {
                Sql.execute(was.quack().sharedConnection(), "DETACH DATABASE IF EXISTS " + Sql.quote(catalog));
            }
            catch (RuntimeException e) {
                // the session's database is closing
            }
        }
    }

    private void checkOwnHome() {
        if (!home.owned()) {
            throw new IllegalStateException(name + " over " + file.getFileName() + " is registered in a QuerySession:"
                    + " query it there, under the name it was registered with.");
        }
    }

    private static String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    /** The bridge a session uses to host the table. */
    private static final class Access implements io.quackjvm.agent.file.internal.FileTableAccess {
        @Override
        public void moveTo(FileTable table, Quack database, String catalog, Runnable release) {
            table.moveTo(database, catalog, release);
        }

        @Override
        public void moveHome(FileTable table) {
            table.moveHome();
        }

        @Override
        public void detach(FileTable table) {
            table.detach();
        }

        @Override
        public boolean isHosted(FileTable table) {
            return table.isHosted();
        }

        @Override
        public List<String> attachedFiles(FileTable table) {
            return table.attachedFiles();
        }

        @Override
        public List<ColumnDef> columns(FileTable table) {
            return table.allColumns();
        }

        @Override
        public int columnChanges(FileTable table) {
            return table.columnChanges();
        }

        @Override
        public boolean isLazy(FileTable table) {
            return table.isLazy();
        }

        @Override
        public void store(FileTable table, Set<String> columns, String sql, CancelSignal signal) {
            table.store(columns, sql, signal);
        }

        @Override
        public String whyNotStore(FileTable table, Set<String> columns) {
            return table.whyNotStore(columns);
        }

        @Override
        public List<java.util.function.Consumer<Connection>> functions(FileTable table) {
            List<java.util.function.Consumer<Connection>> all = new ArrayList<>();
            for (TableFunctions.Registrar registrar : table.functions().all()) {
                all.add(registrar::register);
            }
            return all;
        }

        @Override
        public void enter(FileTable table, CancelSignal signal) {
            table.functions().enter(signal);
        }

        @Override
        public void leave(FileTable table, CancelSignal signal) {
            table.functions().leave(signal);
        }

        @Override
        public void awaitClear(FileTable table, CancelSignal signal) {
            table.functions().awaitClear(signal);
        }

        @Override
        public boolean stoppedForAnother(Throwable failure) {
            return TableFunctions.stoppedHere(failure);
        }
    }

    // ---------- underneath: where the rows are ----------

    /**
     * The database the table's own tables are in, and the in-memory one it stages rows in. Owned, it
     * is the table's and closed with it; hosted, it is a session's, with the cache attached to it under
     * {@code catalog}, and {@code release} tells the session when the table is closed.
     */
    private record Home(Quack quack, String catalog, String stage, boolean owned, Runnable release) {

        static Home own(Quack quack) {
            String catalog = quack.query("SELECT current_database()").scalar(String.class);
            return new Home(quack, catalog, STAGE, true, null);
        }
    }

    private Quack quack() {
        return home.quack();
    }

    private String data() {
        return Sql.quote(home.catalog()) + ".main." + DATA;
    }

    private String meta() {
        return Sql.quote(home.catalog()) + ".main." + META;
    }

    private String stage() {
        return Sql.quote(home.stage());
    }

    private String view() {
        return Sql.quote(home.catalog()) + ".main." + Sql.quote(name);
    }

    // ---------- underneath: opening ----------

    private void start() {
        quack().inTransaction(tx -> {
            Connection connection = tx.connection();
            Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + meta() + " (rows BIGINT, fingerprint VARCHAR,"
                    + " fingerprint_bytes BIGINT, stored VARCHAR, source VARCHAR)");
            boolean fresh = Sql.queryLong(connection, "SELECT count(*) FROM " + meta(), List.of()) == 0;
            if (fresh) {
                StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE " + data() + " (" + ROW + " BIGINT");
                List<String> initial = new ArrayList<>();
                if (!lazy) {
                    for (ColumnDef column : columns) {
                        ddl.append(", ").append(column.toDdl());
                        initial.add(column.getName());
                    }
                }
                Sql.execute(connection, ddl.append(')').toString());
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + meta()
                        + " VALUES (0, NULL, 0, ?, ?)")) {
                    insert.setString(1, String.join("\n", initial));
                    insert.setString(2, file.toString());
                    insert.executeUpdate();
                }
                catch (SQLException e) {
                    throw new IllegalStateException("Failed to start the cache", e);
                }
            }
        });
        try (Connection connection = quack().connection();
             PreparedStatement select = connection.prepareStatement("SELECT rows, fingerprint, fingerprint_bytes, stored"
                     + " FROM " + meta());
             ResultSet meta = select.executeQuery()) {
            meta.next();
            rows = meta.getLong(1);
            fingerprint = meta.getString(2);
            fingerprintBytes = meta.getLong(3);
            String list = meta.getString(4);
            stored = list == null || list.isEmpty() ? List.of() : List.of(list.split("\n"));
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read the cache", e);
        }
        dropForgotten();
        kept = Sql.queryLong(quack().sharedConnection(), "SELECT count(*) FROM " + data(), List.of());
        Sql.execute(quack().sharedConnection(), "ATTACH IF NOT EXISTS ':memory:' AS " + stage());
        makeView(quack().sharedConnection(), stored);
        try {
            for (int i = 0; i < threads; i++) {
                DuckDBConnection loader = quack().register(root -> {
                    try {
                        return (DuckDBConnection) root.duplicate();
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to open a loading connection", e);
                    }
                });
                Sql.execute(loader, "USE " + stage());
                loaders.add(loader);
            }
        }
        catch (RuntimeException e) {
            close();
            throw e;
        }
        used();
    }

    /**
     * Drops the stored computed columns not added in this session: values from a function that may
     * have changed since. A column added again is stored again when a query reads it.
     */
    private void dropForgotten() {
        List<String> after = new ArrayList<>();
        for (String column : stored) {
            if (findColumn(column) != null) {
                after.add(column);
            }
        }
        if (after.size() == stored.size()) {
            return;
        }
        List<String> forgotten = new ArrayList<>(stored);
        forgotten.removeAll(after);
        quack().inTransaction(tx -> {
            Connection connection = tx.connection();
            Sql.execute(connection, "DROP VIEW IF EXISTS " + view());
            for (String column : forgotten) {
                Sql.execute(connection, "ALTER TABLE " + data() + " DROP COLUMN IF EXISTS " + Sql.quote(column));
            }
            recordStored(connection, after);
        });
        stored = List.copyOf(after);
    }

    private void makeView(Connection connection, List<String> columnsStored) {
        StringBuilder view = new StringBuilder("CREATE OR REPLACE VIEW ").append(view()).append(" AS SELECT ");
        if (columnsStored.isEmpty()) {
            // Nothing stored yet: the row number alone, so that count(*) can be answered.
            view.append(ROW);
        }
        else {
            for (int i = 0; i < columnsStored.size(); i++) {
                view.append(i == 0 ? "" : ", ").append(Sql.quote(columnsStored.get(i)));
            }
        }
        Sql.execute(connection, view.append(" FROM ").append(data()).toString());
    }

    // ---------- underneath: loading ----------

    /** Stores rows {@code first .. first + count - 1}, of the columns stored, in one transaction. */
    private void appendBatch(long first, int count, CancelSignal signal) {
        List<String> wanted = stored;
        long staged = stage(first, count, wanted, signal);
        String list = columnList(wanted);
        long end = first + count;
        String[] print = fingerprintAfter();
        try {
            quack().inTransaction(tx -> {
                Connection connection = tx.connection();
                Sql.execute(connection, "INSERT INTO " + data() + " (" + list + ") SELECT " + list + " FROM ("
                        + stageUnion() + ") ORDER BY " + ROW);
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + meta()
                        + " SET rows = ?, fingerprint = ?, fingerprint_bytes = ?")) {
                    update.setLong(1, end);
                    update.setString(2, print[0]);
                    update.setLong(3, Long.parseLong(print[1]));
                    update.executeUpdate();
                }
                catch (SQLException e) {
                    throw new IllegalStateException("Failed to record the rows stored", e);
                }
            });
        }
        finally {
            dropStage();
        }
        rows = end;
        kept += staged;
        fingerprint = print[0];
        fingerprintBytes = Long.parseLong(print[1]);
    }

    /** Empties the table, for a file that was replaced. Lazy columns already asked for stay asked for. */
    private void empty() {
        quack().inTransaction(tx -> {
            Sql.execute(tx.connection(), "DELETE FROM " + data());
            Sql.execute(tx.connection(), "UPDATE " + meta() + " SET rows = 0, fingerprint = NULL, fingerprint_bytes = 0");
        });
        rows = 0;
        kept = 0;
        fingerprint = null;
        fingerprintBytes = 0;
    }

    /** Stores the columns a query needs that are not stored yet: the whole file read again, for those. */
    private void addColumns(Set<String> missing, CancelSignal signal) {
        List<String> adding = new ArrayList<>(missing);
        List<String> after = new ArrayList<>(stored);
        after.addAll(adding);
        long count = rows;
        if (count > 0) {
            // A source opened since the rows were stored - after a restart - may not have looked
            // at its file yet: a LineSource finds its lines when asked how many there are.
            long there = source.rowCount();
            if (there < count) {
                throw new IllegalStateException("Cannot store " + adding + " for " + file + ": it has " + there
                        + " rows now, fewer than the " + count + " stored. It was replaced: update the table first.");
            }
            long batch = (long) threads * BLOCKS_PER_THREAD * blockSize;
            Sql.execute(quack().sharedConnection(), "CREATE OR REPLACE TABLE " + stage() + ".main.adding AS SELECT "
                    + ROW + " FROM " + data() + " LIMIT 0");
            for (String column : adding) {
                Sql.execute(quack().sharedConnection(), "ALTER TABLE " + stage() + ".main.adding ADD COLUMN "
                        + column(column).toDdl());
            }
            try {
                for (long first = 0; first < count; first += batch) {
                    stage(first, (int) Math.min(batch, count - first), adding, signal);
                    Sql.execute(quack().sharedConnection(), "INSERT INTO " + stage() + ".main.adding SELECT * FROM ("
                            + stageUnion() + ")");
                    dropStage();
                }
                // The rows the parser left out the first time are left out again; a parser deciding
                // otherwise would leave rows without the new values, so it is refused.
                long have = Sql.queryLong(quack().sharedConnection(), "SELECT count(*) FROM " + data(), List.of());
                long got = Sql.queryLong(quack().sharedConnection(), "SELECT count(*) FROM " + stage() + ".main.adding",
                        List.of());
                if (have != got) {
                    throw new IllegalStateException("Cannot store " + adding + " for " + file + ": of its first " + count
                            + " rows, the parser kept " + got + " this time and " + have + " when they were stored. Its"
                            + " isRow must decide from the raw row and its number alone; if the file changed, update"
                            + " the table first.");
                }
                StringBuilder select = new StringBuilder("SELECT d.*");
                for (String column : adding) {
                    select.append(", a.").append(Sql.quote(column));
                }
                quack().inTransaction(tx -> {
                    Connection connection = tx.connection();
                    Sql.execute(connection, "CREATE TABLE " + data() + "_next AS " + select + " FROM " + data()
                            + " d JOIN " + stage() + ".main.adding a ON d." + ROW + " = a." + ROW + " ORDER BY d." + ROW);
                    Sql.execute(connection, "DROP VIEW IF EXISTS " + view());
                    Sql.execute(connection, "DROP TABLE " + data());
                    Sql.execute(connection, "ALTER TABLE " + data() + "_next RENAME TO " + DATA);
                    makeView(connection, after);
                    recordStored(connection, after);
                });
            }
            finally {
                dropStage();
                Sql.execute(quack().sharedConnection(), "DROP TABLE IF EXISTS " + stage() + ".main.adding");
            }
        }
        else {
            quack().inTransaction(tx -> {
                for (String column : adding) {
                    Sql.execute(tx.connection(), "ALTER TABLE " + data() + " ADD COLUMN " + column(column).toDdl());
                }
                makeView(tx.connection(), after);
                recordStored(tx.connection(), after);
            });
        }
        stored = List.copyOf(after);
    }

    private void recordStored(Connection connection, List<String> after) {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + meta() + " SET stored = ?")) {
            update.setString(1, String.join("\n", after));
            update.executeUpdate();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to record the columns stored", e);
        }
    }

    /**
     * Parses rows {@code first .. first + count - 1} on the loading threads into staging tables, one
     * per thread, each holding its blocks in order: thread {@code t} takes blocks {@code t},
     * {@code t + threads} and so on. A Java table function hands each thread's rows to DuckDB a
     * chunk at a time - measured at 1.2 s for 3 million rows of 8 columns on 8 threads, where an
     * appender took 8.6 s.
     */
    private long stage(long first, int count, List<String> wanted, CancelSignal signal) {
        int[] places = new int[wanted.size()];
        RowFunction[] computing = new RowFunction[wanted.size()];
        BitSet wantedBits = new BitSet(columns.size());
        Map<String, Computed> computedNow = computed;
        for (int i = 0; i < places.length; i++) {
            Computed c = computedNow.get(wanted.get(i).toLowerCase(Locale.ROOT));
            if (c != null) {
                places[i] = -1;
                computing[i] = c.function();
            }
            else {
                places[i] = columns.indexOf(column(wanted.get(i)));
                wantedBits.set(places[i]);
            }
        }
        // A pass storing computed columns alone needs nothing of the parser but isRow.
        boolean parse = !wantedBits.isEmpty() || java.util.Arrays.stream(computing).allMatch(java.util.Objects::isNull);
        AtomicLong staged = new AtomicLong();
        ColumnarLayout<Object[]> layout = layout(wanted);
        int blocks = (count + blockSize - 1) / blockSize;
        int used = Math.min(threads, Math.max(1, blocks));
        ExecutorService pool = Executors.newFixedThreadPool(used, task -> {
            Thread thread = new Thread(task, "quackjvm-file-load");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> parts = new ArrayList<>();
            for (int t = 0; t < used; t++) {
                int me = t;
                parts.add(pool.submit(() -> {
                    DuckDBConnection loader = loaders.get(me);
                    Iterable<Object[]> rowsOfMine = () -> new Blocks(first, count, me, used, places, computing, wantedBits,
                            parse, wanted, staged, signal);
                    LiveTable table = LiveTables.register(loader, "stage_view_" + me, () -> rowsOfMine, layout);
                    try {
                        Sql.execute(loader, "CREATE OR REPLACE TABLE " + stage() + ".main.stage_" + me
                                + " AS SELECT * FROM " + stage() + ".main.stage_view_" + me);
                    }
                    finally {
                        table.unregister();
                    }
                    return null;
                }));
            }
            for (Future<?> part : parts) {
                part.get();
            }
            stagedThreads = used;
        }
        catch (java.util.concurrent.ExecutionException e) {
            dropStage();
            Throwable cause = e.getCause();
            if (signal != null && signal.poll()) {
                throw new QueryCancelled(cause);
            }
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropStage();
            throw new QueryCancelled(e);
        }
        finally {
            pool.shutdownNow();
        }
        if (signal != null && signal.poll()) {
            dropStage();
            throw new QueryCancelled(null);
        }
        return staged.get();
    }

    private int stagedThreads;

    private String stageUnion() {
        StringBuilder union = new StringBuilder();
        for (int t = 0; t < stagedThreads; t++) {
            union.append(t == 0 ? "" : " UNION ALL ").append("SELECT * FROM ").append(stage()).append(".main.stage_")
                    .append(t);
        }
        return union.toString();
    }

    private void dropStage() {
        for (int t = 0; t < threads; t++) {
            try {
                Sql.execute(quack().sharedConnection(), "DROP TABLE IF EXISTS " + stage() + ".main.stage_" + t);
            }
            catch (RuntimeException ignored) {
                // closing
            }
        }
    }

    /**
     * One loading thread's rows: its blocks, read and parsed a block at a time as DuckDB asks. Rows
     * the parser leaves out are passed over. The signal is asked at each block, and at most every
     * millisecond within one, so a slow parser or computed column stops soon after a cancel.
     */
    private final class Blocks implements Iterator<Object[]> {
        private static final long POLL_NANOS = 1_000_000;
        private final long first;
        private final int count;
        private final int step;
        private final int[] places;
        private final RowFunction[] computing;
        private final BitSet wanted;
        private final boolean parse;
        private final List<String> names;
        private final AtomicLong staged;
        private final CancelSignal signal;
        private int block;
        private String[] raw = new String[0];
        private int inBlock;
        private int index;
        private long blockFirst;
        private boolean ready;
        private boolean stopped;
        private long polled = System.nanoTime();
        /** Rows kept since last added to {@code staged}: added a block at a time, not a row. */
        private long mine;

        Blocks(long first, int count, int me, int step, int[] places, RowFunction[] computing, BitSet wanted,
               boolean parse, List<String> names, AtomicLong staged, CancelSignal signal) {
            this.first = first;
            this.count = count;
            this.step = step;
            this.places = places;
            this.computing = computing;
            this.wanted = wanted;
            this.parse = parse;
            this.names = names;
            this.staged = staged;
            this.signal = signal;
            this.block = me - step;
        }

        @Override
        public boolean hasNext() {
            if (ready) {
                return true;
            }
            while (!stopped) {
                while (index < inBlock) {
                    if (signal != null) {
                        long now = System.nanoTime();
                        if (now - polled >= POLL_NANOS) {
                            polled = now;
                            if (signal.poll()) {
                                stop();
                                return false;
                            }
                        }
                    }
                    long rowNo = blockFirst + index;
                    boolean isRow;
                    try {
                        isRow = parser.isRow(raw[index], rowNo);
                    }
                    catch (RuntimeException e) {
                        throw new IllegalStateException("Failed to tell whether row " + rowNo + " of " + file
                                + " is a row: " + e.getMessage(), e);
                    }
                    if (isRow) {
                        ready = true;
                        return true;
                    }
                    index++;
                }
                block += step;
                long start = (long) block * blockSize;
                if (start >= count || (signal != null && signal.poll())) {
                    stop();
                    return false;
                }
                polled = System.nanoTime();
                inBlock = (int) Math.min(blockSize, count - start);
                blockFirst = first + start;
                if (raw.length < inBlock) {
                    raw = new String[inBlock];
                }
                source.read(blockFirst, inBlock, raw);
                rowsRead.addAndGet(inBlock);
                index = 0;
            }
            return false;
        }

        @Override
        public Object[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            long rowNo = blockFirst + index;
            String line = raw[index];
            index++;
            Object[] cells = null;
            if (parse) {
                cells = new Object[columns.size()];
                try {
                    parser.parse(line, rowNo, cells, wanted);
                }
                catch (RuntimeException e) {
                    throw new IllegalStateException("Failed to parse row " + rowNo + " of " + file + ": " + e.getMessage(), e);
                }
            }
            Object[] row = new Object[places.length + 1];
            row[0] = rowNo;
            for (int i = 0; i < places.length; i++) {
                if (places[i] >= 0) {
                    row[i + 1] = cells[places[i]];
                }
                else {
                    try {
                        row[i + 1] = computing[i].apply(line, rowNo);
                    }
                    catch (RuntimeException e) {
                        throw new IllegalStateException("Failed to compute " + names.get(i) + " for row " + rowNo + " of "
                                + file + ": " + e.getMessage(), e);
                    }
                }
            }
            mine++;
            return row;
        }

        private void stop() {
            stopped = true;
            staged.addAndGet(mine);
            mine = 0;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ColumnarLayout<Object[]> layout(List<String> wanted) {
        ColumnarLayout.Builder<Object[]> builder = ColumnarLayout.builder(Object[].class);
        builder.column(ROW, Long.class, row -> (Long) row[0]);
        for (int i = 0; i < wanted.size(); i++) {
            int place = i + 1;
            ColumnDef column = column(wanted.get(i));
            builder.column(column.getName(), (Class) column.getJavaType(), row -> row[place]);
        }
        // Rows only go into DuckDB here, never back out, but the layout needs a way to make one.
        return builder.rowFactory(values -> values).build();
    }

    private String columnList(List<String> wanted) {
        StringBuilder list = new StringBuilder(ROW);
        for (String column : wanted) {
            list.append(", ").append(Sql.quote(column));
        }
        return list.toString();
    }

    private ColumnDef column(String name) {
        ColumnDef column = findColumn(name);
        if (column == null) {
            throw new IllegalArgumentException("No column " + name + " in " + file);
        }
        return column;
    }

    /** One of the parser's columns or a computed one; null if there is none of that name. */
    private ColumnDef findColumn(String name) {
        for (ColumnDef column : columns) {
            if (column.getName().equalsIgnoreCase(name)) {
                return column;
            }
        }
        Computed c = computed.get(name.toLowerCase(Locale.ROOT));
        return c == null ? null : c.column();
    }

    // ---------- underneath: lazy columns ----------

    /** The columns a query reads, or a refusal saying why that cannot be told or is too many to add. */
    private Set<String> needed(String sql) {
        QueryColumns.Read read = planner.read(sql);
        if (!read.isKnown()) {
            throw new QueryRejected(sql, "Only the columns a query reads are stored for this file, and which columns"
                    + " this one reads cannot be told: " + read.reason() + ". Name the columns instead.");
        }
        Set<String> needed = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> table : read.columns().entrySet()) {
            if (!table.getKey().equalsIgnoreCase(name)) {
                continue;
            }
            for (String column : table.getValue()) {
                needed.add(column(column).getName());
            }
        }
        Set<String> missing = new LinkedHashSet<>(needed);
        missing.removeAll(stored);
        if (missing.size() > maxNewColumns) {
            throw new QueryRejected(sql, tooMany(missing.size()));
        }
        return needed;
    }

    private String tooMany(int columns) {
        return "This query reads " + columns + " columns that are not stored yet, more than the " + maxNewColumns
                + " a query may add for a file this wide. Read fewer columns at a time - name them, or narrow"
                + " COLUMNS(...).";
    }

    // ---------- underneath: the fingerprint ----------

    private boolean fingerprintMatches() {
        if (rows == 0 || fingerprint == null) {
            return true;
        }
        String now = hashOfStart(fingerprintBytes);
        return fingerprint.equals(now);
    }

    /** The fingerprint to record once a batch is stored: the file's first bytes, up to 64 KB, as they are now. */
    private String[] fingerprintAfter() {
        try {
            long length = Math.min(FINGERPRINT_BYTES, Files.size(file));
            return new String[] {hashOfStart(length), Long.toString(length)};
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /** A hash of the file's first bytes; null when it has fewer than that now. */
    private String hashOfStart(long length) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) length);
            if (bytes.length < length) {
                return null;
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (IOException e) {
            return null;
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void used() {
        if (cacheFile != null && !closed) {
            FileTables.touch(cacheFile, file);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("This table has been closed");
        }
    }

    // ---------- the builder ----------

    /** How a {@link FileTable} is made. Start one with {@link FileTables#builder}. */
    public static final class Builder {

        private final Path file;
        private final List<ColumnDef> columns;
        private final RowSource source;
        private final RowParser parser;
        private Path cacheDir;
        private long roomLimit = 5L << 30;
        private String version = "1";
        private int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        private int blockSize = 8192;
        private boolean lazy;
        private int maxNewColumns = 64;
        private long inMemoryBelow;
        private String name = "t";
        private final List<java.util.function.Function<TableFunctions, TableFunctions.Registrar>> functions = new ArrayList<>();

        Builder(Path file, List<ColumnDef> columns, RowSource source, RowParser parser) {
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("A table needs at least one column");
            }
            Set<String> names = new java.util.HashSet<>();
            for (ColumnDef column : columns) {
                if (!names.add(column.getName().toLowerCase(Locale.ROOT)) || column.getName().equalsIgnoreCase(ROW)) {
                    throw new IllegalArgumentException("Column " + column.getName() + " is there twice, or is "
                            + ROW + ", which the table uses itself");
                }
            }
            this.file = file;
            this.columns = columns;
            this.source = source;
            this.parser = parser;
        }

        /**
         * Where the cache files go. Without one, the table is kept in memory.
         *
         * @throws IllegalArgumentException outside Windows, for a folder whose path holds a
         *         backslash: DuckDB reads one in a database's path as a separator - measured on 2.0 -
         *         so it would look for the file somewhere else
         */
        public Builder cacheDir(Path folder) {
            Path absolute = folder.toAbsolutePath().normalize();
            if (absolute.toString().indexOf('\\') >= 0 && !"\\".equals(absolute.getFileSystem().getSeparator())) {
                throw new IllegalArgumentException("DuckDB reads a backslash in a database's path as a separator, so"
                        + " it cannot keep a cache in " + absolute + ". Choose a folder whose path has none.");
            }
            this.cacheDir = absolute;
            return this;
        }

        /**
         * How many bytes the cache folder may hold; past that the longest unused caches are removed,
         * never one in use. 5 GB unless set.
         */
        public Builder roomLimit(long bytes) {
            this.roomLimit = bytes;
            return this;
        }

        /** The application's own version of how it parses: a different one makes a new cache. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** How many threads parse. Up to 8 unless set. */
        public Builder threads(int threads) {
            if (threads < 1) {
                throw new IllegalArgumentException("At least one thread: " + threads);
            }
            this.threads = threads;
            return this;
        }

        /** How many rows a thread reads and parses at a time. 8,192 unless set. */
        public Builder blockSize(int rows) {
            if (rows < 1) {
                throw new IllegalArgumentException("A block holds at least one row: " + rows);
            }
            this.blockSize = rows;
            return this;
        }

        /** Store only the columns queries read, for a file too wide to store whole. */
        public Builder lazyColumns(boolean lazy) {
            this.lazy = lazy;
            return this;
        }

        /** The most columns one query may add when columns are lazy. 64 unless set. */
        public Builder maxNewColumnsPerQuery(int columns) {
            if (columns < 1) {
                throw new IllegalArgumentException("At least one column: " + columns);
            }
            this.maxNewColumns = columns;
            return this;
        }

        /** A file smaller than this is kept in memory, with no cache file. */
        public Builder inMemoryBelow(long bytes) {
            this.inMemoryBelow = bytes;
            return this;
        }

        /**
         * A Java function the table's queries can call - {@code SELECT label(time, 3), count(*) FROM t
         * GROUP BY 1}. It is registered on the cache database, or the copy or memory the table ends up
         * in, and on the one lazy columns are planned over; SQL cannot add one. A row with a null
         * argument is null, without calling it. See {@link FileTable#function} to replace one later.
         */
        public <A, R> Builder function(String name, Class<R> returns, Class<A> a,
                                       java.util.function.Function<A, R> function) {
            functions.add(f -> f.add(name, returns, a, function));
            return this;
        }

        /** As {@link #function(String, Class, Class, java.util.function.Function)}, of two arguments. */
        public <A, B, R> Builder function(String name, Class<R> returns, Class<A> a, Class<B> b,
                                          java.util.function.BiFunction<A, B, R> function) {
            functions.add(f -> f.add(name, returns, a, b, function));
            return this;
        }

        /** As {@link #function(String, Class, Class, java.util.function.Function)}, of three arguments. */
        public <A, B, C, R> Builder function(String name, Class<R> returns, Class<A> a, Class<B> b, Class<C> c,
                                             Udfs.Fn3<A, B, C, R> function) {
            functions.add(f -> f.add(name, returns, a, b, c, function));
            return this;
        }

        /** What SQL calls the table. {@code t} unless set. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Opens the table: the cache file if there is one for this file, columns and version, or a new
         * one. If another table - in this JVM or another process - has that cache open, the next copy
         * is used, up to three; past those the table is kept in memory.
         */
        public FileTable open() {
            Path absolute = file.toAbsolutePath().normalize();
            boolean small;
            try {
                small = Files.size(absolute) < inMemoryBelow;
            }
            catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + absolute, e);
            }
            if (cacheDir != null && !small) {
                try {
                    Files.createDirectories(cacheDir);
                }
                catch (IOException e) {
                    throw new UncheckedIOException("Cannot make " + cacheDir, e);
                }
                String key = key(absolute);
                for (int copy = 1; copy <= 4; copy++) {
                    Path database = cacheDir.resolve(FileTables.PREFIX + key + (copy == 1 ? "" : "." + copy) + ".duckdb");
                    if (!FileTables.OPEN.add(database)) {
                        continue;
                    }
                    Quack quack;
                    try {
                        quack = Quack.builder(database.toString()).settings(hardened()).open();
                    }
                    catch (DatabaseInUse inUse) {
                        FileTables.OPEN.remove(database);
                        continue;
                    }
                    catch (RuntimeException e) {
                        FileTables.OPEN.remove(database);
                        throw e;
                    }
                    return started(new FileTable(this, database, quack));
                }
            }
            return started(new FileTable(this, null, Quack.builder(null).settings(hardened()).open()));
        }

        private FileTable started(FileTable table) {
            try {
                table.start();
            }
            catch (RuntimeException e) {
                table.close();
                throw e;
            }
            if (cacheDir != null) {
                FileTables.trim(cacheDir, roomLimit);
            }
            return table;
        }

        private static Properties hardened() {
            Properties properties = new Properties();
            properties.setProperty("autoinstall_known_extensions", "false");
            properties.setProperty("autoload_known_extensions", "false");
            properties.setProperty("enable_external_access", "false");
            properties.setProperty("lock_configuration", "true");
            return properties;
        }

        /** The cache's name: the file, the columns, the parsing version and whether columns are lazy. */
        private String key(Path absolute) {
            StringBuilder what = new StringBuilder(absolute.toString()).append('\0').append(version).append('\0')
                    .append(lazy).append('\0');
            for (ColumnDef column : columns) {
                what.append(column.getName()).append(':').append(column.toDdl()).append('\n');
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(what.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest).substring(0, 20);
            }
            catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
