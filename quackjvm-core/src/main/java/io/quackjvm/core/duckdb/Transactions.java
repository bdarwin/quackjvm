package io.quackjvm.core.duckdb;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Transactions begun and ended with SQL, on a connection left in auto-commit mode throughout.
 *
 * <p>Not {@code setAutoCommit(false)} and {@code commit()}, because of a bug in DuckDB's JDBC driver
 * (1.5.5): once a {@code commit()} fails - on a constraint violation, or a conflict with another
 * transaction - the driver's idea of whether a transaction is open no longer matches DuckDB's, and
 * no sequence of JDBC calls brings them back together. Every later {@code commit()} on that
 * connection throws "cannot commit - no transaction is active", and the statements it was meant to
 * commit have <b>already been committed one at a time</b>. So a write reports failure when it
 * succeeded, a caller who retries it duplicates it, and a multi-statement write is no longer
 * atomic.</p>
 *
 * <p>{@code BEGIN TRANSACTION}, {@code COMMIT} and {@code ROLLBACK} as statements go straight to
 * DuckDB and leave the driver's bookkeeping alone. Measured: a failed {@code COMMIT} ends the
 * transaction cleanly, the connection's next transactions commit and roll back as they should, and
 * the Appender takes part in them.</p>
 */
public final class Transactions {

    private Transactions() {
    }

    /** Whether the caller is outside a transaction of their own, so one of ours is ours to end. */
    public static boolean isAutoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        }
        catch (SQLException e) {
            throw new IllegalStateException("Cannot tell whether the connection is in a transaction", e);
        }
    }

    public static void begin(Connection connection) {
        Sql.execute(connection, "BEGIN TRANSACTION");
    }

    public static void commit(Connection connection) {
        Sql.execute(connection, "COMMIT");
    }

    /**
     * Rolls back if there is anything to roll back. A {@code COMMIT} that failed has already ended
     * the transaction, so "no transaction is active" here is expected and not an error.
     */
    public static void rollbackQuietly(Connection connection) {
        try {
            Sql.execute(connection, "ROLLBACK");
        }
        catch (RuntimeException ignored) {
            // Nothing was open; the original failure is what the caller needs to see.
        }
    }
}
