package io.quackjvm.core;

import io.quackjvm.core.duckdb.Sql;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    // ---------- deleting, which DuckDB cannot ----------

    /**
     * The store among these that answers for an {@code s3://} location: the one with the longest
     * scope it starts with, else one with no scope - as DuckDB picks a secret. Null if none does.
     */
    static ObjectStore forLocation(List<ObjectStore> stores, String location) {
        ObjectStore best = null;
        int bestLength = -1;
        for (ObjectStore store : stores) {
            int length = store.scope == null ? 0 : location.startsWith(store.scope) ? store.scope.length() : -1;
            if (length > bestLength) {
                best = store;
                bestLength = length;
            }
        }
        return best;
    }

    /** Deletes the object at an {@code s3://bucket/key} location; one already gone counts as deleted. */
    void delete(HttpClient client, String location) throws IOException, InterruptedException {
        URI uri = url(location);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).DELETE().timeout(Duration.ofSeconds(30));
        if (keyId != null) {
            String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
            String payload = EMPTY_SHA256;
            Map<String, String> headers = new TreeMap<>();
            headers.put("host", uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()));
            headers.put("x-amz-content-sha256", payload);
            headers.put("x-amz-date", amzDate);
            request.header("x-amz-content-sha256", payload).header("x-amz-date", amzDate)
                    .header("Authorization", authorization("DELETE", uri.getRawPath(), headers, payload, keyId, secret,
                            region == null ? "us-east-1" : region, amzDate));
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status / 100 != 2 && status != 404) {
            String body = response.body() == null ? "" : response.body();
            throw new IOException("Could not delete " + location + " from " + this + ": HTTP " + status + " "
                    + body.substring(0, Math.min(200, body.length())).replace('\n', ' '));
        }
    }

    /** Where an {@code s3://bucket/key} location is, over HTTP, for this store. */
    URI url(String location) {
        if (!location.startsWith("s3://")) {
            throw new IllegalArgumentException("Not an s3:// location: " + location);
        }
        String rest = location.substring("s3://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("Not an object's location: " + location);
        }
        String bucket = rest.substring(0, slash);
        String key = encodePath(rest.substring(slash + 1));
        String scheme = ssl ? "https://" : "http://";
        String host = endpoint != null ? endpoint : "s3." + (region == null ? "us-east-1" : region) + ".amazonaws.com";
        boolean byPath = pathStyle != null ? pathStyle : endpoint != null;
        return URI.create(byPath ? scheme + host + "/" + bucket + "/" + key : scheme + bucket + "." + host + "/" + key);
    }

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * An AWS Signature Version 4 {@code Authorization} header for S3, for a request with no query
     * string. {@code headers} holds every header signed, by lower-case name.
     */
    static String authorization(String method, String rawPath, Map<String, String> headers, String payloadHash,
                                String keyId, String secret, String region, String amzDate) {
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : new TreeMap<>(headers).entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
            signedHeaders.append(signedHeaders.length() == 0 ? "" : ";").append(header.getKey());
        }
        String canonicalRequest = method + "\n" + rawPath + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String day = amzDate.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));
        byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return "AWS4-HMAC-SHA256 Credential=" + keyId + "/" + scope + ",SignedHeaders=" + signedHeaders
                + ",Signature=" + hex(hmac(key, toSign));
    }

    /** Each segment of a key percent-encoded as S3 expects, slashes kept. */
    static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                out.append(c);
            }
            else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public String toString() {
        return "ObjectStore[" + (endpoint == null ? "AWS" : (ssl ? "https://" : "http://") + endpoint)
                + (region == null ? "" : ", region " + region)
                + (keyId == null ? ", unsigned" : ", key " + keyId)
                + (scope == null ? "" : ", for " + scope) + "]";
    }
}
