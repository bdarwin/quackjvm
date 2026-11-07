package io.quackjvm.core;

import io.quackjvm.core.duckdb.Sql;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Where an S3-compatible object store is and the keys for it, so that SQL can read and write
 * {@code s3://} locations: AWS S3 itself, or any server that speaks its API - MinIO, cairn, R2.
 *
 * <pre>
 * ObjectStore store = ObjectStore.s3("http://localhost:9000").keys(keyId, secret).scope("s3://items/");
 * try (Quack db = Quack.builder(path).objectStore(store).open()) {
 *     db.exportTable("reading", "s3://items/reading.parquet");
 *     db.query("SELECT count(*) FROM read_parquet('s3://items/*.parquet')").scalar(Long.class);
 * }
 * </pre>
 *
 * <p>When the database opens, DuckDB's {@code httpfs} extension is loaded (installed first if it is
 * not there, which needs the network once) and the store becomes a DuckDB secret. The secret's text
 * is run on the database's own connection, never through the pool, so it is in no metric and no
 * error message; {@code duckdb_secrets()} shows it redacted. A database opened with
 * {@code enable_external_access} off or {@code lock_configuration} on - such as with
 * {@code Hardening.options().properties()} - gets those two last, after the store is set up, since
 * DuckDB loads no extension once external access is off.</p>
 *
 * <p><b>An allowed {@code s3://} folder is not a boundary on its own.</b> Measured on DuckDB 2.0.0:
 * with {@code allowed_directories} set to {@code ['s3://items/']}, reading
 * {@code s3://items/../other/a.csv} is let through - the URL is checked as written - and DuckDB then
 * requests {@code /other/a.csv}. It signs the path as written, so a server that checks signatures
 * (cairn, measured) refuses the request; one that does not, or a store with no keys, serves it. SQL
 * can build such a string at run time, so no check of the text can stop it. The boundary is the
 * keys and the server: give a database that runs SQL it did not write keys that the server lets
 * read only what it should.</p>
 */
public final class ObjectStore {

    private final String endpoint;
    private final boolean ssl;
    private final String keyId;
    private final String secret;
    private final String region;
    private final String scope;
    private final Boolean pathStyle;

    private ObjectStore(String endpoint, boolean ssl, String keyId, String secret, String region, String scope,
                        Boolean pathStyle) {
        this.endpoint = endpoint;
        this.ssl = ssl;
        this.keyId = keyId;
        this.secret = secret;
        this.region = region;
        this.scope = scope;
        this.pathStyle = pathStyle;
    }

    /**
     * A server that speaks S3, at a URL such as {@code http://localhost:9000} or
     * {@code https://storage.example.net}. Buckets are addressed by path, which is what such servers
     * expect; see {@link #pathStyle(boolean)}.
     */
    public static ObjectStore s3(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a URL: " + endpoint, e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("An endpoint is a URL starting http:// or https://, such as"
                    + " http://localhost:9000: " + endpoint);
        }
        String hostAndPort = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        return new ObjectStore(hostAndPort, "https".equalsIgnoreCase(scheme), null, null, null, null, true);
    }

    /** AWS S3 itself, in a region such as {@code eu-west-1}. */
    public static ObjectStore aws(String region) {
        return new ObjectStore(null, true, null, null, region, null, null);
    }

    /** The access key and its secret. Without them, requests go unsigned: for public buckets. */
    public ObjectStore keys(String keyId, String secret) {
        if (keyId == null || keyId.isEmpty() || secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Both a key id and a secret are needed");
        }
        return new ObjectStore(endpoint, ssl, keyId, secret, region, scope, pathStyle);
    }

    /** The region requests are signed for. Servers other than AWS mostly accept any; the default is DuckDB's. */
    public ObjectStore region(String region) {
        return new ObjectStore(endpoint, ssl, keyId, secret, region, scope, pathStyle);
    }

    /**
     * The locations this store answers for, such as {@code s3://items/}: with several stores, each
     * {@code s3://} location goes to the one with the longest scope it starts with. Without a scope,
     * a store answers for every location no other store claims.
     */
    public ObjectStore scope(String prefix) {
        if (prefix == null || !prefix.startsWith("s3://")) {
            throw new IllegalArgumentException("A scope is an s3:// location, such as s3://items/: " + prefix);
        }
        return new ObjectStore(endpoint, ssl, keyId, secret, region, prefix, pathStyle);
    }

    /** Buckets by path ({@code host/bucket/key}) rather than by name ({@code bucket.host/key}). */
    public ObjectStore pathStyle(boolean byPath) {
        return new ObjectStore(endpoint, ssl, keyId, secret, region, scope, byPath);
    }

    /**
     * Loads httpfs and creates a secret per store, on the database's own connection. The secret's
     * text holds the key, so a failure is reported without it, and without DuckDB's exception,
     * whose message could quote it.
     */
    static void prepare(Connection root, List<ObjectStore> stores) {
        loadHttpfs(root);
        for (int i = 0; i < stores.size(); i++) {
            ObjectStore store = stores.get(i);
            try (Statement statement = root.createStatement()) {
                statement.execute(store.secretSql("quack_store_" + (i + 1)));
            }
            catch (SQLException e) {
                String reason = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
                if (store.secret != null) {
                    reason = reason.replace(store.secret, "***");
                }
                throw new IllegalStateException("Could not set up the object store " + store + ": " + reason);
            }
        }
    }

    private static void loadHttpfs(Connection root) {
        try (Statement statement = root.createStatement()) {
            statement.execute("LOAD httpfs");
            return;
        }
        catch (SQLException notInstalled) {
            // Installed once per machine, from DuckDB's extension repository.
        }
        try (Statement statement = root.createStatement()) {
            statement.execute("INSTALL httpfs");
            statement.execute("LOAD httpfs");
        }
        catch (SQLException e) {
            throw new IllegalStateException("Could not load DuckDB's httpfs extension, which reads and writes"
                    + " s3:// locations: " + e.getMessage(), e);
        }
    }

    String secretSql(String name) {
        StringBuilder sql = new StringBuilder("CREATE SECRET ").append(Sql.quote(name)).append(" (TYPE s3");
        if (keyId != null) {
            sql.append(", KEY_ID ").append(literal(keyId)).append(", SECRET ").append(literal(secret));
        }
        if (region != null) {
            sql.append(", REGION ").append(literal(region));
        }
        if (endpoint != null) {
            sql.append(", ENDPOINT ").append(literal(endpoint)).append(", USE_SSL ").append(ssl);
        }
        if (pathStyle != null) {
            sql.append(", URL_STYLE ").append(pathStyle ? "'path'" : "'vhost'");
        }
        if (scope != null) {
            sql.append(", SCOPE ").append(literal(scope));
        }
        return sql.append(')').toString();
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @Override
    public String toString() {
        return "ObjectStore[" + (endpoint == null ? "AWS" : (ssl ? "https://" : "http://") + endpoint)
                + (region == null ? "" : ", region " + region)
                + (keyId == null ? ", unsigned" : ", key " + keyId)
                + (scope == null ? "" : ", for " + scope) + "]";
    }
}
