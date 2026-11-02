package io.quackjvm.agent.guard;

import io.quackjvm.core.Quack;
import io.quackjvm.agent.guard.internal.ParserGate;
import io.quackjvm.core.internal.Deadline;
import io.quackjvm.core.internal.SqlScanner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs SQL that came from somewhere you do not control.
 *
 * <pre>
 * GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly());
 * GuardedResult result = guard.run("SELECT make, count(*) FROM car GROUP BY 1");
 * System.out.println(result.toText());
 *
 * // the first 100 rows of anything, saying when there were more; and a way to stop it
 * GuardedQuery firstRows = GuardedQuery.on(connection, QueryPolicy.readOnly().maxRows(100).whenOver(Over.TRUNCATE));
 * CancelSignal stop = new CancelSignal();
 * GuardedResult page = firstRows.run(sql, stop);     // stop.cancel() from another thread: QueryCancelled
 * page.isTruncated();
 * </pre>
 *
 * <p>Four things stand between the statement and the database, and each one fails with a message
 * meant to be read by whoever wrote the SQL:</p>
 *
 * <ol>
 *   <li><b>What it is.</b> DuckDB's own parser decides, not a pattern: a statement it will not
 *       serialize as a query is refused, which is everything that writes, attaches, loads, copies
 *       or configures - a CTE with an INSERT inside it included. More than one statement is
 *       refused by counting them, not by looking for semicolons.</li>
 *   <li><b>How long.</b> The statement is cancelled when the policy's time is up. DuckDB stops
 *       mid-scan and the connection stays usable - measured at 310 ms for a cancel of a query that
 *       would have run for minutes.</li>
 *   <li><b>How much.</b> The row cap becomes a {@code LIMIT} around the statement, so the rows are
 *       never built in the first place; a query that would exceed it is refused rather than
 *       quietly cut short - or, under {@code whenOver(Over.TRUNCATE)}, its first rows come back
 *       marked as truncated. The byte cap is counted as the values are read.</li>
 *   <li><b>What it can reach</b>, if {@link Hardening} is turned on - and read what that says
 *       before turning it on, because it applies to the whole database.</li>
 * </ol>
 *
 * <p><b>The statement is never prepared to find out what it is.</b> With this driver,
 * {@code prepareStatement("DROP TABLE u; SELECT 1")} drops the table: preparing a multi-statement
 * string executes all but the last. Everything here parses the text by passing it to DuckDB as a
 * string, and only prepares a statement once it is known to be a single query.</p>
 */
public final class GuardedQuery {

    /** Named so that it cannot collide with a table in the statement being wrapped. */
    private static final String WRAPPER = "quackjvm_guarded";

    private final Connection connection;
    private final QueryPolicy policy;

    private GuardedQuery(Connection connection, QueryPolicy policy) {
        this.connection = connection;
        this.policy = policy;
        if (policy.getHardening() == Hardening.DATABASE) {
            Hardening.apply(connection);
        }
    }

    public static GuardedQuery on(Connection connection, QueryPolicy policy) {
        return new GuardedQuery(connection, policy);
    }

    /** A guard on a {@link Quack}'s database, borrowing a connection per query. Safe to share between threads. */
    public static GuardedQuery on(Quack quack, QueryPolicy policy) {
        return on(quack.sharedConnection(), policy);
    }

    public QueryPolicy getPolicy() {
        return policy;
    }

    /**
     * Checks a statement and runs it.
     *
     * @throws QueryRejected   if it is not a single query, or not one this policy allows
     * @throws QueryTimedOut   if it ran for longer than the policy allows
     * @throws ResultTruncated if it would return more rows or bytes than the policy allows
     */
    public GuardedResult run(String sql) {
        return run(sql, null);
    }

    /**
     * As {@link #run(String)}, stopped early if the signal is cancelled - from another thread, a
     * Stop button for instance.
     *
     * @throws QueryCancelled if the signal was cancelled before or while it ran
     */
    public GuardedResult run(String sql, CancelSignal signal) {
        String statement = check(sql);
        return execute(statement, isExplain(statement), signal);
    }

    /**
     * Checks a statement and says why it would be refused, without running it. Returns null when it
     * would be allowed.
     */
    public String whyRejected(String sql) {
        try {
            check(sql);
            return null;
        }
        catch (QueryRejected rejected) {
            return rejected.getMessage();
        }
    }

