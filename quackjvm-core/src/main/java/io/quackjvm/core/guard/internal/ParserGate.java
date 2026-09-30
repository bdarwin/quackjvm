package io.quackjvm.core.guard.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Asks DuckDB's own parser what a statement is, without running it.
 *
 * <h2>Why this and not something else</h2>
 *
 * <p>{@code json_serialize_sql(text)} parses the text and returns its syntax tree, or an error. It
 * serializes only queries, so everything that writes, attaches, loads, copies or configures comes
 * back as {@code "Only SELECT statements can be serialized to json!"} - including a CTE with an
 * INSERT inside it, which comes back as "A CTE needs a SELECT". The result also carries the
 * statements as an array, so more than one statement is a count, not a search for semicolons.
 * Quotes, dollar-quoting and comments are the parser's problem, not ours.</p>
 *
 * <p>The obvious alternative - preparing the statement and looking at what comes back - is unsafe
 * with this driver, and that is not a theory: {@code prepareStatement("DROP TABLE u; SELECT 1")}
 * <b>drops the table</b>. Preparing a multi-statement string executes everything but the last
 * statement. So nothing here ever prepares the text it is checking; it is passed to the parser as a
 * string literal inside a query of ours.</p>
 *
 * <p>What it cannot do: DuckDB refuses to serialize {@code PIVOT} as well, even inside a subquery,
 * so a pivot cannot be told apart from a write by asking the parser. That is why allowing one is a
 * deliberate, separate choice - see {@code QueryPolicy.allowUnverified}.</p>
 */
public final class ParserGate {

    /** What the parser made of a statement. */
    public record Verdict(boolean parsedAsQuery, String error, int statements) {

        public boolean isSingleQuery() {
            return parsedAsQuery && statements == 1;
        }
    }

    private ParserGate() {
    }

    /**
     * Parses the text and reports what it is. Runs one query of our own, which reads nothing and
     * writes nothing.
     */
    public static Verdict inspect(Connection connection, String sql) {
        String probe = "SELECT (j->>'error')::BOOLEAN AS failed, j->>'error_message' AS message,"
                + " coalesce(json_array_length(j->'statements'), 0) AS statements"
                + " FROM (SELECT json_serialize_sql(" + literal(sql) + ") AS j)";
        try (PreparedStatement statement = connection.prepareStatement(probe);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return new Verdict(false, "DuckDB's parser said nothing about this statement", 0);
            }
            boolean failed = rows.getBoolean(1);
            String message = rows.getString(2);
            int statements = rows.getInt(3);
            return new Verdict(!failed, failed ? message : null, statements);
        }
        catch (SQLException e) {
            // The parser itself could not be asked - treat that as a refusal rather than a pass.
            return new Verdict(false, "DuckDB's parser could not read this statement: " + firstLine(e), 0);
        }
    }

    /** A string literal for DuckDB: only the quote needs doubling, and backslash is not an escape. */
    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String firstLine(Exception e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
