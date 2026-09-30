package io.quackjvm.core.guard;

import io.quackjvm.core.duckdb.Sql;

import java.sql.Connection;
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
 * <p>Measured on DuckDB 1.5.5, with external access off: {@code read_csv}, {@code getenv},
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
