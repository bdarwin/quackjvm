package io.quackjvm.agent.file;

import io.quackjvm.agent.catalog.QueryColumns;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.QueryCancelled;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.live.LiveTable;
import io.quackjvm.agent.live.LiveTables;
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
 * <h2>Hardening</h2>
 *
 * <p>The cache database is opened with no file, network or extension access and its settings
 * locked. Measured on DuckDB 2.0: SQL can then read none of the cache folder's other files - the
 * other caches - only this database's own file, its write-ahead log and its temp folder, which hold
 * nothing the table does not.</p>
 */
public final class FileTable implements AutoCloseable {

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
    private final Quack quack;
    private final List<DuckDBConnection> loaders = new ArrayList<>();
    private final ReentrantLock writer = new ReentrantLock();
    private final AtomicLong rowsRead = new AtomicLong();
    private final QueryColumns planner;
    private final Connection plannerSource;
    private Object identity;
    private volatile long rows;
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
        this.quack = quack;
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
    }

    // ---------- updating ----------

    /** What an update did. */
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
        writer.lock();
        try {
            checkOpen();
            long target = source.rowCount();
            Object now = source.identity();
            boolean replaced = (identity != null && !identity.equals(now)) || target < rows || !fingerprintMatches();
            identity = now;
            if (replaced) {
                empty();
            }
            long before = rows;
            long batch = (long) threads * BLOCKS_PER_THREAD * blockSize;
            while (rows < target) {
                if (signal != null && signal.poll()) {
                    throw new QueryCancelled(null);
                }
                long count = Math.min(batch, target - rows);
                appendBatch(rows, (int) count, signal);
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
            return new Updated(rows - before, replaced, rows);
        }
        finally {
            writer.unlock();
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
        return GuardedQuery.on(quack, policy).run(sql, signal);
    }

    /**
     * Why a query would be refused - by the guard, or because its columns cannot be told or are too
     * many to add - without running it; null if it would run.
     */
    public String whyNot(String sql) {
        checkOpen();
        String rejected = GuardedQuery.on(quack, QueryPolicy.readOnly()).whyRejected(sql);
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

    /** How many rows are stored. */
    public long rowsLoaded() {
        return rows;
    }

    /** The columns stored: all of them, or with lazy columns those queries have asked for. */
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
        writer.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (DuckDBConnection loader : loaders) {
                Sql.closeQuietly(loader);
            }
            if (planner != null) {
                planner.close();
                Sql.closeQuietly(plannerSource);
            }
            quack.close();
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
                + (lazy ? ", " + stored.size() + " of " + columns.size() + " columns" : "")
                + (cacheFile == null ? ", in memory" : "") + "]";
    }

    // ---------- underneath: opening ----------

    private void start() {
        quack.inTransaction(tx -> {
            Connection connection = tx.connection();
            Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + META + " (rows BIGINT, fingerprint VARCHAR,"
                    + " fingerprint_bytes BIGINT, stored VARCHAR, source VARCHAR)");
            boolean fresh = Sql.queryLong(connection, "SELECT count(*) FROM " + META, List.of()) == 0;
            if (fresh) {
                StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE " + DATA + " (" + ROW + " BIGINT");
                List<String> initial = new ArrayList<>();
                if (!lazy) {
                    for (ColumnDef column : columns) {
                        ddl.append(", ").append(column.toDdl());
                        initial.add(column.getName());
                    }
                }
                Sql.execute(connection, ddl.append(')').toString());
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + META
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
        try (Connection connection = quack.connection();
             PreparedStatement select = connection.prepareStatement("SELECT rows, fingerprint, fingerprint_bytes, stored"
                     + " FROM " + META);
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
        Sql.execute(quack.sharedConnection(), "ATTACH IF NOT EXISTS ':memory:' AS " + STAGE);
        makeView(quack.sharedConnection(), stored);
        try {
            for (int i = 0; i < threads; i++) {
                DuckDBConnection loader = quack.register(root -> {
                    try {
                        return (DuckDBConnection) root.duplicate();
                    }
                    catch (SQLException e) {
                        throw new IllegalStateException("Failed to open a loading connection", e);
                    }
                });
                Sql.execute(loader, "USE " + STAGE);
                loaders.add(loader);
            }
        }
        catch (RuntimeException e) {
            close();
            throw e;
        }
        used();
    }

    private void makeView(Connection connection, List<String> columnsStored) {
        StringBuilder view = new StringBuilder("CREATE OR REPLACE VIEW ").append(Sql.quote(name)).append(" AS SELECT ");
        if (columnsStored.isEmpty()) {
            // Nothing stored yet: the row number alone, so that count(*) can be answered.
            view.append(ROW);
        }
        else {
            for (int i = 0; i < columnsStored.size(); i++) {
                view.append(i == 0 ? "" : ", ").append(Sql.quote(columnsStored.get(i)));
            }
        }
        Sql.execute(connection, view.append(" FROM ").append(DATA).toString());
    }

    // ---------- underneath: loading ----------

    /** Stores rows {@code first .. first + count - 1}, of the columns stored, in one transaction. */
    private void appendBatch(long first, int count, CancelSignal signal) {
        List<String> wanted = stored;
        stage(first, count, wanted, signal);
        String list = columnList(wanted);
        long end = first + count;
        String[] print = fingerprintAfter();
        try {
            quack.inTransaction(tx -> {
                Connection connection = tx.connection();
                Sql.execute(connection, "INSERT INTO " + DATA + " (" + list + ") SELECT " + list + " FROM ("
                        + stageUnion() + ") ORDER BY " + ROW);
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + META
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
        fingerprint = print[0];
        fingerprintBytes = Long.parseLong(print[1]);
    }

    /** Empties the table, for a file that was replaced. Lazy columns already asked for stay asked for. */
    private void empty() {
        quack.inTransaction(tx -> {
            Sql.execute(tx.connection(), "DELETE FROM " + DATA);
            Sql.execute(tx.connection(), "UPDATE " + META + " SET rows = 0, fingerprint = NULL, fingerprint_bytes = 0");
        });
        rows = 0;
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
            long batch = (long) threads * BLOCKS_PER_THREAD * blockSize;
            Sql.execute(quack.sharedConnection(), "CREATE OR REPLACE TABLE " + STAGE + ".main.adding AS SELECT "
                    + ROW + " FROM " + DATA + " LIMIT 0");
            for (String column : adding) {
                Sql.execute(quack.sharedConnection(), "ALTER TABLE " + STAGE + ".main.adding ADD COLUMN "
                        + column(column).toDdl());
            }
            try {
                for (long first = 0; first < count; first += batch) {
                    stage(first, (int) Math.min(batch, count - first), adding, signal);
                    Sql.execute(quack.sharedConnection(), "INSERT INTO " + STAGE + ".main.adding SELECT * FROM ("
                            + stageUnion() + ")");
                    dropStage();
                }
                StringBuilder select = new StringBuilder("SELECT d.*");
                for (String column : adding) {
                    select.append(", a.").append(Sql.quote(column));
                }
                quack.inTransaction(tx -> {
                    Connection connection = tx.connection();
                    Sql.execute(connection, "CREATE TABLE " + DATA + "_next AS " + select + " FROM " + DATA
                            + " d JOIN " + STAGE + ".main.adding a ON d." + ROW + " = a." + ROW + " ORDER BY d." + ROW);
                    Sql.execute(connection, "DROP VIEW IF EXISTS " + Sql.quote(name));
                    Sql.execute(connection, "DROP TABLE " + DATA);
                    Sql.execute(connection, "ALTER TABLE " + DATA + "_next RENAME TO " + DATA);
                    makeView(connection, after);
                    recordStored(connection, after);
                });
            }
            finally {
                dropStage();
                Sql.execute(quack.sharedConnection(), "DROP TABLE IF EXISTS " + STAGE + ".main.adding");
            }
        }
        else {
            quack.inTransaction(tx -> {
                for (String column : adding) {
                    Sql.execute(tx.connection(), "ALTER TABLE " + DATA + " ADD COLUMN " + column(column).toDdl());
                }
                makeView(tx.connection(), after);
                recordStored(tx.connection(), after);
            });
        }
        stored = List.copyOf(after);
    }

    private static void recordStored(Connection connection, List<String> after) {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + META + " SET stored = ?")) {
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
    private void stage(long first, int count, List<String> wanted, CancelSignal signal) {
        int[] places = new int[wanted.size()];
        BitSet wantedBits = new BitSet(columns.size());
        for (int i = 0; i < places.length; i++) {
            places[i] = columns.indexOf(column(wanted.get(i)));
            wantedBits.set(places[i]);
        }
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
                    Iterable<Object[]> rowsOfMine = () -> new Blocks(first, count, me, used, places, wantedBits, signal);
                    LiveTable table = LiveTables.register(loader, "stage_view_" + me, () -> rowsOfMine, layout);
                    try {
                        Sql.execute(loader, "CREATE OR REPLACE TABLE " + STAGE + ".main.stage_" + me
                                + " AS SELECT * FROM " + STAGE + ".main.stage_view_" + me);
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
    }

    private int stagedThreads;

    private String stageUnion() {
        StringBuilder union = new StringBuilder();
        for (int t = 0; t < stagedThreads; t++) {
            union.append(t == 0 ? "" : " UNION ALL ").append("SELECT * FROM ").append(STAGE).append(".main.stage_")
                    .append(t);
        }
        return union.toString();
    }

    private void dropStage() {
        for (int t = 0; t < threads; t++) {
            try {
                Sql.execute(quack.sharedConnection(), "DROP TABLE IF EXISTS " + STAGE + ".main.stage_" + t);
            }
            catch (RuntimeException ignored) {
                // closing
            }
        }
    }

    /** One loading thread's rows: its blocks, read and parsed a block at a time as DuckDB asks. */
    private final class Blocks implements Iterator<Object[]> {
        private final long first;
        private final int count;
        private final int step;
        private final int[] places;
        private final BitSet wanted;
        private final CancelSignal signal;
        private int block;
        private String[] raw = new String[0];
        private int inBlock;
        private int index;
        private long blockFirst;

        Blocks(long first, int count, int me, int step, int[] places, BitSet wanted, CancelSignal signal) {
            this.first = first;
            this.count = count;
            this.step = step;
            this.places = places;
            this.wanted = wanted;
            this.signal = signal;
            this.block = me - step;
        }

        @Override
        public boolean hasNext() {
            if (index < inBlock) {
                return true;
            }
            block += step;
            long start = (long) block * blockSize;
            if (start >= count || (signal != null && signal.poll())) {
                return false;
            }
            inBlock = (int) Math.min(blockSize, count - start);
            blockFirst = first + start;
            if (raw.length < inBlock) {
                raw = new String[inBlock];
            }
            source.read(blockFirst, inBlock, raw);
            rowsRead.addAndGet(inBlock);
            index = 0;
            return true;
        }

        @Override
        public Object[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            long rowNo = blockFirst + index;
            Object[] cells = new Object[columns.size()];
            try {
                parser.parse(raw[index], rowNo, cells, wanted);
            }
            catch (RuntimeException e) {
                throw new IllegalStateException("Failed to parse row " + rowNo + " of " + file + ": " + e.getMessage(), e);
            }
            index++;
            Object[] row = new Object[places.length + 1];
            row[0] = rowNo;
            for (int i = 0; i < places.length; i++) {
                row[i + 1] = cells[places[i]];
            }
            return row;
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
        for (ColumnDef column : columns) {
            if (column.getName().equalsIgnoreCase(name)) {
                return column;
            }
        }
        throw new IllegalArgumentException("No column " + name + " in " + file);
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
            throw new QueryRejected(sql, "This query reads " + missing.size() + " columns that are not stored yet, more"
                    + " than the " + maxNewColumns + " a query may add for a file this wide. Read fewer columns at a"
                    + " time - name them, or narrow COLUMNS(...).");
        }
        return needed;
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
