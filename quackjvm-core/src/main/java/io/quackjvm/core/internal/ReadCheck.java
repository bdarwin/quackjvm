package io.quackjvm.core.internal;

import org.duckdb.DuckDBResultSetMetaData;
import org.duckdb.StatementReturnType;

import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Refuses, before it runs, a statement handed to a read that would change something and then fail.
 *
 * <p>{@code executeQuery()} on a statement that returns no rows - {@code DROP}, {@code COPY ... TO},
 * {@code ATTACH}, {@code SET} - runs it first and only then throws, so the caller is told the call
 * failed when the database has changed. Two checks close that, measured on duckdb_jdbc 1.5.5.1 and
 * 2.0.0.0:</p>
 *
 * <ul>
 *     <li><b>After preparing, before running:</b> preparing one statement does not run it, and the
 *     prepared statement says whether it returns rows. Anything that does not is refused.</li>
 *     <li><b>Before preparing:</b> preparing several statements at once runs every one but the
 *     last, and preparing {@code EXPORT DATABASE} writes the export. So more than one statement,
 *     and {@code EXPORT} or {@code IMPORT}, are refused without being prepared at all.</li>
 * </ul>
 *
 * <p>What still runs is anything that returns rows, as it always has: {@code PIVOT},
 * {@code DESCRIBE}, {@code EXPLAIN}, {@code CALL}, and also {@code INSERT ... RETURNING} and, on
 * DuckDB 2.0, a {@code SELECT} whose {@code WITH} clause writes. This is a guard against mistakes
 * in SQL you wrote, not against SQL you did not: that is what {@code GuardedQuery} in quackjvm-agent
 * is for.</p>
 */
public final class ReadCheck {

    /** A statement refused before it ran. Still an IllegalStateException, which is what callers catch. */
    public static final class Refused extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private ReadCheck() {
    }

    /** Before preparing: one statement, and not one that preparing runs. */
    public static void beforePreparing(String sql) {
        if (SqlScanner.hasStatementSeparator(sql)) {
            throw new Refused("Not run: this is more than one statement, and DuckDB's driver runs"
                    + " all but the last while preparing them. A query runs one statement; run the others with"
                    + " execute(), one at a time: " + sql);
        }
        String keyword = SqlScanner.leadingKeyword(sql);
        if ("EXPORT".equals(keyword) || "IMPORT".equals(keyword)) {
            throw new Refused("Not run: DuckDB runs " + keyword + " DATABASE while preparing it,"
                    + " and a query only reads. Run it with execute(): " + sql);
        }
    }

    /** After preparing, before running: it must return rows. */
    public static void afterPreparing(PreparedStatement statement, String sql) throws SQLException {
        StatementReturnType returns = returnType(statement);
        if (returns != null && returns != StatementReturnType.QUERY_RESULT) {
            throw new Refused("Not run: this statement returns no rows, and a query only reads."
                    + " Run it with execute(): " + sql);
        }
    }

    /** What the prepared statement returns, or null when this is not DuckDB's driver underneath. */
    private static StatementReturnType returnType(PreparedStatement statement) throws SQLException {
        ResultSetMetaData metaData = statement.getMetaData();
        if (metaData instanceof DuckDBResultSetMetaData duckdb) {
            return duckdb.getReturnType();
        }
        if (metaData != null && metaData.isWrapperFor(DuckDBResultSetMetaData.class)) {
            return metaData.unwrap(DuckDBResultSetMetaData.class).getReturnType();
        }
        return null;
    }
}
