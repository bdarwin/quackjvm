package io.quackjvm.agent.guard.internal;

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
 * serializes only statements whose top level is a query, so everything that writes, attaches, loads,
 * copies or configures at the top comes back as {@code "Only SELECT statements can be serialized to
 * json!"}. The result also carries the statements as an array, so more than one statement is a
 * count, not a search for semicolons. Quotes, dollar-quoting and comments are the parser's problem,
 * not ours.</p>
 *
 * <p><b>A top-level SELECT is not enough.</b> DuckDB 2.0's parser serializes a SELECT whose WITH
 * clause deletes, inserts, updates or copies to a file - {@code WITH x AS (DELETE FROM t RETURNING *)
 * SELECT * FROM x} - as a SELECT, with a {@code DELETE_QUERY_NODE} inside it; 1.5 refused it with "A
 * CTE needs a SELECT". Run at the top level, 2.0 executes the write. So every query node in the tree
 * is checked against the kinds that only read, and anything else - including kinds a later DuckDB
 * adds - is not a query.</p>
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

    /** The query nodes a statement that only reads is made of. Everything else writes, or is unknown. */
    static final java.util.List<String> READ_NODES =
            java.util.List.of("SELECT_NODE", "SET_OPERATION_NODE", "RECURSIVE_CTE_NODE", "CTE_NODE");

    private ParserGate() {
    }

    /**
     * Parses the text and reports what it is. Runs one query of our own, which reads nothing and
     * writes nothing.
     */
    public static Verdict inspect(Connection connection, String sql) {
        // Every query node anywhere in the tree, by kind, joined into one string. No lambda: DuckDB 2.0
        // refuses the arrow syntax 1.5 used, and the filtering is simpler in Java anyway.
        String probe = "SELECT (j->>'error')::BOOLEAN AS failed, j->>'error_message' AS message,"
                + " coalesce(json_array_length(j->'statements'), 0) AS statements,"
                + " list_aggregate(regexp_extract_all(j::VARCHAR, '\"type\"\\s*:\\s*\"([A-Z_]+_NODE)\"', 1),"
                + " 'string_agg', ',') AS nodes"
                + " FROM (SELECT json_serialize_sql(" + literal(sql) + ") AS j)";
        try (PreparedStatement statement = connection.prepareStatement(probe);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return new Verdict(false, "DuckDB's parser said nothing about this statement", 0);
            }
            boolean failed = rows.getBoolean(1);
            String message = rows.getString(2);
            int statements = rows.getInt(3);
            String nodes = rows.getString(4);
            if (!failed && nodes != null) {
                for (String node : nodes.split(",")) {
                    if (!node.isBlank() && !READ_NODES.contains(node.trim())) {
                        String kind = node.trim().replace("_QUERY_NODE", "").replace("_NODE", "");
                        return new Verdict(false, "it is a SELECT with a " + kind + " inside it - a WITH clause"
                                + " or subquery that writes", statements);
                    }
                }
            }
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
