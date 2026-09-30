package io.quackjvm.core.guard;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a {@link GuardedQuery} will allow: which statements, for how long, and how much it may
 * return.
 *
 * <pre>
 * QueryPolicy policy = QueryPolicy.readOnly()
 *         .timeout(Duration.ofSeconds(5))
 *         .maxRows(1_000)
 *         .maxBytes(1 &lt;&lt; 20);
 * </pre>
 *
 * <p>The defaults are meant for SQL written by something you do not control: five seconds, ten
 * thousand rows, eight megabytes, and only statements DuckDB's own parser will vouch for as
 * queries.</p>
 */
public final class QueryPolicy {

    /** Statements DuckDB's parser cannot serialize, which may be allowed one by one. */
    public static final String PIVOT = "PIVOT";

    private final Duration timeout;
    private final long maxRows;
    private final long maxBytes;
    private final Set<String> unverified;
    private final boolean allowExplain;
    private final Hardening hardening;

    private QueryPolicy(Duration timeout, long maxRows, long maxBytes, Set<String> unverified,
                        boolean allowExplain, Hardening hardening) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A timeout must be positive: " + timeout);
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive: " + maxRows);
        }
        this.timeout = timeout;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.unverified = Set.copyOf(unverified);
        this.allowExplain = allowExplain;
        this.hardening = hardening;
    }

    /** Reads only, five seconds, ten thousand rows, eight megabytes, EXPLAIN allowed. */
    public static QueryPolicy readOnly() {
        return new QueryPolicy(Duration.ofSeconds(5), 10_000, 8L * 1024 * 1024, Set.of(), true,
                Hardening.NONE);
    }

    /** How long a query may run before it is cancelled. */
    public QueryPolicy timeout(Duration timeout) {
        return new QueryPolicy(timeout, maxRows, maxBytes, unverified, allowExplain, hardening);
    }

    /** How many rows a query may return before {@link ResultTruncated} is thrown. */
    public QueryPolicy maxRows(long rows) {
        return new QueryPolicy(timeout, rows, maxBytes, unverified, allowExplain, hardening);
    }

    /** Roughly how many bytes the values may add up to; zero for no limit. */
    public QueryPolicy maxBytes(long bytes) {
        return new QueryPolicy(timeout, maxRows, bytes, unverified, allowExplain, hardening);
    }

    /** Whether {@code EXPLAIN} and {@code EXPLAIN ANALYZE} of an allowed query may be run. */
    public QueryPolicy allowExplain(boolean allow) {
        return new QueryPolicy(timeout, maxRows, maxBytes, unverified, allow, hardening);
    }

    /**
     * Allows a statement kind DuckDB's parser will not serialize, and therefore cannot vouch for.
     *
     * <p>Only {@link #PIVOT} qualifies today. Everything else DuckDB refuses to serialize is a
     * statement that writes, attaches, loads or configures something, and no allowlist will let it
     * through.</p>
     *
     * <p>What is lost by allowing it: the parser is no longer what decides. The statement is checked
     * by its first keyword, and a scan for a statement separator outside quotes and comments stands
     * in for the parser's promise that there is only one statement. The timeout and the row and byte
     * caps still apply, and so does {@link Hardening} if it is on. Leave it off unless a pivot is
     * worth that.</p>
     */
    public QueryPolicy allowUnverified(String... statementKeywords) {
        Set<String> allowed = new LinkedHashSet<>(unverified);
        for (String keyword : statementKeywords) {
            String upper = keyword.toUpperCase(Locale.ROOT);
            if (!PIVOT.equals(upper)) {
                throw new IllegalArgumentException("'" + keyword + "' is not a statement this can allow."
                        + " DuckDB's parser refuses to serialize it because it is not a query at all, and"
                        + " allowing it by keyword would be a hole, not a policy. Only " + PIVOT + " can be.");
            }
            allowed.add(upper);
        }
        return new QueryPolicy(timeout, maxRows, maxBytes, allowed, allowExplain, hardening);
    }

    /**
     * Whether to take away the database's access to files, extensions and the network as well as
     * checking statements - see {@link Hardening}, and read what it says about scope first.
     */
    public QueryPolicy hardening(Hardening hardening) {
        return new QueryPolicy(timeout, maxRows, maxBytes, unverified, allowExplain, hardening);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public long getMaxRows() {
        return maxRows;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public boolean isAllowExplain() {
        return allowExplain;
    }

    public boolean allows(String statementKeyword) {
        return unverified.contains(statementKeyword.toUpperCase(Locale.ROOT));
    }

    public Set<String> getUnverified() {
        return unverified;
    }

    public Hardening getHardening() {
        return hardening;
    }

    @Override
    public String toString() {
        return "QueryPolicy[timeout=" + timeout + ", maxRows=" + maxRows + ", maxBytes=" + maxBytes
                + ", unverified=" + unverified + ", explain=" + allowExplain + ", hardening=" + hardening + "]";
    }
}
