package io.quackjvm.core.vector;

import io.quackjvm.core.duckdb.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The rows whose vector is nearest a given one.
 *
 * <pre>
 * VectorSearch search = VectorSearch.on(connection, "doc", "embedding").identifiedBy("id");
 *
 * List&lt;Match&gt; nearest = search.topK(queryVector, 10);
 * List&lt;Match&gt; inNews  = search.topK(queryVector, 10, "category = ?", "news");
 * </pre>
 *
 * <p>The work is done by DuckDB's own array functions - {@code array_cosine_similarity},
 * {@code array_distance}, {@code array_inner_product} - which need no extension at all. The column
 * has to be a fixed-size array, {@code FLOAT[768]} rather than {@code FLOAT[]}, because that is what
 * those functions take: see {@code ColumnarLayout.vectorColumn}.</p>
 *
 * <h2>Exact, and what that costs</h2>
 *
 * <p>This is an exact search: every row is scored. Measured on this machine, 768 dimensions,
 * single-threaded per query but parallel across DuckDB's threads: 100,000 rows in 26 ms, and it is
 * linear in rows times dimensions. Below a few hundred thousand vectors that is usually the whole
 * answer; above it, an index earns its keep.</p>
 *
 * <h2>The index, if it is there</h2>
 *
 * <p>DuckDB's HNSW index lives in the {@code vss} extension, which has to be installed already -
 * installing one needs the network and a writable extension directory, which a hardened database
 * forbids by design. {@link #isIndexAvailable} says whether it can be used here, and
 * {@link #createIndex} makes one when it can and says why not when it cannot. The queries this
 * builds are the same either way: an HNSW index is used by the planner for exactly this shape of
 * query, so nothing about the call changes.</p>
 */
public final class VectorSearch {

    /** How nearness is measured. */
    public enum Metric {
        /** {@code array_cosine_similarity}: 1 is identical, -1 is opposite. Higher is nearer. */
        COSINE("array_cosine_similarity", true, "cosine"),
        /** {@code array_distance}: 0 is identical. Lower is nearer. */
        EUCLIDEAN("array_distance", false, "l2sq"),
        /** {@code array_inner_product}: higher is nearer, and unlike cosine it is not normalised. */
        INNER_PRODUCT("array_inner_product", true, "ip");

        private final String function;
        private final boolean higherIsNearer;
        private final String indexMetric;

        Metric(String function, boolean higherIsNearer, String indexMetric) {
            this.function = function;
            this.higherIsNearer = higherIsNearer;
            this.indexMetric = indexMetric;
        }

        public String getFunction() {
            return function;
        }

        /** Whether a bigger score means nearer, which decides the direction of the sort. */
        public boolean isHigherNearer() {
            return higherIsNearer;
        }
    }

    /** One row that matched: what identifies it, and how near it is. */
    public record Match(Object id, double score) {
    }

    private final Connection connection;
    private final String table;
    private final String column;
    private final String idColumn;
    private final Metric metric;

    private VectorSearch(Connection connection, String table, String column, String idColumn, Metric metric) {
        this.connection = connection;
        this.table = table;
        this.column = column;
        this.idColumn = idColumn;
        this.metric = metric;
    }

    /** Searches a fixed-size array column of a table. */
    public static VectorSearch on(Connection connection, String table, String column) {
        return new VectorSearch(connection, table, column, null, Metric.COSINE);
    }

    /** Which column identifies a row in the answer. Without it, the answer carries row numbers. */
    public VectorSearch identifiedBy(String idColumn) {
        return new VectorSearch(connection, table, column, idColumn, metric);
    }

    /** How nearness is measured. Cosine similarity by default. */
    public VectorSearch metric(Metric metric) {
        return new VectorSearch(connection, table, column, idColumn, metric);
    }

    /**
     * The {@code k} nearest rows, nearest first.
     *
     * @param query     the vector to compare against; its length must be the column's
     * @param k         how many rows to return
     * @param filterSql an optional {@code WHERE} clause without the keyword, or null
     * @param params    values for the filter's {@code ?} placeholders
     */
    public List<Match> topK(float[] query, int k, String filterSql, Object... params) {
        String sql = sql(query, k, filterSql);
        List<Match> matches = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Sql.bindAll(statement, List.of(params), 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    matches.add(new Match(rows.getObject(1), rows.getDouble(2)));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to search " + table + "." + column + ": " + firstLine(e), e);
        }
        return matches;
    }

    /** The {@code k} nearest rows, nearest first. */
    public List<Match> topK(float[] query, int k) {
        return topK(query, k, null);
    }

    /**
     * The SQL this would run, for joining your own tables to it or for reading.
     *
     * <p>The query vector is written into the SQL rather than bound, because DuckDB's parameters do
     * not carry a fixed-size array. It is numbers, so there is nothing to escape.</p>
     */
    public String sql(float[] query, int k, String filterSql) {
        checkVector(query);
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive: " + k);
        }
        String score = metric.getFunction() + "(" + Sql.quote(column) + ", " + literal(query) + ")";
        return "SELECT " + (idColumn == null ? "row_number() OVER ()" : Sql.quote(idColumn)) + " AS id, "
                + score + " AS score FROM " + Sql.quote(table)
                + (filterSql == null || filterSql.isBlank() ? "" : " WHERE " + filterSql)
                + " ORDER BY score " + (metric.isHigherNearer() ? "DESC" : "ASC")
                + " LIMIT " + k;
    }

    // ---------- the index ----------

    /**
     * Whether an HNSW index can be used on this database: the {@code vss} extension is installed and
     * can be loaded without reaching the network.
     */
    public static boolean isIndexAvailable(Connection connection) {
        return unavailableReason(connection) == null;
    }

    /** Why an HNSW index cannot be used here, or null when it can. */
    public static String unavailableReason(Connection connection) {
        boolean loaded = Sql.queryLong(connection, "SELECT count(*) FROM duckdb_extensions()"
                + " WHERE extension_name = 'vss' AND loaded", List.of()) > 0;
        if (loaded) {
            return null;
        }
        boolean installed = Sql.queryLong(connection, "SELECT count(*) FROM duckdb_extensions()"
                + " WHERE extension_name = 'vss' AND installed", List.of()) > 0;
        if (!installed) {
            return "the vss extension is not installed. Install it once, outside quackjvm, with"
                    + " INSTALL vss - it needs the network, which a hardened database forbids."
                    + " Exact search works without it.";
        }
        try {
            Sql.execute(connection, "LOAD vss");
            return null;
        }
        catch (RuntimeException e) {
            return "the vss extension is installed but would not load: " + e.getMessage().split("\n")[0];
        }
    }

    /**
     * Creates an HNSW index over the column, if that is possible here.
     *
     * @return null when the index was created, or the reason it was not
     */
    public String createIndex() {
        String unavailable = unavailableReason(connection);
        if (unavailable != null) {
            return unavailable;
        }
        try {
            // An HNSW index in a file database is experimental in DuckDB, and says so unless told.
            Sql.execute(connection, "SET hnsw_enable_experimental_persistence = true");
        }
        catch (RuntimeException notNeeded) {
            // An in-memory database does not need it.
        }
        String name = ("hnsw_" + table + "_" + column).toLowerCase(Locale.ROOT);
        try {
            Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote(name) + " ON " + Sql.quote(table)
                    + " USING HNSW (" + Sql.quote(column) + ") WITH (metric = '" + metric.indexMetric + "')");
            return null;
        }
        catch (RuntimeException e) {
            return "the index could not be created: " + e.getMessage().split("\n")[0];
        }
    }

    // ---------- internals ----------

    private void checkVector(float[] query) {
        if (query == null || query.length == 0) {
            throw new IllegalArgumentException("There is no vector to search for");
        }
        String type = columnType();
        if (type != null && !type.matches(".*\\[\\d+]")) {
            throw new IllegalArgumentException(table + "." + column + " is " + type + ", and DuckDB's array"
                    + " functions need a fixed size - FLOAT[" + query.length + "] rather than FLOAT[]."
                    + " Declare it with ColumnarLayout.vectorColumn(name, " + query.length + ", accessor).");
        }
        if (type != null) {
            int declared = Integer.parseInt(type.substring(type.indexOf('[') + 1, type.length() - 1));
            if (declared != query.length) {
                throw new IllegalArgumentException(table + "." + column + " holds " + declared
                        + " values and this query has " + query.length + ".");
            }
        }
    }

    private String columnType() {
        List<String> types = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT data_type FROM duckdb_columns()"
                + " WHERE table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    types.add(rows.getString(1));
                }
            }
        }
        catch (SQLException e) {
            return null;
        }
        return types.isEmpty() ? null : types.get(0);
    }

    /** {@code [1.0, 2.0, ...]::FLOAT[n]} - numbers, so there is nothing to escape. */
    private static String literal(float[] vector) {
        StringBuilder sql = new StringBuilder(vector.length * 8 + 16).append('[');
        for (int i = 0; i < vector.length; i++) {
            sql.append(i == 0 ? "" : ", ").append(vector[i]);
        }
        return sql.append("]::FLOAT[").append(vector.length).append(']').toString();
    }

    private static String firstLine(SQLException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    @Override
    public String toString() {
        return "VectorSearch[" + table + "." + column + " by " + metric + "]";
    }
}
