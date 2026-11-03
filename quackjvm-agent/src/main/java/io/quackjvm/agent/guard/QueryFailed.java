package io.quackjvm.agent.guard;

import java.sql.SQLException;

/**
 * DuckDB could not run a statement the guard let through: a column or table that is not there, a
 * value that will not convert, a file hardening keeps out. The guard's own refusals are plain
 * {@link QueryRejected}s; this is a subtype, so code that catches that catches both.
 *
 * <p>The message is DuckDB's, all of it: what went wrong, the {@code Candidate bindings} it
 * suggests, and the {@code LINE n:} pointer, numbered as the caller wrote the SQL. That is the
 * part whoever wrote it - a person or a language model - fixes the query from. The cause is the
 * driver's {@link SQLException}.</p>
 */
public class QueryFailed extends QueryRejected {

    private static final long serialVersionUID = 1L;

    private final String errorType;

    public QueryFailed(String sql, String message, SQLException cause) {
        super(sql, message, cause);
        this.errorType = errorType(message);
    }

    /**
     * The kind of error DuckDB named - {@code "Binder"}, {@code "Catalog"}, {@code "Conversion"},
     * {@code "Permission"} and so on - or an empty string if its message did not start with one.
     */
    public String getErrorType() {
        return errorType;
    }

    private static String errorType(String message) {
        int end = message.indexOf(" Error:");
        if (end <= 0) {
            return "";
        }
        String type = message.substring(0, end);
        return type.chars().allMatch(c -> Character.isLetter(c) || c == ' ') ? type : "";
    }
}
