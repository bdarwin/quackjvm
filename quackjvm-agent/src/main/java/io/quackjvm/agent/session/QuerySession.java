package io.quackjvm.agent.session;

import io.quackjvm.agent.catalog.Catalog;
import io.quackjvm.agent.catalog.ColumnInfo;
import io.quackjvm.agent.catalog.QueryColumns;
import io.quackjvm.agent.catalog.Profile;
import io.quackjvm.agent.catalog.QueryExamples;
import io.quackjvm.agent.catalog.TableInfo;
import io.quackjvm.agent.catalog.internal.SqlNames;
import io.quackjvm.agent.file.FileTable;
import io.quackjvm.agent.file.internal.FileTableAccess;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.GuardedResult;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryRejected;
import io.quackjvm.agent.live.LiveTable;
import io.quackjvm.agent.live.LiveTables;
import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * One hardened, in-memory database where an application puts tables under names, adds and takes
 * them away as its user opens and closes files, and runs guarded queries that can join them.
 *
 * <pre>
 * try (QuerySession session = QuerySession.open(Hardening.options().tempFolder(temp), QueryPolicy.readOnly())) {
 *     String readings = session.register(Path.of("/data/readings-2026.csv"));    // "readings_2026"
 *     session.register(Path.of("/data/devices.csv"));                       // "devices"
 *     session.alias("t", readings);                                             // the current one, as t
 *     GuardedResult r = session.run("SELECT * FROM t JOIN devices USING (id)",
 *             CancelSignal.from(stopButton::isPressed));
 *     session.unregister("devices");
 * }
 * </pre>
 *
 * <h2>What a registration is</h2>
 *
 * <p>A view, so nothing is copied: a file is read when a query reads it, a live table is read from
 * its objects, a relation is whatever SQL it was given. {@link #register(Path)} picks DuckDB's reader
 * from the file's extension and names the table with {@link #tableName}.</p>
 *
 * <p>A {@link FileTable} - a big file the application parses, kept on disk - is the exception:
 * {@link #register(String, FileTable)} attaches its cache to the session's database, letting in
 * that cache's files and nothing else of its folder, and moves the table there until it is
 * unregistered or the session closes.</p>
 *
 * <h2>Folders</h2>
 *
 * <p>The database is opened with {@link Hardening.Options#properties()}, so it starts hardened and its
 * settings are locked: no network, no extensions, no files outside the allowed folders. DuckDB lets
 * folders in only before that, so when a file is registered from a folder SQL may not read yet - or
 * {@link #allowFolders} adds one - the session opens a new database with the wider list and makes
 * every registration and alias again, under the same names. If any of them cannot be made again, the
 * old database stays as it was and the call throws.</p>
 *
 * <h2>Threads</h2>
 *
 * <p>{@link #run} may be called from several threads at once; each query borrows a connection of its
 * own. A change to the registrations waits for the queries running then, and queries that start
 * after it see the change.</p>
 */
public final class QuerySession implements AutoCloseable {

    /** Names {@link #tableName} never gives out, so that an application can use them as aliases. */
    public static final Set<String> ALIAS_NAMES = Set.of("t", "log");

    /** The in-memory database attached for {@link ResultWindow}s, so their tables are not among the session's. */
    static final String WINDOWS = "quackjvm_windows";

    private static final Set<String> COMPRESSED = Set.of("gz", "gzip", "zst", "bz2", "xz", "lz4", "zip", "snappy");

    private final QueryPolicy defaults;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    /** In the order they were made, so that one built over another is made again after it. */
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private Hardening.Options options;
    private Quack database;
    private GuardedQuery guard;
    private boolean closed;
    /** Counts the databases the session has had, so that a window knows when its own has gone. */
    private int generation;
    private boolean windowsAttached;
    /** Names the caches of registered file tables are attached under. */
    private final java.util.concurrent.atomic.AtomicLong catalogs = new java.util.concurrent.atomic.AtomicLong();
    /** Changes with every registration, alias and reopening, so the planner knows to start again. */
    private int schemaVersion;
    private Planner planner;
    private final java.util.concurrent.atomic.AtomicLong windowNames = new java.util.concurrent.atomic.AtomicLong();

    private QuerySession(Hardening.Options options, QueryPolicy defaults) {
        this.options = options;
        this.defaults = defaults;
        this.database = openDatabase(options);
        this.guard = GuardedQuery.on(database, defaults);
    }

    /** A new session: an empty in-memory database, hardened with these options. */
    public static QuerySession open(Hardening.Options options, QueryPolicy defaults) {
        if (options == null || defaults == null) {
            throw new IllegalArgumentException("A session needs hardening options and a query policy");
        }
        return new QuerySession(options, defaults);
    }

    // ---------- registering ----------

    /**
     * A file as a table, named after it, read with the reader its extension calls for:
     * {@code read_parquet} for {@code .parquet}, {@code read_json} for {@code .json}, {@code .jsonl}
     * and {@code .ndjson}, {@code read_csv} for anything else - all of them also compressed. Lets its
     * folder in first, if SQL may not read it yet.
     *
     * @return the name it got
     */
    public String register(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return register(absolute, readerFor(absolute));
    }

    /** A file as a table, named after it, made of the given SQL - a query over the file, for instance. */
    public String register(Path file, String relationSql) {
        Path absolute = file.toAbsolutePath().normalize();
        return change(() -> {
            if (absolute.getParent() != null && !isAllowed(absolute.getParent())) {
                widen(List.of(absolute.getParent()));
            }
            String name = tableName(absolute, takenNames());
            put(new View(name, relationSql));
            return name;
        });
    }

    /** SQL as a table under this name. A table already under the name is replaced. */
    public void register(String name, String relationSql) {
        change(() -> {
            put(new View(checkName(name), relationSql));
            return null;
        });
    }

    /**
     * Java objects as a table under this name, read where they lie - see {@link LiveTables}. What the
     * supplier returns is what the next query sees.
     */
    public <O> void register(String name, Supplier<Iterable<O>> rows, ColumnarLayout<O> layout) {
        change(() -> {
            put(new Live<>(checkName(name), rows, layout));
            return null;
        });
    }

    /**
     * A {@link FileTable} as a table under this name, so that queries join it with the others - and
     * {@link #window} can read it. Its cache is attached to the session's database: SQL reads it,
     * and none of the rest of its folder. It goes on growing with {@link FileTable#update}, and a
     * query sees whole batches. Lazy columns a query reads are stored first. Taken away, or when the
     * session closes, the table is still open, and still the caller's to close; closed while it is
     * here, a query naming it says so.
     *
     * @throws IllegalArgumentException if it is closed, or registered in a session already
     */
    public void register(String name, FileTable table) {
        change(() -> {
            host(checkName(name), table);
            return null;
        });
    }

    /** As {@link #register(String, FileTable)}, named after its file as {@link #tableName} names one. */
    public String register(FileTable table) {
        return change(() -> {
            String name = tableName(table.getFile(), takenNames());
            host(name, table);
            return name;
        });
    }

    /** Takes a table away, and any alias that stood for it. Unknown names are ignored. */
    public void unregister(String name) {
        change(() -> {
            Registration gone = registrations.remove(key(registrations, name));
            if (gone == null) {
                return null;
            }
            for (String alias : new ArrayList<>(aliases.keySet())) {
                if (aliases.get(alias).equalsIgnoreCase(name)) {
                    aliases.remove(alias);
                    Sql.execute(database.sharedConnection(), "DROP VIEW IF EXISTS " + Sql.quote(alias));
                }
            }
            gone.drop(database);
            schemaVersion++;
            return null;
        });
    }

    /**
     * Another name for a registered table - {@code t} for the one on screen, say. Moving an alias to
     * another table is calling this again.
     */
    public void alias(String alias, String name) {
        change(() -> {
            if (alias == null || alias.isBlank()) {
                throw new IllegalArgumentException("An alias needs a name");
            }
            String table = key(registrations, name);
            if (!registrations.containsKey(table)) {
                throw new IllegalArgumentException("Nothing is registered as " + name + ": " + registrations.keySet());
            }
            if (registrations.containsKey(key(registrations, alias))) {
                throw new IllegalArgumentException(alias + " is a registered table, so it cannot also be an alias");
            }
            aliases.remove(key(aliases, alias));
            makeAlias(database, alias, table);
            aliases.put(alias, table);
            schemaVersion++;
            return null;
        });
    }

    /** Takes an alias away; the table it stood for stays. */
    public void unalias(String alias) {
        change(() -> {
            if (aliases.remove(key(aliases, alias)) != null) {
                Sql.execute(database.sharedConnection(), "DROP VIEW IF EXISTS " + Sql.quote(alias));
            }
            schemaVersion++;
            return null;
        });
    }

    /**
     * Lets SQL read these folders too. One that is in or below an allowed folder already changes
     * nothing; otherwise the database is opened again with the wider list, keeping every table.
     */
    public void allowFolders(Collection<Path> folders) {
        change(() -> {
            List<Path> missing = new ArrayList<>();
            for (Path folder : folders) {
                Path absolute = folder.toAbsolutePath().normalize();
                if (!isAllowed(absolute)) {
                    missing.add(absolute);
                }
            }
            if (!missing.isEmpty()) {
                widen(missing);
            }
            return null;
        });
    }

    // ---------- querying ----------

    /** Runs a statement under the session's policy. */
    public GuardedResult run(String sql) {
        return run(sql, null, null);
    }

    /** As {@link #run(String)}, stopped when the signal is cancelled. */
    public GuardedResult run(String sql, CancelSignal signal) {
        return run(sql, signal, null);
    }

    /** As {@link #run(String, CancelSignal)}, under another policy than the session's for this one statement. */
    public GuardedResult run(String sql, CancelSignal signal, QueryPolicy policy) {
        lock.readLock().lock();
        try {
            checkOpen();
            GuardedQuery runner = policy == null ? guard : GuardedQuery.on(database, policy);
            return overFileTables(sql, signal, () -> runner.run(sql, signal));
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** Why a statement would be refused, without running it; null if it would be run. */
    public String whyRejected(String sql) {
        lock.readLock().lock();
        try {
            checkOpen();
            String rejected = guard.whyRejected(sql);
            return rejected != null ? rejected : whyNotLazy(sql);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** A registered table's columns, for a hint or a prompt. */
    public TableInfo describe(String name) {
        lock.readLock().lock();
        try {
            checkOpen();
            return describe(Catalog.of(database), name);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Ready-made queries for a registered table or alias, with joins to the other tables that share a
     * column with it - see {@link QueryExamples}. Each one runs as it is.
     */
    public List<QueryExamples.Example> examples(String name) {
        return examples(name, Map.of());
    }

    /**
     * As {@link #examples(String)}, told roughly how many distinct values the columns hold, so the
     * group-by examples use a column with few: {@code examples("log", profile("log").distinctValues())}.
     */
    public List<QueryExamples.Example> examples(String name, Map<String, Long> distinctValues) {
        lock.readLock().lock();
        try {
            checkOpen();
            Catalog catalog = Catalog.of(database);
            List<TableInfo> others = new ArrayList<>();
            for (String other : registrations.keySet()) {
                if (!other.equals(name) && !other.equals(aliases.get(name)) && !isClosed(other)) {
                    others.add(describe(catalog, other));
                }
            }
            return QueryExamples.of(describe(catalog, name), others, distinctValues);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * What a registered table or alias holds - an exact row count, {@code SUMMARIZE} per column and
     * five rows; see {@link Catalog#profile(String)}. It reads the whole table.
     */
    public Profile profile(String name) {
        lock.readLock().lock();
        try {
            checkOpen();
            return Catalog.of(database).profile(name);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Runs a query once and keeps its whole result, to be read a window at a time - see
     * {@link ResultWindow}. Checked as {@link #run} checks it; the policy's time limit and the
     * signal apply, its row and byte caps do not.
     */
    public ResultWindow window(String sql, CancelSignal signal) {
        return window(sql, signal, null);
    }

    /** As {@link #window(String, CancelSignal)}, under another policy than the session's. */
    public ResultWindow window(String sql, CancelSignal signal, QueryPolicy policy) {
        lock.readLock().lock();
        try {
            checkOpen();
            attachWindows();
            String table = "w" + windowNames.incrementAndGet();
            GuardedQuery runner = policy == null ? guard : GuardedQuery.on(database, policy);
            long rows;
            try {
                rows = overFileTables(sql, signal, () -> runner.store(sql, WINDOWS, table, signal));
            }
            catch (RuntimeException e) {
                Sql.execute(database.sharedConnection(), "DROP TABLE IF EXISTS " + ResultWindow.qualified(table));
                throw e;
            }
            return ResultWindow.describe(this, generation, database, sql, table, rows);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** Runs work on the session's database, if it is still the one a window was made in. */
    <T> T inDatabase(int expected, java.util.function.Function<Quack, T> work) {
        lock.readLock().lock();
        try {
            checkOpen();
            if (expected != generation) {
                throw new IllegalStateException("The session has opened its database again since this window was"
                        + " made - to let a folder in - and the stored result went with the old one."
                        + " Make the window again with window(sql, signal).");
            }
            return work.apply(database);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    boolean isCurrent(int expected) {
        lock.readLock().lock();
        try {
            return !closed && expected == generation;
        }
        finally {
            lock.readLock().unlock();
        }
    }

    private synchronized void attachWindows() {
        if (!windowsAttached) {
            Sql.execute(database.sharedConnection(), "ATTACH IF NOT EXISTS ':memory:' AS " + WINDOWS);
            windowsAttached = true;
        }
    }

    // ---------- what is there ----------

    /** The registered tables' names, in the order they were registered. Aliases are not among them. */
    public List<String> names() {
        lock.readLock().lock();
        try {
            return List.copyOf(registrations.keySet());
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** Each alias, and the table it stands for. */
    public Map<String, String> aliases() {
        lock.readLock().lock();
        try {
            return Map.copyOf(aliases);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /** The folders SQL may read, as the database was last opened with them. */
    public List<String> allowedFolders() {
        lock.readLock().lock();
        try {
            return options.getAllowedFolders();
        }
        finally {
            lock.readLock().unlock();
        }
    }

    public QueryPolicy getPolicy() {
        return defaults;
    }

    // ---------- names ----------

    /**
     * The name a file's table gets: its name without the extension (and a compression extension
     * before that), lower case, accents dropped, and every run of other characters as one
     * {@code _}. {@code Readings 2026-Q4.tsv} is {@code readings_2026_q4}.
     *
     * <ul>
     *   <li>A name that starts with a digit, is one of DuckDB's reserved words, or has nothing left
     *       gets {@code table_} in front: {@code 2026.csv} is {@code table_2026}, {@code order.csv}
     *       {@code table_order}.</li>
     *   <li>A name already taken gets {@code _2}, {@code _3} and so on - compared without case, as
     *       DuckDB compares names.</li>
     *   <li>{@link #ALIAS_NAMES} are never given out: {@code t.csv} is {@code t_2}.</li>
     * </ul>
     */
    public static String tableName(Path file, Set<String> taken) {
        String base = file.getFileName() == null ? "" : file.getFileName().toString();
        base = withoutExtensions(base);
        String plain = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String name = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (name.isEmpty()) {
            name = "table";
        }
        else if (Character.isDigit(name.charAt(0)) || SqlNames.KEYWORDS.contains(name)) {
            name = "table_" + name;
        }
        Set<String> used = new HashSet<>(ALIAS_NAMES);
        for (String t : taken) {
            used.add(t.toLowerCase(Locale.ROOT));
        }
        if (!used.contains(name)) {
            return name;
        }
        for (int n = 2; ; n++) {
            String candidate = name + "_" + n;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String withoutExtensions(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0 && COMPRESSED.contains(base.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            base = base.substring(0, dot);
            dot = base.lastIndexOf('.');
        }
        return dot > 0 ? base.substring(0, dot) : base;
    }

    // ---------- closing ----------

    /** Closes the database; the session can no longer be used. Waits for the queries running. */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (Registration registration : registrations.values()) {
                if (registration instanceof Hosted hosted) {
                    hosted.goHome();
                }
            }
            if (planner != null) {
                planner.close();
            }
            database.close();
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isOpen() {
        lock.readLock().lock();
        try {
            return !closed;
        }
        finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "QuerySession[" + names() + (aliases().isEmpty() ? "" : ", aliases " + aliases()) + "]";
    }

    // ---------- underneath ----------

    private interface Change<T> {
        T apply();
    }

    private <T> T change(Change<T> change) {
        lock.writeLock().lock();
        try {
            checkOpen();
            return change.apply();
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("This session has been closed");
        }
    }

    private Set<String> takenNames() {
        Set<String> taken = new HashSet<>(registrations.keySet());
        taken.addAll(aliases.keySet());
        return taken;
    }

    private String checkName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A table needs a name");
        }
        if (aliases.containsKey(key(aliases, name))) {
            throw new IllegalArgumentException(name + " is an alias; take it away with unalias first");
        }
        return name;
    }

    /**
     * Makes the new one over whatever had the name - each kind replaces a view in one statement - so
     * that if it cannot be made, the old one is still there.
     */
    private void put(Registration registration) {
        put(registration, database);
    }

    private void put(Registration registration, Quack into) {
        String taken = key(registrations, registration.name());
        Registration before = registrations.get(taken);
        registration.make(into);
        schemaVersion++;
        if (before != null) {
            // A view made over a live table's leaves its objects registered: let them go. The live
            // table's own view is not dropped, since it no longer reads them. A file table goes home.
            before.replaced(into);
        }
        if (before == null || taken.equals(registration.name())) {
            registrations.put(registration.name(), registration);   // a replacement keeps its place
        }
        else {
            // The same name in other case: keep the place, under the new spelling.
            Map<String, Registration> reordered = new LinkedHashMap<>();
            for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
                if (entry.getKey().equals(taken)) {
                    reordered.put(registration.name(), registration);
                }
                else {
                    reordered.put(entry.getKey(), entry.getValue());
                }
            }
            registrations.clear();
            registrations.putAll(reordered);
        }
    }

    /** The key a name is held under - DuckDB compares names without case, so this does too. */
    private static String key(Map<String, ?> map, String name) {
        if (name == null || map.containsKey(name)) {
            return name;
        }
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return name;
    }

    private boolean isAllowed(Path folder) {
        for (String allowed : options.getAllowedFolders()) {
            if (!allowed.contains("://") && folder.startsWith(Path.of(allowed))) {
                return true;
            }
        }
        return false;
    }

    /** A new database with the folders added, every registration made again in it, then the old one closed. */
    private void widen(List<Path> folders) {
        String[] added = new String[folders.size()];
        for (int i = 0; i < added.length; i++) {
            added[i] = folders.get(i).toString();
        }
        reopen(options.allowedFolders(added), null, "with " + folders);
    }

    /**
     * A new database with these options and the files of the file tables in it, every registration
     * made again there - and one added, if given - then the old one closed. A file table's cache can
     * be attached to one database at a time, so each leaves the old one first, and goes back to it if
     * the new one cannot be made.
     */
    private void reopen(Hardening.Options wider, Hosted adding, String why) {
        List<String> files = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (registration instanceof Hosted hosted && !hosted.closed) {
                files.addAll(ACCESS().attachedFiles(hosted.table));
            }
        }
        if (adding != null) {
            files.addAll(ACCESS().attachedFiles(adding.table));
        }
        Hardening.Options next = wider.withAllowedFiles(files);
        Quack opened = openDatabase(next);
        List<Hosted> moved = new ArrayList<>();
        try {
            for (Registration registration : registrations.values()) {
                if (registration instanceof Hosted hosted && !hosted.closed) {
                    ACCESS().detach(hosted.table);
                    moved.add(hosted);
                }
            }
            for (Registration registration : registrations.values()) {
                registration.make(opened);
            }
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (!isClosed(alias.getValue())) {   // a closed file table has no view to stand for
                    makeAlias(opened, alias.getKey(), alias.getValue());
                }
            }
            if (adding != null) {
                put(adding, opened);
            }
        }
        catch (RuntimeException e) {
            for (Hosted hosted : moved) {
                ACCESS().detach(hosted.table);
            }
            opened.close();
            for (Registration registration : registrations.values()) {
                registration.release(opened);
            }
            for (Hosted hosted : moved) {
                try {
                    hosted.make(database);
                }
                catch (RuntimeException again) {
                    e.addSuppressed(again);
                }
            }
            throw new IllegalStateException("Could not open the session again " + why + "; it is as it was: "
                    + e.getMessage(), e);
        }
        Quack old = database;
        database = opened;
        generation++;
        schemaVersion++;
        windowsAttached = false;
        options = wider;
        guard = GuardedQuery.on(opened, defaults);
        old.close();
        for (Registration registration : registrations.values()) {
            registration.release(old);
        }
    }

    // ---------- file tables ----------

    private static FileTableAccess ACCESS() {
        return FileTableAccess.get();
    }

    private void host(String name, FileTable table) {
        if (table == null || !table.isOpen()) {
            throw new IllegalArgumentException("That file table is closed");
        }
        if (ACCESS().isHosted(table)) {
            throw new IllegalArgumentException(table + " is registered in a session already");
        }
        Hosted hosted = new Hosted(name, table, "quackjvm_file_" + catalogs.incrementAndGet());
        List<String> files = ACCESS().attachedFiles(table);
        if (files.isEmpty() || options.getAllowedFiles().containsAll(files)) {
            put(hosted);
        }
        else {
            // DuckDB's list of files cannot change once the database is locked: open it again.
            reopen(options, hosted, "to let in the cache of " + table.getFile().getFileName());
        }
    }

    /** A file table it holds was closed by the application: let go of its cache, and remember. */
    private void closedOutside(Hosted hosted) {
        lock.writeLock().lock();
        try {
            if (closed || hosted.closed || registrations.get(key(registrations, hosted.name)) != hosted) {
                return;
            }
            hosted.closed = true;
            ACCESS().detach(hosted.table);
            Sql.execute(database.sharedConnection(), "DROP VIEW IF EXISTS " + Sql.quote(hosted.name));
            schemaVersion++;
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isClosed(String name) {
        return registrations.get(key(registrations, name)) instanceof Hosted hosted && hosted.closed;
    }

    private List<Hosted> openHosted() {
        List<Hosted> open = new ArrayList<>();
        for (Registration registration : registrations.values()) {
            if (registration instanceof Hosted hosted && !hosted.closed) {
                open.add(hosted);
            }
        }
        return open;
    }

    private TableInfo describe(Catalog catalog, String name) {
        String table = key(registrations, aliases.getOrDefault(key(aliases, name), name));
        if (registrations.get(table) instanceof Hosted hosted) {
            if (hosted.closed) {
                throw new IllegalStateException(closedMessage(hosted.name));
            }
            if (ACCESS().isLazy(hosted.table)) {
                // Its view holds only the columns stored so far: describe all of them.
                List<ColumnInfo> columns = new ArrayList<>();
                for (ColumnDef column : ACCESS().columns(hosted.table)) {
                    columns.add(new ColumnInfo(column.getName(), column.getSqlType(), column.getJavaType(), true,
                            column.getDescription(), column.getUnit(), null));
                }
                return new TableInfo(name, null, hosted.table.rowsStored(), columns);
            }
        }
        return catalog.describe(name);
    }

    private interface Work<T> {
        T run();
    }

    /**
     * Runs a query that may read registered file tables: their lazy columns it reads stored first,
     * their functions stopped if it is cancelled, and run again if they stopped for another query's
     * cancel. A query naming a file table that was closed says so.
     */
    private <T> T overFileTables(String sql, CancelSignal signal, Work<T> work) {
        List<Hosted> hosted = openHosted();
        boolean anyClosed = registrations.values().stream().anyMatch(r -> r instanceof Hosted h && h.closed);
        if (hosted.isEmpty() && !anyClosed) {
            return work.run();
        }
        storeLazyColumns(sql, hosted, signal);
        for (int attempt = 0; ; attempt++) {
            for (Hosted h : hosted) {
                ACCESS().enter(h.table, signal);
            }
            try {
                return work.run();
            }
            catch (QueryFailed failed) {
                if (ACCESS().stoppedForAnother(failed) && attempt < 10) {
                    continue;
                }
                throw closedOrAsIs(sql, failed);
            }
            finally {
                for (Hosted h : hosted) {
                    ACCESS().leave(h.table, signal);
                }
                if (attempt < 10) {
                    for (Hosted h : hosted) {
                        ACCESS().awaitClear(h.table, signal);
                    }
                }
            }
        }
    }

    private QueryFailed closedOrAsIs(String sql, QueryFailed failed) {
        String message = String.valueOf(failed.getMessage()).toLowerCase(Locale.ROOT);
        for (Registration registration : registrations.values()) {
            if (registration instanceof Hosted hosted && hosted.closed) {
                Set<String> names = new HashSet<>();
                names.add(hosted.name.toLowerCase(Locale.ROOT));
                for (Map.Entry<String, String> alias : aliases.entrySet()) {
                    if (alias.getValue().equalsIgnoreCase(hosted.name)) {
                        names.add(alias.getKey().toLowerCase(Locale.ROOT));
                    }
                }
                for (String name : names) {
                    if (message.contains("name " + name + " does not exist") || message.contains("\"" + name + "\"")) {
                        return new QueryFailed(sql, closedMessage(hosted.name) + " DuckDB said: " + failed.getMessage(),
                                failed.getCause() instanceof SQLException e ? e : null);
                    }
                }
            }
        }
        return failed;
    }

    private static String closedMessage(String name) {
        return name + " is a file table that was closed while registered here: unregister it, or register a table"
                + " under its name again.";
    }

    /** Stores the lazy columns of registered file tables that a query reads. */
    private void storeLazyColumns(String sql, List<Hosted> hosted, CancelSignal signal) {
        Map<Hosted, Set<String>> needed = lazyColumns(sql, hosted);
        for (Map.Entry<Hosted, Set<String>> entry : needed.entrySet()) {
            ACCESS().store(entry.getKey().table, entry.getValue(), sql, signal);
        }
    }

    private String whyNotLazy(String sql) {
        try {
            Map<Hosted, Set<String>> needed = lazyColumns(sql, openHosted());
            for (Map.Entry<Hosted, Set<String>> entry : needed.entrySet()) {
                String reason = ACCESS().whyNotStore(entry.getKey().table, entry.getValue());
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }
        catch (QueryRejected refused) {
            return refused.getMessage();
        }
    }

    /** The columns a query reads of each lazy file table, planned over empty tables with all their columns. */
    private Map<Hosted, Set<String>> lazyColumns(String sql, List<Hosted> hosted) {
        List<Hosted> lazy = new ArrayList<>();
        for (Hosted h : hosted) {
            if (ACCESS().isLazy(h.table)) {
                lazy.add(h);
            }
        }
        Map<Hosted, Set<String>> needed = new LinkedHashMap<>();
        if (lazy.isEmpty()) {
            return needed;
        }
        QueryColumns.Read read = planner().read(sql);
        if (!read.isKnown()) {
            if (mentionsAny(sql, lazy)) {
                throw new QueryRejected(sql, "Only the columns a query reads are stored for "
                        + lazy.stream().map(h -> h.name).toList() + ", and which columns this one reads cannot be told: "
                        + read.reason() + ". Name the columns instead.");
            }
            return needed;
        }
        for (Hosted h : lazy) {
            Set<String> names = new HashSet<>();
            names.add(h.name.toLowerCase(Locale.ROOT));
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (alias.getValue().equalsIgnoreCase(h.name)) {
                    names.add(alias.getKey().toLowerCase(Locale.ROOT));
                }
            }
            Set<String> columns = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> table : read.columns().entrySet()) {
                if (names.contains(table.getKey().toLowerCase(Locale.ROOT))) {
                    columns.addAll(table.getValue());
                }
            }
            if (!columns.isEmpty()) {
                needed.put(h, columns);
            }
        }
        return needed;
    }

    private boolean mentionsAny(String sql, List<Hosted> lazy) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (Hosted h : lazy) {
            Set<String> names = new HashSet<>();
            names.add(h.name.toLowerCase(Locale.ROOT));
            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                if (alias.getValue().equalsIgnoreCase(h.name)) {
                    names.add(alias.getKey().toLowerCase(Locale.ROOT));
                }
            }
            for (String name : names) {
                if (java.util.regex.Pattern.compile("(?<![a-z0-9_])" + java.util.regex.Pattern.quote(name)
                        + "(?![a-z0-9_])").matcher(lower).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The planner of lazy columns, made again when the tables, the aliases, the functions or a file
     * table's columns - a computed one added - changed.
     */
    private synchronized QueryColumns planner() {
        int functions = 0;
        List<Integer> columnChanges = new ArrayList<>();
        for (Hosted h : openHosted()) {
            functions += ACCESS().functions(h.table).size();
            columnChanges.add(ACCESS().columnChanges(h.table));
        }
        if (planner == null || planner.version != schemaVersion || planner.functions != functions
                || !planner.columnChanges.equals(columnChanges)) {
            if (planner != null) {
                planner.close();
            }
            planner = Planner.of(this, schemaVersion, functions);
            planner.columnChanges = columnChanges;
        }
        return planner.columns;
    }

    /** Empty tables like the session's - a lazy file table's with all its columns - to plan queries over. */
    private static final class Planner {
        final int version;
        final int functions;
        final Connection source;
        final QueryColumns columns;
        /** Each open file table's {@code columnChanges}, when this was made. */
        List<Integer> columnChanges = List.of();

        private Planner(int version, int functions, Connection source, QueryColumns columns) {
            this.version = version;
            this.functions = functions;
            this.source = source;
            this.columns = columns;
        }

        static Planner of(QuerySession session, int version, int functions) {
            Connection source;
            try {
                source = DriverManager.getConnection("jdbc:duckdb:");
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to open a database to plan queries in", e);
            }
            Catalog catalog = Catalog.of(session.database);
            for (Map.Entry<String, Registration> entry : session.registrations.entrySet()) {
                if (entry.getValue() instanceof Hosted hosted && hosted.closed) {
                    continue;
                }
                List<String> columns = new ArrayList<>();
                try {
                    if (entry.getValue() instanceof Hosted hosted && ACCESS().isLazy(hosted.table)) {
                        for (ColumnDef column : ACCESS().columns(hosted.table)) {
                            columns.add(column.toDdl());
                        }
                    }
                    else {
                        for (ColumnInfo column : catalog.describe(entry.getKey()).columns()) {
                            columns.add(Sql.quote(column.name()) + " " + column.sqlType());
                        }
                    }
                    Sql.execute(source, "CREATE TABLE " + Sql.quote(entry.getKey()) + " (" + String.join(", ", columns) + ")");
                }
                catch (RuntimeException e) {
                    // A table that cannot be described cannot be planned over either: a query
                    // reading it fails when it runs, with DuckDB's own reason.
                }
            }
            for (Map.Entry<String, String> alias : session.aliases.entrySet()) {
                try {
                    Sql.execute(source, "CREATE VIEW " + Sql.quote(alias.getKey()) + " AS SELECT * FROM "
                            + Sql.quote(alias.getValue()));
                }
                catch (RuntimeException e) {
                    // as above
                }
            }
            QueryColumns columns = QueryColumns.of(source);
            for (Hosted hosted : session.openHosted()) {
                for (java.util.function.Consumer<Connection> function : ACCESS().functions(hosted.table)) {
                    columns.register(function);
                }
            }
            return new Planner(version, functions, source, columns);
        }

        void close() {
            columns.close();
            Sql.closeQuietly(source);
        }
    }


    private static Quack openDatabase(Hardening.Options options) {
        Quack.Builder builder = Quack.builder(null).settings(options.properties());
        options.getObjectStores().forEach(builder::objectStore);
        return builder.open();
    }

    private static void makeAlias(Quack database, String alias, String name) {
        Sql.execute(database.sharedConnection(), "CREATE OR REPLACE VIEW " + Sql.quote(alias) + " AS SELECT * FROM "
                + Sql.quote(name));
    }

    static String readerFor(Path file) {
        String name = withoutCompression(file.getFileName().toString().toLowerCase(Locale.ROOT));
        String literal = "'" + file.toString().replace("'", "''") + "'";
        if (name.endsWith(".parquet")) {
            return "SELECT * FROM read_parquet(" + literal + ")";
        }
        if (name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
            return "SELECT * FROM read_json(" + literal + ")";
        }
        return "SELECT * FROM read_csv(" + literal + ")";
    }

    private static String withoutCompression(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && COMPRESSED.contains(fileName.substring(dot + 1)) ? fileName.substring(0, dot) : fileName;
    }

    /** Something under a name that can be made in a database, and taken away from it. */
    private interface Registration {
        String name();

        void make(Quack database);

        void drop(Quack database);

        /** Forgets what was made in a database, without dropping anything there. */
        default void release(Quack database) {
        }

        /** Another registration took its name in this database. */
        default void replaced(Quack database) {
            release(database);
        }
    }

    /** A file table, its cache attached to the session's database. */
    private final class Hosted implements Registration {
        private final String name;
        private final FileTable table;
        private final String catalog;
        private volatile boolean closed;

        Hosted(String name, FileTable table, String catalog) {
            this.name = name;
            this.table = table;
            this.catalog = catalog;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void make(Quack database) {
            if (closed) {
                return;
            }
            ACCESS().moveTo(table, database, catalog, () -> closedOutside(this));
            try {
                Sql.execute(database.sharedConnection(), "CREATE OR REPLACE VIEW " + Sql.quote(name) + " AS SELECT * FROM "
                        + Sql.quote(catalog) + ".main." + Sql.quote(table.getName()));
            }
            catch (RuntimeException e) {
                ACCESS().moveHome(table);
                throw e;
            }
        }

        @Override
        public void drop(Quack database) {
            Sql.execute(database.sharedConnection(), "DROP VIEW IF EXISTS " + Sql.quote(name));
            goHome();
        }

        @Override
        public void replaced(Quack database) {
            goHome();
        }

        void goHome() {
            if (!closed) {
                try {
                    ACCESS().moveHome(table);
                }
                catch (RuntimeException e) {
                    // Its cache could not be opened again on its own: the table's own methods say so
                    // when used. The session goes on without it.
                }
            }
        }
    }

    private record View(String name, String sql) implements Registration {
        @Override
        public void make(Quack database) {
            try {
                Sql.execute(database.sharedConnection(), "CREATE OR REPLACE VIEW " + Sql.quote(name) + " AS " + sql);
            }
            catch (IllegalStateException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new IllegalArgumentException("Could not register " + name + ": " + cause.getMessage(), e);
            }
        }

        @Override
        public void drop(Quack database) {
            Sql.execute(database.sharedConnection(), "DROP VIEW IF EXISTS " + Sql.quote(name));
        }
    }

    private static final class Live<O> implements Registration {
        private final String name;
        private final Supplier<Iterable<O>> rows;
        private final ColumnarLayout<O> layout;
        /** One per database it was made in: the session's, and while it moves, the next one. */
        private final Map<Quack, LiveTable> tables = new java.util.IdentityHashMap<>();

        Live(String name, Supplier<Iterable<O>> rows, ColumnarLayout<O> layout) {
            this.name = name;
            this.rows = rows;
            this.layout = layout;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void make(Quack database) {
            tables.put(database, database.register(connection -> LiveTables.register(connection, name, rows, layout)));
        }

        @Override
        public void drop(Quack database) {
            LiveTable table = tables.remove(database);
            if (table != null) {
                table.unregister();
            }
        }

        @Override
        public void release(Quack database) {
            // Unregistering drops the view only while it still reads this table's objects.
            drop(database);
        }
    }
}
