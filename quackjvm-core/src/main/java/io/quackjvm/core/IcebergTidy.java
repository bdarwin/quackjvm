package io.quackjvm.core;

import io.quackjvm.core.duckdb.Sql;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Quack#tidy}: merges a table's small files, removes its old snapshots, and deletes the files
 * no remaining snapshot refers to. Measured on DuckDB 2.0.0 with Lakekeeper: DuckDB merges files
 * ({@code iceberg_rewrite_data_files}) but removes no snapshot, Lakekeeper removes a snapshot when
 * a commit asks it to but deletes no file, and nothing else in the stack does either.
 */
final class IcebergTidy {

    /** Table properties that put files outside the table's own folders, where listing would not find them all. */
    private static final List<String> ELSEWHERE = List.of("write.data.path", "write.metadata.path",
            "write.object-storage.enabled", "write.folder-storage.path");

    private final Connection connection;
    private final IcebergCatalog catalog;
    private final List<ObjectStore> stores;
    private final String catalogName;
    private final String namespace;
    private final String table;
    private final String ident;
    private final HttpClient http;

    IcebergTidy(Connection connection, IcebergCatalog catalog, List<ObjectStore> stores, String catalogName,
                String namespace, String table) {
        this.connection = connection;
        this.catalog = catalog;
        this.stores = stores;
        this.catalogName = catalogName;
        this.namespace = namespace;
        this.table = table;
        this.ident = Sql.quote(catalogName) + "." + Sql.quote(namespace) + "." + Sql.quote(table);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    Tidied tidy(Duration keepFor) throws SQLException, IOException, InterruptedException {
        long cutoff = System.currentTimeMillis() - keepFor.toMillis();
        long[] merged = retry(this::merge);
        int removed = retry(() -> removeSnapshots(cutoff));
        long[] deleted = deleteUnreferenced(cutoff);
        return new Tidied(merged[0], merged[1], removed, (int) deleted[0], deleted[1], (int) deleted[2]);
    }

    // ---------- 1. merging small files ----------

    private long[] merge() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT rewritten_data_files, added_data_files"
                     + " FROM iceberg_rewrite_data_files(" + literal(catalogName + "." + namespace + "." + table) + ")")) {
            long merged = 0;
            long written = 0;
            while (rows.next()) {
                merged += rows.getLong(1);
                written += rows.getLong(2);
            }
            return new long[]{merged, written};
        }
    }

    // ---------- 2. removing old snapshots ----------

    private int removeSnapshots(long cutoff) throws SQLException, IOException, InterruptedException {
        Set<Long> kept = new HashSet<>(longs("SELECT snapshot_id FROM iceberg_refs(" + ident + ")"));
        Long main = first("SELECT snapshot_id FROM iceberg_refs(" + ident + ") WHERE name = 'main'");
        List<Long> old = new ArrayList<>();
        for (Long id : longs("SELECT snapshot_id::BIGINT FROM iceberg_snapshots(" + ident + ")"
                + " WHERE epoch_ms(timestamp_ms) < " + cutoff)) {
            if (!kept.contains(id)) {
                old.add(id);
            }
        }
        if (old.isEmpty() || main == null) {
            return 0;
        }
        String uuid = string("SELECT json_extract_string(metadata::JSON, '$.\"table-uuid\"')"
                + " FROM iceberg_load_table_response(" + ident + ")");
        StringBuilder body = new StringBuilder("{\"requirements\":[{\"type\":\"assert-table-uuid\",\"uuid\":")
                .append(jsonString(uuid))
                .append("},{\"type\":\"assert-ref-snapshot-id\",\"ref\":\"main\",\"snapshot-id\":").append(main)
                .append("}],\"updates\":[{\"action\":\"remove-snapshots\",\"snapshot-ids\":[");
        for (int i = 0; i < old.size(); i++) {
            body.append(i == 0 ? "" : ",").append(old.get(i));
        }
        body.append("]}]}");
        HttpResponse<String> response = send(HttpRequest.newBuilder(tableUri())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (response.statusCode() == 409) {
            throw new CommitConflict("Removing old snapshots of " + catalogName + "." + namespace + "." + table
                    + " conflicted with another commit: Iceberg Conflict_409", null);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("The catalog refused to remove old snapshots of " + catalogName + "."
                    + namespace + "." + table + ": HTTP " + response.statusCode() + " " + shortly(response.body()));
        }
        return old.size();
    }

    // ---------- 3 and 4: what is still referred to, and deleting the rest ----------

    private long[] deleteUnreferenced(long cutoff) throws SQLException, IOException, InterruptedException {
        String location = string("SELECT json_extract_string(metadata::JSON, '$.location')"
                + " FROM iceberg_load_table_response(" + ident + ")");
        String properties = string("SELECT coalesce(json_extract_string(metadata::JSON, '$.properties'), '{}')"
                + " FROM iceberg_load_table_response(" + ident + ")");
        if (location == null || location.isEmpty() || ELSEWHERE.stream().anyMatch(properties::contains)) {
            return new long[]{0, 0, 0};
        }
        String root = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;

        // Listed first, then what is referred to: a file committed in between is then either not
        // listed, or referred to - never listed and taken for unreferenced.
        Map<String, long[]> listed = new LinkedHashMap<>();
        for (String folder : List.of("/data/", "/metadata/")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT filename, size, epoch_ms(last_modified) FROM read_blob(?, allow_empty => true)")) {
                statement.setString(1, root + folder + "**");
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        listed.put(rows.getString(1), new long[]{rows.getLong(2), rows.getLong(3)});
                    }
                }
            }
        }
        Set<String> referred = referred();
        if (referred.isEmpty() && !listed.isEmpty()) {
            throw new IllegalStateException("Found no file referred to by " + catalogName + "." + namespace + "."
                    + table + ", yet " + listed.size() + " in its folders: deleting nothing");
        }

        long deleted = 0;
        long bytes = 0;
        long kept = 0;
        for (Map.Entry<String, long[]> file : listed.entrySet()) {
            String path = file.getKey();
            if (referred.contains(normal(path))) {
                continue;
            }
            if (file.getValue()[1] >= cutoff || !delete(path)) {
                kept++;
                continue;
            }
            deleted++;
            bytes += file.getValue()[0];
        }
        return new long[]{deleted, bytes, kept};
    }

    /** Every file the table's remaining snapshots and metadata refer to. */
    private Set<String> referred() throws SQLException {
        Set<String> referred = new HashSet<>();
        String response = "FROM iceberg_load_table_response(" + ident + ")";
        add(referred, strings("SELECT metadata_location " + response));
        add(referred, strings("SELECT unnest(json_extract_string(metadata::JSON, '$.\"metadata-log\"[*].\"metadata-file\"')) " + response));
        add(referred, strings("SELECT unnest(json_extract_string(metadata::JSON, '$.statistics[*].\"statistics-path\"')) " + response));
        add(referred, strings("SELECT unnest(json_extract_string(metadata::JSON, '$.\"partition-statistics\"[*].\"statistics-path\"')) " + response));
        add(referred, strings("SELECT manifest_list FROM iceberg_snapshots(" + ident + ")"));
        for (Long snapshot : longs("SELECT snapshot_id::BIGINT FROM iceberg_snapshots(" + ident + ")")) {
            add(referred, strings("SELECT path FROM iceberg_manifests(" + ident + ", snapshot_from_id => " + snapshot + ")"));
            // A DELETED entry records a file this snapshot removed; it is no longer part of the table.
            add(referred, strings("SELECT file_path FROM iceberg_metadata(" + ident + ", snapshot_from_id => " + snapshot
                    + ") WHERE status <> 'DELETED'"));
        }
        return referred;
    }

    private boolean delete(String path) throws IOException, InterruptedException {
        if (path.startsWith("s3://")) {
            ObjectStore store = ObjectStore.forLocation(stores, path);
            if (store == null) {
                return false;
            }
            store.delete(http, path);
            return true;
        }
        if (path.startsWith("file:")) {
            Files.deleteIfExists(Path.of(URI.create(path)));
            return true;
        }
        if (path.startsWith("/")) {
            Files.deleteIfExists(Path.of(path));
            return true;
        }
        return false;
    }

    private static void add(Set<String> into, List<String> paths) {
        for (String path : paths) {
            if (path != null) {
                into.add(normal(path));
            }
        }
    }

    /** The same object, however its scheme was spelled. */
    private static String normal(String path) {
        if (path.startsWith("s3a://") || path.startsWith("s3n://")) {
            return "s3://" + path.substring(6);
        }
        return path;
    }

    // ---------- the catalog's REST API ----------

    private URI tableUri() throws IOException, InterruptedException, SQLException {
        String query = catalog.warehouse().isEmpty() ? "" : "?warehouse=" + encode(catalog.warehouse());
        HttpResponse<String> config = send(HttpRequest.newBuilder(URI.create(catalog.endpoint() + "/v1/config" + query)).GET());
        if (config.statusCode() / 100 != 2) {
            throw new IllegalStateException("The catalog " + catalog + " answered HTTP " + config.statusCode()
                    + " for its configuration: " + shortly(config.body()));
        }
        String base = json(config.body(), "$.overrides.uri");
        String prefix = json(config.body(), "$.overrides.prefix");
        if (prefix == null) {
            prefix = json(config.body(), "$.defaults.prefix");
        }
        String root = base == null || base.isEmpty() ? catalog.endpoint().toString() : base.replaceAll("/+$", "");
        return URI.create(root + "/v1" + (prefix == null || prefix.isEmpty() ? "" : "/" + encode(prefix))
                + "/namespaces/" + encode(namespace) + "/tables/" + encode(table));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException, SQLException {
        String bearer = bearer();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String bearer() throws IOException, InterruptedException, SQLException {
        if (catalog.token() != null) {
            return catalog.token();
        }
        if (catalog.clientId() == null) {
            return null;
        }
        // As DuckDB asks for its own token, so that a token endpoint that gives DuckDB one gives this one.
        String form = "grant_type=client_credentials&scope=" + encode("PRINCIPAL_ROLE:ALL");
        String basic = java.util.Base64.getEncoder().encodeToString((encode(catalog.clientId()) + ":"
                + encode(catalog.clientSecret())).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(catalog.tokenUri()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        String token = response.statusCode() / 100 == 2 ? json(response.body(), "$.access_token") : null;
        if (token == null) {
            throw new IllegalStateException("No token from " + catalog.tokenUri() + " for " + catalog
                    + ": HTTP " + response.statusCode());
        }
        return token;
    }

    // ---------- small things ----------

    /** Runs a step again, after a short random wait, while it conflicts with another writer's commit. */
    private <T> T retry(Step<T> step) throws SQLException, IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                return step.run();
            }
            catch (SQLException | RuntimeException e) {
                if (attempt >= catalog.retries() || !IcebergCatalog.isConflict(e)) {
                    throw e;
                }
                Thread.sleep(Quack.backoff(attempt));
            }
        }
    }

    @FunctionalInterface
    private interface Step<T> {
        T run() throws SQLException, IOException, InterruptedException;
    }

    private String json(String document, String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT json_extract_string(?::JSON, ?)")) {
            statement.setString(1, document);
            statement.setString(2, path);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private List<Long> longs(String sql) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getLong(1));
            }
        }
        return values;
    }

    private Long first(String sql) throws SQLException {
        List<Long> values = longs(sql);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private String string(String sql) throws SQLException {
        List<String> values = strings(sql);
        return values.isEmpty() ? null : values.get(0);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String shortly(String body) {
        String text = body == null ? "" : catalog.scrub(body.replace('\n', ' '));
        return text.substring(0, Math.min(300, text.length()));
    }
}
