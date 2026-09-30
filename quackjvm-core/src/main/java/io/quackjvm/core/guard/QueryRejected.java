package io.quackjvm.core.guard;

/**
 * A statement was not run, and why. The message is written to be read by whatever sent the SQL,
 * including a language model: it says what was refused and what would be allowed instead.
 */
public class QueryRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sql;

    public QueryRejected(String sql, String reason) {
        super(reason);
        this.sql = sql;
    }

    /** The statement that was refused. */
    public String getSql() {
        return sql;
    }
}
