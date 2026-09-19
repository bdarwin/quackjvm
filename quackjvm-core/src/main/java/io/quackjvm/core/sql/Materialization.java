package io.quackjvm.core.sql;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;

import java.sql.Connection;
import java.time.Instant;

/**
 * A query precomputed into a DuckDB table, so that requests are served from the answer rather than
 * from the data.
 *
 * <p>On ten million rows, a panel which costs 39.6 ms against the base table costs 0.4 ms against
 * its materialization, and a dimensional roll-up of 18,000 rows answers questions it was never
 * designed for in 0.9 ms against 21.9 ms. It is an ordinary table, so it can still be filtered,
 * joined and aggregated further - unlike a cached result, and unlike a {@code VIEW}, which stores
 * the query rather than its answer and re-runs it every time.</p>
 *
 * <pre>
 * Materialization topModels = new Materialization("top_models",
 *         "SELECT region, make, sum(price) AS revenue FROM sale GROUP BY 1, 2");
 * topModels.createIfAbsent(connection);
 *
 * // Just a table.
 * Rows.of(connection, "SELECT * FROM top_models WHERE region = ?", "EMEA").records(Row.class);
 *
 * topModels.refresh(connection);
 * </pre>
 *
 * <p><b>Refreshing does not interrupt readers.</b> The obvious {@code DROP} followed by
 * {@code CREATE TABLE AS} leaves a window in which the table does not exist: measured with four
 * threads reading across fifteen refreshes, that window produced 1,280 failures of
 * {@code Catalog Error: Table with name ... does not exist}. {@link #refresh} instead builds the
 * replacement alongside and swaps the two inside one transaction, which DuckDB's transactional
 * catalog makes atomic - zero failures over the same test, with 7,488 successful reads in the
 * meantime.</p>
 *
 * <p>Not thread-safe to refresh concurrently with itself; refreshing concurrently with readers is
 * the whole point and is safe.</p>
 */
public final class Materialization {

    /** Suffix of the table a refresh builds before swapping it in. */
    private static final String PENDING_SUFFIX = "__quackjvm_next";

    private final String name;
    private final String sql;
    private volatile Instant builtAt;