    /** The statement as it will be run, once it is known to be allowed. */
    private String check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new QueryRejected(sql, "There is no statement to run.");
        }
        String trimmed = sql.trim();
        String keyword = SqlScanner.leadingKeyword(trimmed);

        if (policy.isAllowExplain() && ("EXPLAIN".equals(keyword))) {
            // EXPLAIN is not serializable, but what it explains is: check that, and the EXPLAIN of
            // an allowed query is itself allowed. EXPLAIN ANALYZE runs the query, which is why the
            // query has to be one that may be run.
            String explained = SqlScanner.afterLeadingKeyword(trimmed);
            if ("ANALYZE".equals(SqlScanner.leadingKeyword(explained))) {
                explained = SqlScanner.afterLeadingKeyword(explained);
            }
            ParserGate.Verdict inner = ParserGate.inspect(connection, explained);
            if (inner.isSingleQuery()) {
                return trimmed;
            }
            throw new QueryRejected(sql, "EXPLAIN is allowed, but " + describe(inner, explained));
        }

        ParserGate.Verdict verdict = ParserGate.inspect(connection, trimmed);
        if (verdict.isSingleQuery()) {
            return trimmed;
        }
        if (!verdict.parsedAsQuery() && policy.allows(keyword)) {
            // A statement kind the parser will not serialize, allowed on purpose. The parser cannot
            // say how many statements this is, so the scanner does - see QueryPolicy.allowUnverified.
            if (SqlScanner.hasStatementSeparator(trimmed)) {
                throw new QueryRejected(sql, "Only one statement can be run at a time, and this is more than one.");
            }
            return trimmed;
        }
        throw new QueryRejected(sql, capitalise(describe(verdict, trimmed)));
    }

    /** Why a statement is not an allowed query, in words meant for whoever wrote it. */
    private String describe(ParserGate.Verdict verdict, String sql) {
        if (verdict.parsedAsQuery() && verdict.statements() == 0) {
            return "there is no statement to run - only whitespace or a comment.";
        }
        if (verdict.parsedAsQuery() && verdict.statements() != 1) {
            return "only one statement can be run at a time, and this is " + verdict.statements() + ".";
        }
        if (verdict.error() != null && verdict.error().startsWith("it is a SELECT with a ")) {
            return "this is not a query, so it will not be run: " + verdict.error() + ".";
        }
        String keyword = SqlScanner.leadingKeyword(sql);
        String what = keyword.isEmpty() ? "this" : keyword;
        if (QueryPolicy.PIVOT.equals(keyword)) {
            return what + " cannot be checked: DuckDB's parser will not serialize a pivot, so there is no way"
                    + " to tell it apart from a statement that writes. Rewrite it as a GROUP BY, or allow it"
                    + " deliberately with QueryPolicy.allowUnverified(QueryPolicy.PIVOT).";
        }
        return what + " is not a query, so it will not be run. Only statements that read are allowed:"
                + " SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT"
                + (policy.isAllowExplain() ? " and EXPLAIN" : "")
                + ". DuckDB's parser said: " + verdict.error();
    }

    private GuardedResult execute(String sql, boolean explaining, CancelSignal signal) {
        long maxRows = policy.getMaxRows();
        String toRun = sql;
        if (!explaining) {
            // One more row than allowed, so that "too many" can be told from "exactly the limit". A
            // trailing semicolon would end the statement inside the bracket and break the wrapper,
            // so it is taken off first; the statement is already known to be a single one.
            String body = sql.replaceAll("[;\\s]+$", "");
            String wrapped = "SELECT * FROM (\n" + body + "\n) AS " + WRAPPER + " LIMIT " + (maxRows + 1);
            // The wrapper is checked as a whole: a statement that would break out of it - by closing
            // the bracket and starting another statement - stops being a single query, and is caught
            // here rather than run.
            if (ParserGate.inspect(connection, wrapped).isSingleQuery()) {
                toRun = wrapped;
            }
        }
        long started = System.nanoTime();
        Deadline deadline = null;
        try (PreparedStatement statement = connection.prepareStatement(toRun)) {
            if (signal != null && !signal.attach(statement)) {
                throw new QueryCancelled(null);
            }
            try {
                deadline = Deadline.start(statement, policy.getTimeout());
                try (Deadline running = deadline; ResultSet rows = statement.executeQuery()) {
                    return read(rows, started, signal);
                }
            }
            finally {
                if (signal != null) {
                    signal.detach();
                }
            }
        }
        catch (SQLException e) {
            if (signal != null && signal.isCancelled()) {
                throw new QueryCancelled(e);
            }
            if (deadline != null && deadline.hasExpired()) {
                throw new QueryTimedOut(policy.getTimeout(), e);
            }
            throw new QueryRejected(sql, "DuckDB refused to run this: " + firstLine(e));
        }
    }

    private GuardedResult read(ResultSet rows, long started, CancelSignal signal) throws SQLException {
        ResultSetMetaData metaData = rows.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
            types.add(metaData.getColumnTypeName(i));
        }
        boolean truncate = policy.getWhenOver() == QueryPolicy.Over.TRUNCATE;
        List<Object[]> read = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;
        while (rows.next()) {
            if (signal != null && signal.isCancelled()) {
                // Cancelled between two reads, where the driver has nothing running to interrupt.
                throw new QueryCancelled(null);
            }
            // The wrapper fetched one row more than allowed, and an unwrapped statement is capped
            // as its rows arrive: either way, a row past the limit means there were more.
            if (read.size() == policy.getMaxRows()) {
                if (truncate) {
                    truncated = true;
                    break;
                }
                throw ResultTruncated.rows(policy.getMaxRows());
            }
            Object[] row = new Object[columnCount];
            long rowBytes = 0;
            for (int i = 0; i < columnCount; i++) {
                row[i] = rows.getObject(i + 1);
                rowBytes += sizeOf(row[i]);
            }
            if (policy.getMaxBytes() > 0 && bytes + rowBytes > policy.getMaxBytes()) {
                if (truncate) {
                    // The rows before this one fit; this one would not.
                    truncated = true;
                    break;
                }
                throw ResultTruncated.bytes(policy.getMaxBytes(), bytes + rowBytes);
            }
            bytes += rowBytes;
            read.add(row);
        }
        return new GuardedResult(columns, types, read, Duration.ofNanos(System.nanoTime() - started), bytes,
                truncated);
    }

    /** Roughly what a value costs, for the byte cap. Not exact, and does not need to be. */
    private static long sizeOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String text) {
            return 2L * text.length();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 8;
        }
        return 32;
    }

    private static boolean isExplain(String sql) {
        return "EXPLAIN".equals(SqlScanner.leadingKeyword(sql));
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String firstLine(SQLException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    @Override
    public String toString() {
        return "GuardedQuery[" + policy + "]";
    }
}
