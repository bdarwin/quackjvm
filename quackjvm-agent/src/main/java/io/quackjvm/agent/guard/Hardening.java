package io.quackjvm.agent.guard;

import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.Sql;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Taking away a database's access to files, extensions and the network, so that a statement which
 * somehow got past the checks still cannot reach anything outside the database.
 *
 * <h2>Read this before turning it on</h2>
 *
 * <p>In DuckDB this is a property of the <b>database</b>, not of a connection, and it cannot be
 * undone while the database is running: {@code "Cannot enable external access while database is
 * running"}. Every connection to that database loses file access at once, including the ones your
 * own code holds.</p>
 *
 * <p>Measured on DuckDB 1.5.5 and 2.0.0, with external access off: {@code read_csv}, {@code getenv}
 * (which 2.0 removes outright),
 * {@code COPY ... TO} and {@code INSTALL} are all refused - and so are quackjvm's own
 * {@code MeasureTable.archive}, {@code MeasureHistory.export} and every Parquet read. If a database
 * archives, exports or attaches anything, hardening it takes those away. {@code ATTACH ':memory:'}
 * is still allowed, which is why the statement check rejects ATTACH itself.</p>
 *
 * <h2>The sandbox, which costs nothing</h2>
 *
 * <p>{@link #sandbox()} opens a second, empty database with external access off and the
 * configuration locked, so it cannot be turned back on. A hardened database can still see live Java
 * objects through a registered table function, and can hold tables copied into it - both measured -
 * so an agent can be given a database that has no way out, while the database your application
 * writes to keeps everything it had.</p>
 *
 * <pre>
 * try (Connection sandbox = Hardening.sandbox()) {
 *     LiveTables.register(sandbox, "car", cars, layout);        // live objects, no file access
 *     GuardedQuery.on(sandbox, QueryPolicy.readOnly()).run(sql);
 * }
 * </pre>
 *
 * <h2>Locked down, but with folders of its own</h2>
 *
 * <p>All or nothing is too little for SQL people write over their own files. {@link #options()}
 * keeps everything else locked - no network, no other folders, no extensions, no {@code SET} - and
 * lets in the folders named:</p>
 *
 * <pre>
 * Quack db = Quack.builder(path)
 *         .settings(Hardening.options()
 *                 .allowedFolders(logFolder, home)
 *                 .tempFolder(temp)
 *                 .memoryLimit("4GB").threads(7)
 *                 .properties())
 *         .open();
 * // or, for a database already open:  Hardening.apply(db, options)
 * </pre>
 */
public enum Hardening {

    /**
     * Check statements, cap rows, bytes and time; change nothing about the database. The default,
     * and the only setting that leaves a working database working.
     */
    NONE,

    /**
     * Take external access away from the whole database the guarded connection belongs to, the
     * first time a query is guarded. Irreversible until the database is closed, and felt by every
     * connection to it - including yours.
     */
    DATABASE;

    /** Whether this database has already had external access taken away. */
    public static boolean isHardened(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM duckdb_settings()"
                + " WHERE name = 'enable_external_access' AND value = 'true'", java.util.List.of()) == 0;
    }

    /**
     * Takes external access away from the database this connection belongs to. Safe to call again;
     * DuckDB refuses to put it back.
     */
    public static void apply(Connection connection) {
        if (isHardened(connection)) {
            return;
        }
        Sql.execute(connection, "SET enable_external_access = false");
    }

    /**
     * Takes external access away from a {@link Quack}'s database. Do it straight after opening - and,
     * with a history, after it is attached, which {@code open()} has already done.
     */
    public static void apply(Quack quack) {
        apply(quack.sharedConnection());
    }

    /**
     * A {@link Quack} on a new, empty, in-memory database that starts hardened and cannot be
     * reconfigured, for queries nobody has vetted. It holds only what is put into it.
     */
    public static Quack openSandbox() {
        return Quack.builder(null).settings(sandboxProperties()).open();
    }

    /** Hardening that lets in folders of its own, with a temp folder and limits: see {@link Options}. */
    public static Options options() {
        return new Options(List.of(), List.of(), null, null, 0);
    }

    /**
     * Applies options to a database that is already open, in the order DuckDB needs: the folders
     * before external access goes, everything before the configuration is locked. A database
     * whose configuration is locked already is left as it is - which is what a second
     * {@code Quack} on the same file in the same JVM finds, since the two share one database.
     */
    public static void apply(Connection connection, Options options) {
        if (isLocked(connection)) {
            return;
        }
        if (isHardened(connection) && !options.getAllowedFolders().isEmpty()) {
            throw new IllegalStateException("This database has had external access taken away already, and DuckDB"
                    + " lets folders in only before that: open it with Hardening.options()...properties() instead.");
        }
        for (String statement : options.statements()) {
            Sql.execute(connection, statement);
        }
    }

    /** As {@link #apply(Connection, Options)}, on a {@link Quack}'s database. */
    public static void apply(Quack quack, Options options) {
        apply(quack.sharedConnection(), options);
    }

    /** Whether this database's configuration can no longer be changed. */
    public static boolean isLocked(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM duckdb_settings()"
                + " WHERE name = 'lock_configuration' AND value = 'true'", java.util.List.of()) > 0;
    }

    /**
     * A locked-down database that can still use the folders it is given.
     *
     * <p>Measured on duckdb_jdbc 1.5.5.1 and 2.0.0.0, with a folder allowed: files in it and in the
     * folders below it are read; a file in any other folder, a neighbour whose name starts the same
     * ({@code /data2} when {@code /data} is allowed), a way out through {@code ..}, a URL,
     * {@code INSTALL} and {@code SET} are all refused.</p>
     *
     * <p><b>An allowed folder can be written as well as read.</b> DuckDB has no read-only form of
     * it: {@code COPY ... TO} and {@code ATTACH} of a file in an allowed folder both work - measured.
     * {@link GuardedQuery} refuses both statements, so SQL that goes through it only reads; SQL run
     * on the database directly can write there.</p>
     *
     * <p><b>SQL can read and write in the temp folder, as if it were an allowed one.</b> DuckDB
     * spills into it without it being listed - a sort of 8 million rows under a 200 MB limit wrote
     * 375 MB of temp files, measured - but it also lets {@code read_csv}, {@code glob},
     * {@code COPY ... TO} and {@code ATTACH} reach it and the folders below it, measured on 2.0.0.0.
     * So it must be a folder of its own: {@link #properties()} and {@link Hardening#apply} refuse
     * one that holds anything but DuckDB's own {@code duckdb_temp} files, which keeps it from being
     * pointed at the system's temp folder or at a folder of data. {@link GuardedQuery} refuses
     * {@code COPY} and {@code ATTACH}, so through it SQL can only read there.</p>
     */
    public static final class Options {

        private final List<String> allowedFolders;
        private final List<String> allowedFiles;
        private final String tempFolder;
        private final String memoryLimit;
        private final int threads;

        private Options(List<String> allowedFolders, List<String> allowedFiles, String tempFolder, String memoryLimit,
                        int threads) {
            this.allowedFolders = List.copyOf(allowedFolders);
            this.allowedFiles = List.copyOf(allowedFiles);
            this.tempFolder = tempFolder;
            this.memoryLimit = memoryLimit;
            this.threads = threads;
        }

        /** Folders whose files - and those of the folders below them - SQL may use. */
        public Options allowedFolders(String... folders) {
            List<String> all = new ArrayList<>(allowedFolders);
            for (String folder : folders) {
                all.add(Path.of(folder).toAbsolutePath().normalize().toString());
            }
            return new Options(all, allowedFiles, tempFolder, memoryLimit, threads);
        }

        /**
         * Files SQL may use, each on its own - DuckDB's {@code allowed_paths}. A {@link
         * io.quackjvm.agent.session.QuerySession} lets a registered file table's cache in this way: its
         * database, its write-ahead log and its checkpoint file, and nothing else of its folder.
         */
        public Options allowedFiles(String... files) {
            List<String> all = new ArrayList<>(allowedFiles);
            for (String file : files) {
                all.add(Path.of(file).toAbsolutePath().normalize().toString());
            }
            return new Options(allowedFolders, all, tempFolder, memoryLimit, threads);
        }

        /** These options with these files in place of the ones given before. */
        public Options withAllowedFiles(List<String> files) {
            return new Options(allowedFolders, List.of(), tempFolder, memoryLimit, threads)
                    .allowedFiles(files.toArray(new String[0]));
        }

        /**
         * Where DuckDB spills what does not fit in memory. A folder of its own: SQL can read and
         * write in it, so one holding anything but DuckDB's files is refused when used.
         */
        public Options tempFolder(String folder) {
            return new Options(allowedFolders, allowedFiles, Path.of(folder).toAbsolutePath().normalize().toString(),
                    memoryLimit, threads);
        }

        /** DuckDB's {@code memory_limit}, such as {@code "4GB"}. */
        public Options memoryLimit(String limit) {
            return new Options(allowedFolders, allowedFiles, tempFolder, limit, threads);
        }

        /** How many threads DuckDB may use. */
        public Options threads(int count) {
            if (count < 1) {
                throw new IllegalArgumentException("At least one thread: " + count);
            }
            return new Options(allowedFolders, allowedFiles, tempFolder, memoryLimit, count);
        }

        public List<String> getAllowedFolders() {
            return allowedFolders;
        }

        public List<String> getAllowedFiles() {
            return allowedFiles;
        }

        /** The properties to open a database with, so that it starts this way and stays so. */
        public Properties properties() {
            checkTempFolder();
            Properties properties = new Properties();
            if (memoryLimit != null) {
                properties.setProperty("memory_limit", memoryLimit);
            }
            if (threads > 0) {
                properties.setProperty("threads", Integer.toString(threads));
            }
            if (tempFolder != null) {
                properties.setProperty("temp_directory", tempFolder);
            }
            properties.setProperty("autoinstall_known_extensions", "false");
            properties.setProperty("autoload_known_extensions", "false");
            if (!allowedFolders.isEmpty()) {
                properties.setProperty("allowed_directories", castList(allowedFolders));
            }
            if (!allowedFiles.isEmpty()) {
                properties.setProperty("allowed_paths", castList(allowedFiles));
            }
            properties.setProperty("enable_external_access", "false");
            properties.setProperty("lock_configuration", "true");
            return properties;
        }

        /** The same as statements, in the order a running database needs them. */
        List<String> statements() {
            checkTempFolder();
            List<String> statements = new ArrayList<>();
            if (memoryLimit != null) {
                statements.add("SET memory_limit = " + literal(memoryLimit));
            }
            if (threads > 0) {
                statements.add("SET threads = " + threads);
            }
            if (tempFolder != null) {
                statements.add("SET temp_directory = " + literal(tempFolder));
            }
            statements.add("SET autoinstall_known_extensions = false");
            statements.add("SET autoload_known_extensions = false");
            // Before external access goes: afterwards the lists can no longer be changed.
            if (!allowedFolders.isEmpty()) {
                statements.add("SET allowed_directories = " + sqlList(allowedFolders));
            }
            if (!allowedFiles.isEmpty()) {
                statements.add("SET allowed_paths = " + sqlList(allowedFiles));
            }
            statements.add("SET enable_external_access = false");
            // Last: once locked, nothing above could be set.
            statements.add("SET lock_configuration = true");
            return statements;
        }

        /** SQL can reach the temp folder, so it must hold nothing but what DuckDB puts there. */
        private void checkTempFolder() {
            if (tempFolder == null) {
                return;
            }
            Path folder = Path.of(tempFolder);
            if (!Files.exists(folder)) {
                return;
            }
            if (!Files.isDirectory(folder)) {
                throw new IllegalStateException("The temp folder is a file: " + folder);
            }
            List<String> others = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (!name.startsWith("duckdb_temp")) {
                        others.add(name);
                    }
                }
            }
            catch (IOException e) {
                throw new IllegalStateException("Cannot list the temp folder " + folder, e);
            }
            if (!others.isEmpty()) {
                java.util.Collections.sort(others);
                throw new IllegalStateException("The temp folder " + folder + " holds files that are not DuckDB's: "
                        + (others.size() > 5 ? others.subList(0, 5) + " and " + (others.size() - 5) + " more" : others)
                        + ". SQL can read and write in the temp folder as if it were an allowed one, so give it a"
                        + " folder of its own.");
            }
        }

        private static String sqlList(List<String> paths) {
            StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < paths.size(); i++) {
                list.append(i == 0 ? "" : ", ").append(literal(paths.get(i)));
            }
            return list.append(']').toString();
        }

        /**
         * A list for {@link #properties()}. DuckDB turns a property's string into a list with its
         * cast, which reads a backslash as an escape and does not know SQL's doubled quote, so each
         * backslash and quote is escaped with a backslash instead (issue #12).
         */
        private static String castList(List<String> paths) {
            StringBuilder list = new StringBuilder("[");
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i).replace("\\", "\\\\").replace("'", "\\'");
                list.append(i == 0 ? "" : ", ").append('\'').append(path).append('\'');
            }
            return list.append(']').toString();
        }

        private static String literal(String value) {
            return "'" + value.replace("'", "''") + "'";
        }

        @Override
        public String toString() {
            return "Hardening.Options[folders=" + allowedFolders + (allowedFiles.isEmpty() ? "" : ", files=" + allowedFiles)
                    + ", temp=" + tempFolder + ", memory=" + memoryLimit + ", threads=" + threads + "]";
        }
    }

    /** The properties to open a database with so that it starts hardened and stays that way. */
    public static Properties sandboxProperties() {
        Properties properties = new Properties();
        properties.setProperty("enable_external_access", "false");
        // Without this, external access is off but other settings could still be changed.
        properties.setProperty("lock_configuration", "true");
        return properties;
    }

    /**
     * A new, empty, in-memory database with no way to reach a file, an extension or the network.
     * Register table functions on it to give it live data; copy tables into it for snapshots.
     */
    public static Connection sandbox() {
        try {
            return java.sql.DriverManager.getConnection("jdbc:duckdb:", sandboxProperties());
        }
        catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to open a hardened sandbox database", e);
        }
    }
}