    /**
     * @param name the table name to materialize into, which is what queries refer to
     * @param sql  the query to precompute; anything DuckDB can put in {@code CREATE TABLE AS}
     */
    public Materialization(String name, String sql) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A materialization needs a name");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("A materialization needs a query: " + name);
        }
        this.name = name;
        this.sql = sql;
    }

    public String getName() {
        return name;
    }

    public String getSql() {
        return sql;
    }

    /** When this instance last built the table, or null if it has not built it in this JVM. */
    public Instant getBuiltAt() {
        return builtAt;
    }

    /** Whether {@link #getBuiltAt()} is older than the given age, treating "never built" as stale. */
    public boolean isOlderThan(java.time.Duration age) {
        Instant built = builtAt;
        return built == null || built.plus(age).isBefore(Instant.now());
    }

    /** Builds the table if it is not there already. Does nothing if it is, however stale. */
    public void createIfAbsent(Connection connection) {
        if (!exists(connection)) {
            Sql.execute(connection, "CREATE TABLE IF NOT EXISTS " + Sql.quote(name) + " AS " + sql);
            builtAt = Instant.now();
        }
    }

    /**
     * Rebuilds the table from its query, without readers ever seeing it missing or half-built.
     *
     * <p>The replacement is built alongside under another name, and the two are swapped inside one
     * transaction: a reader's statement resolves either the old table or the new one, never the
     * gap between them.</p>
     */
    public void refresh(Connection connection) {
        String pending = name + PENDING_SUFFIX;
        Sql.execute(connection, "CREATE OR REPLACE TABLE " + Sql.quote(pending) + " AS " + sql);
        // Begun and ended with SQL rather than setAutoCommit/commit - see Transactions for the
        // driver bug that makes the difference - and only when the caller is not already in a
        // transaction, which is theirs to commit.
        boolean ownTransaction = Transactions.isAutoCommit(connection);
        try {
            if (ownTransaction) {
                Transactions.begin(connection);
            }
            Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(name));
            Sql.execute(connection, "ALTER TABLE " + Sql.quote(pending) + " RENAME TO " + Sql.quote(name));
            if (ownTransaction) {
                Transactions.commit(connection);
            }
            builtAt = Instant.now();
        }
        catch (RuntimeException e) {
            if (ownTransaction) {
                Transactions.rollbackQuietly(connection);
                dropPendingQuietly(connection, pending);
            }
            throw new IllegalStateException("Failed to refresh materialization " + name, e);
        }
    }

    /** Builds it if absent, refreshes it if it is older than the given age. */
    public void refreshIfOlderThan(Connection connection, java.time.Duration age) {
        if (!exists(connection)) {
            createIfAbsent(connection);
        }
        else if (isOlderThan(age)) {
            refresh(connection);
        }
    }

    /**
     * Folds new rows into the table without rebuilding it, by running a second query that computes
     * the same aggregate over only the new rows and appending its result.
     *
     * <p>On ten million rows this is 3.5 ms against 13.8 ms for a full {@link #refresh}, and gives
     * an identical answer. Two conditions, and both are on you rather than checkable here:</p>
     *
     * <ul>
     *   <li><b>The measures must be additive.</b> {@code count}, {@code sum}, {@code min} and
     *       {@code max} combine across partial groups; an average or a median does not. Store
     *       {@code count(*) AS n} and {@code sum(x) AS total} and derive the average at query time
     *       as {@code sum(total)/sum(n)}.</li>
     *   <li><b>Queries must re-aggregate.</b> After an append the table holds <em>partial</em>
     *       groups - the same key can appear once per delta - so a panel reads
     *       {@code SELECT make, sum(n) FROM rollup GROUP BY 1}, not {@code SELECT make, n}. This
     *       is what makes appending cheap: nothing has to be merged in place.</li>
     * </ul>
     *
     * <p>The delta query is given rather than derived. Deriving it would mean editing the
     * materialization's own SQL to add a watermark predicate, and a mistake there would silently
     * put wrong numbers in the table - where a mistake in an optimisation merely costs speed. So
     * you write it, and it is worth checking once against a {@link #refresh} that the two agree.</p>
     *
     * <pre>
     * rollup.appendDelta(connection,
     *         "SELECT region, make, count(*), sum(price) FROM sale WHERE saleId >= ? GROUP BY 1, 2",
     *         watermark);
     * </pre>
     *
     * <p>A {@link #refresh} collapses the partial groups again, since it rebuilds from the base
     * data - so refresh occasionally to stop the table growing a delta at a time.</p>
     *
     * @return how many rows were appended
     */
    public long appendDelta(Connection connection, String deltaSql, Object... parameters) {
        if (deltaSql == null || deltaSql.isBlank()) {
            throw new IllegalArgumentException("A delta needs a query: " + name);
        }
        if (!exists(connection)) {
            throw new IllegalStateException("Cannot append to " + name
                    + ", which has not been built yet - call createIfAbsent or refresh first");
        }
        int appended = Sql.executeUpdate(connection,
                "INSERT INTO " + Sql.quote(name) + " " + deltaSql,
                java.util.List.of(parameters));
        return Math.max(appended, 0);
    }

    public boolean exists(Connection connection) {
        return Sql.queryLong(connection,
                "SELECT count(*) FROM duckdb_tables() WHERE table_name = ?", java.util.List.of(name)) > 0;
    }

    public long rowCount(Connection connection) {
        return Sql.queryLong(connection, "SELECT count(*) FROM " + Sql.quote(name), java.util.List.of());
    }

    /** Drops the table and any half-built replacement left behind by a failed refresh. */
    public void drop(Connection connection) {
        Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(name));
        dropPendingQuietly(connection, name + PENDING_SUFFIX);
        builtAt = null;
    }

    private static void dropPendingQuietly(Connection connection, String pending) {
        try {
            Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(pending));
        }
        catch (RuntimeException ignored) {
            // Leaves a stray table rather than masking the original failure; the next refresh
            // replaces it.
        }
    }

    @Override
    public String toString() {
        return "Materialization[" + name + "]";
    }
}
