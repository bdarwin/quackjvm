package io.quackjvm.core;

import io.quackjvm.core.duckdb.Sql;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * An Iceberg REST catalog - Lakekeeper, Polaris, Nessie, AWS Glue's or any other - whose tables SQL
 * then reads and writes under a name of its own, as {@code lake.schema.table}.
 *
 * <pre>
 * ObjectStore store = ObjectStore.s3("http://storage:9000").keys(keyId, secret);
 * IcebergCatalog lake = IcebergCatalog.rest("http://catalog:8181/catalog").warehouse("lake");
 * try (Quack db = Quack.builder(null).objectStore(store).catalog("lake", lake).open()) {
 *     db.execute("CREATE TABLE lake.demo.items (id BIGINT, name VARCHAR)");
 *     db.execute("INSERT INTO lake.demo.items VALUES (1, 'a')");   // retried if another node commits first
 *     db.query("SELECT count(*) FROM lake.demo.items").scalar(Long.class);
 * }
 * </pre>
 *
 * <p>The catalog records which files make up each table; the files themselves are in an object
 * store. Several databases - several machines - can write the same table: the catalog takes one
 * commit at a time, and refuses one made against a version of the table that has since moved on.
 * {@link Quack#execute} runs such a statement again; {@link Quack#inTransaction} throws
 * {@link CommitConflict}, and {@link Quack#retrying} runs the work again. Appends that meet only
 * other appends do not conflict: DuckDB applies them on top of each other.</p>
 *
 * <p>With an {@link ObjectStore} given, DuckDB reads and writes the files with that store's keys.
 * Without one, it asks the catalog for credentials, which only some catalogs hand out.</p>
 *
 * <p>Old snapshots and the files only they refer to stay until removed: {@link Quack#tidy}.</p>
 */
public final class IcebergCatalog {

    private final URI endpoint;
    private final String warehouse;
    private final String token;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUri;
    private final int retries;

    private IcebergCatalog(URI endpoint, String warehouse, String token, String clientId, String clientSecret,
                           String tokenUri, int retries) {
        this.endpoint = endpoint;
        this.warehouse = warehouse;
        this.token = token;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenUri = tokenUri;
        this.retries = retries;
    }

    /** A catalog at a URL such as {@code http://localhost:8181/catalog}: the part before {@code /v1/}. */
    public static IcebergCatalog rest(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a URL: " + endpoint, e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("A catalog is a URL starting http:// or https://, such as"
                    + " http://localhost:8181/catalog: " + endpoint);
        }
        String text = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return new IcebergCatalog(URI.create(text), "", null, null, null, null, 4);
    }

    /** Which of the catalog's warehouses, for a catalog with several; Lakekeeper and Polaris need one. */
    public IcebergCatalog warehouse(String name) {
        if (name == null) {
            throw new IllegalArgumentException("No warehouse given");
        }
        return new IcebergCatalog(endpoint, name, token, clientId, clientSecret, tokenUri, retries);
    }

    /** A bearer token sent with every request to the catalog. */
    public IcebergCatalog token(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("No token given");
        }
        return new IcebergCatalog(endpoint, warehouse, token, null, null, null, retries);
    }

    /**
     * OAuth2 client credentials: a token is fetched from {@code tokenUri} with them, and sent with
     * every request to the catalog.
     */
    public IcebergCatalog oauth2(String clientId, String clientSecret, String tokenUri) {
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()
                || tokenUri == null || tokenUri.isEmpty()) {
            throw new IllegalArgumentException("A client id, its secret and the token URL are all needed");
        }
        return new IcebergCatalog(endpoint, warehouse, null, clientId, clientSecret, tokenUri, retries);
    }

    /** How many more times a conflicting commit is tried before giving up; 4 unless set. */
    public IcebergCatalog retries(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("Retries cannot be negative: " + times);
        }
        return new IcebergCatalog(endpoint, warehouse, token, clientId, clientSecret, tokenUri, times);
    }

    URI endpoint() {
        return endpoint;
    }

    String warehouse() {
        return warehouse;
    }

    int retries() {
        return retries;
    }

    String token() {
        return token;
    }

    String clientId() {
        return clientId;
    }

    String clientSecret() {
        return clientSecret;
    }

    String tokenUri() {
        return tokenUri;
    }

    /** The folder {@code allowed_directories} must hold for a hardened database to reach the catalog. */
    String allowedFolder() {
        return endpoint + "/";
    }

    /**
     * Loads the iceberg extension and attaches the catalog, on the database's own connection. The
     * secret holds a token or a client secret, so a failure is reported without DuckDB's exception,
     * whose message could quote it.
     */
    static void attach(Connection root, String name, IcebergCatalog catalog, boolean ownKeys, int index) {
        load(root, "iceberg");
        String secret = null;
        if (catalog.token != null || catalog.clientId != null) {
            secret = "quack_catalog_" + index;
            run(root, catalog.secretSql(secret), catalog, "set up the credentials of");
        }
        StringBuilder attach = new StringBuilder("ATTACH ").append(literal(catalog.warehouse))
                .append(" AS ").append(Sql.quote(name))
                .append(" (TYPE iceberg, ENDPOINT ").append(literal(catalog.endpoint.toString()));
        if (secret == null) {
            attach.append(", AUTHORIZATION_TYPE 'none'");
        }
        else {
            attach.append(", SECRET ").append(Sql.quote(secret));
        }
        if (ownKeys) {
            attach.append(", ACCESS_DELEGATION_MODE 'none'");
        }
        run(root, attach.append(')').toString(), catalog, "attach");
    }

    String secretSql(String name) {
        StringBuilder sql = new StringBuilder("CREATE SECRET ").append(Sql.quote(name)).append(" (TYPE iceberg");
        if (token != null) {
            sql.append(", TOKEN ").append(literal(token));
        }
        else {
            sql.append(", CLIENT_ID ").append(literal(clientId))
                    .append(", CLIENT_SECRET ").append(literal(clientSecret))
                    .append(", OAUTH2_SERVER_URI ").append(literal(tokenUri));
        }
        return sql.append(')').toString();
    }

    private static void run(Connection root, String sql, IcebergCatalog catalog, String what) {
        try (Statement statement = root.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Could not " + what + " the catalog " + catalog + ": "
                    + catalog.scrub(e.getMessage()));
        }
    }

    static void load(Connection root, String extension) {
        try (Statement statement = root.createStatement()) {
            statement.execute("LOAD " + extension);
            return;
        }
        catch (SQLException notInstalled) {
            // Installed once per machine, from DuckDB's extension repository.
        }
        try (Statement statement = root.createStatement()) {
            statement.execute("INSTALL " + extension);
            statement.execute("LOAD " + extension);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Could not load DuckDB's " + extension + " extension: " + e.getMessage(), e);
        }
    }

    /** The first line of a message, with this catalog's token or client secret taken out. */
    String scrub(String message) {
        String reason = message == null ? "" : message.split("\n")[0];
        if (token != null) {
            reason = reason.replace(token, "***");
        }
        if (clientSecret != null) {
            reason = reason.replace(clientSecret, "***");
        }
        return reason;
    }

    /**
     * Whether a failure is an Iceberg commit refused because the table moved on since it was read,
     * in either of the two forms DuckDB 2.0 reports: its own check, or the catalog's 409.
     */
    static boolean isConflict(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("Iceberg")
                    && (message.contains("conflicts with a concurrent commit") || message.contains("Conflict_409"))) {
                return true;
            }
        }
        return false;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    @Override
    public String toString() {
        return "IcebergCatalog[" + endpoint + (warehouse.isEmpty() ? "" : ", warehouse " + warehouse)
                + (token != null ? ", token" : clientId != null ? ", client " + clientId : "") + "]";
    }
}
