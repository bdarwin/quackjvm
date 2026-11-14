package io.quackjvm.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough of the Iceberg REST catalog for DuckDB 2.0's iceberg extension, in memory, for tests.
 * Built from the requests DuckDB was recorded making to Lakekeeper: stage-create then a commit that
 * creates, a {@code transactions/commit} per change, loads, namespaces, drops. Commits are taken one
 * at a time, and a requirement that no longer holds is refused with 409, as a real catalog does.
 *
 * <p>Tables live under {@code s3://<bucket>/<namespace>/<table>} in a {@link FakeObjectStore}, and
 * each commit writes its metadata file there, as a catalog does.</p>
 */
final class FakeIcebergCatalog implements AutoCloseable {

    private static final String PREFIX = "wh";

    private final HttpServer server;
    private final FakeObjectStore store;
    private final String bucket;
    private final Map<String, Map<String, Object>> namespaces = new ConcurrentHashMap<>();
    /** "namespace.table" to its metadata. */
    private final Map<String, Map<String, Object>> tables = new ConcurrentHashMap<>();
    private final Map<String, String> locations = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> versions = new ConcurrentHashMap<>();
    /** Every request: "METHOD path". */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** The Authorization header of every request to the catalog proper. */
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    /** The bodies of the requests to the token endpoint, after their Authorization header. */
    final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    /** The next this many commits that add a snapshot are refused with 409, as if another writer had won. */
    final AtomicInteger conflictsToForce = new AtomicInteger();
    /** How many commits were refused with 409, forced or not. */
    final AtomicInteger conflicts = new AtomicInteger();
    /** When set, every request to the catalog must carry it as a bearer token. */
    volatile String requiredToken;
    /** What the token endpoint hands out. */
    volatile String issuedToken = "issued-token";

    FakeIcebergCatalog(FakeObjectStore store, String bucket) throws IOException {
        this.store = store;
        this.bucket = bucket;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** As {@code IcebergCatalog.rest} takes it. */
    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/catalog";
    }

    String tokenEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/tokens";
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> snapshots(String namespace, String table) {
        Map<String, Object> metadata = tables.get(namespace + "." + table);
        return metadata == null ? List.of() : List.copyOf((List<Map<String, Object>>) metadata.get("snapshots"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            requests.add(method + " " + path);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/oauth/tokens")) {
                tokenRequests.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
                reply(exchange, 200, Map.of("access_token", issuedToken, "token_type", "bearer", "expires_in", 3600L));
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(authorization == null ? "" : authorization);
            if (requiredToken != null && !("Bearer " + requiredToken).equals(authorization)) {
                error(exchange, 401, "NotAuthorizedException", "No valid token");
                return;
            }
            if (!path.startsWith("/catalog/v1/")) {
                error(exchange, 404, "NotFound", path);
                return;
            }
            String[] parts = path.substring("/catalog/v1/".length()).split("/");
            for (int i = 0; i < parts.length; i++) {
                parts[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8);
            }
            route(exchange, method, parts, body);
        }
        catch (RuntimeException e) {
            error(exchange, 500, "ServerError", String.valueOf(e));
        }
    }

    private void route(HttpExchange exchange, String method, String[] p, String body) throws IOException {
        if (p.length == 1 && p[0].equals("config")) {
            reply(exchange, 200, Map.of("defaults", Map.of(), "overrides", Map.of("prefix", PREFIX)));
            return;
        }
        if (p.length >= 2 && p[0].equals(PREFIX) && p[1].equals("transactions") && method.equals("POST")) {
            transaction(exchange, body);
            return;
        }
        if (p.length < 2 || !p[0].equals(PREFIX) || !p[1].equals("namespaces")) {
            error(exchange, 404, "NotFound", String.join("/", p));
            return;
        }
        if (p.length == 2) {
            if (method.equals("GET")) {
                List<Object> list = new ArrayList<>();
                namespaces.keySet().forEach(n -> list.add(List.of(n)));
                reply(exchange, 200, Map.of("namespaces", list));
            }
            else {
                Map<String, Object> request = Json.object(body);
                String name = (String) ((List<?>) request.get("namespace")).get(0);
                if (namespaces.putIfAbsent(name, new LinkedHashMap<>()) != null) {
                    error(exchange, 409, "AlreadyExistsException", "Namespace already exists: " + name);
                    return;
                }
                reply(exchange, 200, Map.of("namespace", List.of(name), "properties", Map.of()));
            }
            return;
        }
        String namespace = p[2];
        if (!namespaces.containsKey(namespace)) {
            error(exchange, 404, "NoSuchNamespaceException", "Namespace does not exist: " + namespace);
            return;
        }
        if (p.length == 3) {
            switch (method) {
                case "HEAD" -> exchange.sendResponseHeaders(204, -1);
                case "GET" -> reply(exchange, 200, Map.of("namespace", List.of(namespace), "properties", Map.of()));
                case "DELETE" -> {
                    namespaces.remove(namespace);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> error(exchange, 405, "MethodNotAllowed", method);
            }
            return;
        }
        if (p[3].equals("views")) {
            error(exchange, 404, "NoSuchViewException", "View does not exist");
            return;
        }
        if (!p[3].equals("tables")) {
            error(exchange, 404, "NotFound", p[3]);
            return;
        }
        if (p.length == 4) {
            if (method.equals("GET")) {
                List<Object> list = new ArrayList<>();
                tables.keySet().stream().filter(k -> k.startsWith(namespace + "."))
                        .forEach(k -> list.add(Map.of("namespace", List.of(namespace), "name", k.substring(namespace.length() + 1))));
                reply(exchange, 200, Map.of("identifiers", list));
            }
            else {
                create(exchange, namespace, Json.object(body));
            }
            return;
        }
        String table = p[4];
        String key = namespace + "." + table;
        switch (method) {
            case "GET", "HEAD" -> {
                Map<String, Object> metadata = tables.get(key);
                if (metadata == null) {
                    error(exchange, 404, "NoSuchTableException", "Table does not exist: " + key);
                }
                else if (method.equals("HEAD")) {
                    exchange.sendResponseHeaders(204, -1);
                }
                else {
                    reply(exchange, 200, loaded(key));
                }
            }
            case "DELETE" -> {
                synchronized (this) {
                    tables.remove(key);
                    locations.remove(key);
                }
                exchange.sendResponseHeaders(204, -1);
            }
            case "POST" -> {
                Map<String, Object> change = Json.object(body);
                change.put("identifier", Map.of("namespace", List.of(namespace), "name", table));
                commit(exchange, List.of(change));
            }
            default -> error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    @SuppressWarnings("unchecked")
    private void create(HttpExchange exchange, String namespace, Map<String, Object> request) throws IOException {
        String name = (String) request.get("name");
        String key = namespace + "." + name;
        if (tables.containsKey(key)) {
            error(exchange, 409, "AlreadyExistsException", "Table already exists: " + key);
            return;
        }
        Map<String, Object> schema = (Map<String, Object>) request.get("schema");
        Map<String, Object> spec = (Map<String, Object>) request.getOrDefault("partition-spec",
                Map.of("spec-id", 0L, "fields", List.of()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format-version", 2L);
        metadata.put("table-uuid", UUID.randomUUID().toString());
        metadata.put("location", "s3://" + bucket + "/" + namespace + "/" + name);
        metadata.put("last-sequence-number", 0L);
        metadata.put("last-updated-ms", System.currentTimeMillis());
        metadata.put("last-column-id", maxId(schema));
        metadata.put("schemas", new ArrayList<>(List.of(schema)));
        metadata.put("current-schema-id", schema.getOrDefault("schema-id", 0L));
        Map<String, Object> cleanSpec = new LinkedHashMap<>();
        cleanSpec.put("spec-id", spec.getOrDefault("spec-id", 0L));
        cleanSpec.put("fields", spec.getOrDefault("fields", List.of()));
        metadata.put("partition-specs", new ArrayList<>(List.of(cleanSpec)));
        metadata.put("default-spec-id", cleanSpec.get("spec-id"));
        metadata.put("last-partition-id", maxFieldId((List<Map<String, Object>>) cleanSpec.get("fields")));
        metadata.put("properties", new LinkedHashMap<>((Map<String, Object>) request.getOrDefault("properties", Map.of())));
        metadata.put("sort-orders", new ArrayList<>(List.of(Map.of("order-id", 0L, "fields", List.of()))));
        metadata.put("default-sort-order-id", 0L);
        metadata.put("refs", new LinkedHashMap<>());
        metadata.put("snapshots", new ArrayList<>());
        metadata.put("snapshot-log", new ArrayList<>());
        metadata.put("metadata-log", new ArrayList<>());
        if (Boolean.TRUE.equals(request.get("stage-create"))) {
            Map<String, Object> staged = new LinkedHashMap<>();
            staged.put("metadata-location", null);
            staged.put("metadata", metadata);
            staged.put("config", Map.of());
            reply(exchange, 200, staged);
            return;
        }
        synchronized (this) {
            tables.put(key, metadata);
            persist(key);
        }
        reply(exchange, 200, loaded(key));
    }

    @SuppressWarnings("unchecked")
    private void transaction(HttpExchange exchange, String body) throws IOException {
        Map<String, Object> request = Json.object(body);
        commit(exchange, (List<Map<String, Object>>) request.get("table-changes"));
    }

    /** Validates every change's requirements, then applies them all, or none. */
    @SuppressWarnings("unchecked")
    private void commit(HttpExchange exchange, List<Map<String, Object>> changes) throws IOException {
        Map<String, Map<String, Object>> updated = new LinkedHashMap<>();
        synchronized (this) {
            for (Map<String, Object> change : changes) {
                Map<String, Object> identifier = (Map<String, Object>) change.get("identifier");
                String key = ((List<?>) identifier.get("namespace")).get(0) + "." + identifier.get("name");
                Map<String, Object> current = tables.get(key);
                List<Map<String, Object>> updates = (List<Map<String, Object>>) change.getOrDefault("updates", List.of());
                boolean addsSnapshot = updates.stream().anyMatch(u -> "add-snapshot".equals(u.get("action")));
                if (addsSnapshot && conflictsToForce.get() > 0 && conflictsToForce.getAndDecrement() > 0) {
                    conflicts.incrementAndGet();
                    error(exchange, 409, "CommitFailedException", "Forced: branch main has changed");
                    return;
                }
                for (Map<String, Object> requirement : (List<Map<String, Object>>) change.getOrDefault("requirements", List.of())) {
                    String failure = check(requirement, current);
                    if (failure != null) {
                        conflicts.incrementAndGet();
                        error(exchange, 409, "CommitFailedException", "Requirement failed: " + failure);
                        return;
                    }
                }
                Map<String, Object> next = current == null ? fresh() : (Map<String, Object>) Json.parse(Json.write(current));
                if (current == null) {
                    String[] parts = key.split("\\.", 2);
                    next.put("location", "s3://" + bucket + "/" + parts[0] + "/" + parts[1]);
                }
                for (Map<String, Object> update : updates) {
                    String failure = apply(update, next);
                    if (failure != null) {
                        error(exchange, 400, "BadRequestException", failure);
                        return;
                    }
                }
                next.put("last-updated-ms", System.currentTimeMillis());
                updated.put(key, next);
            }
            for (Map.Entry<String, Map<String, Object>> table : updated.entrySet()) {
                tables.put(table.getKey(), table.getValue());
                persist(table.getKey());
            }
        }
        if (updated.size() == 1 && changes.size() == 1 && exchange.getRequestURI().getPath().contains("/tables/")) {
            reply(exchange, 200, loaded(updated.keySet().iterator().next()));
        }
        else {
            exchange.sendResponseHeaders(204, -1);
        }
    }

    @SuppressWarnings("unchecked")
    private static String check(Map<String, Object> requirement, Map<String, Object> current) {
        String type = (String) requirement.get("type");
        if (type.equals("assert-create")) {
            return current == null ? null : "table already exists";
        }
        if (current == null) {
            return "table does not exist";
        }
        switch (type) {
            case "assert-table-uuid" -> {
                return requirement.get("uuid").equals(current.get("table-uuid")) ? null : "table UUID changed";
            }
            case "assert-ref-snapshot-id" -> {
                Map<String, Object> ref = (Map<String, Object>) ((Map<String, Object>) current.get("refs")).get(requirement.get("ref"));
                Object expected = requirement.get("snapshot-id");
                Object actual = ref == null ? null : ref.get("snapshot-id");
                return java.util.Objects.equals(expected, actual) ? null
                        : "branch " + requirement.get("ref") + " has changed: expected id " + expected + " != " + actual;
            }
            case "assert-current-schema-id" -> {
                return requirement.get("current-schema-id").equals(current.get("current-schema-id")) ? null : "schema changed";
            }
            case "assert-last-assigned-field-id" -> {
                return requirement.get("last-assigned-field-id").equals(current.get("last-column-id")) ? null : "columns changed";
            }
            case "assert-default-spec-id" -> {
                return requirement.get("default-spec-id").equals(current.get("default-spec-id")) ? null : "spec changed";
            }
            case "assert-last-assigned-partition-id" -> {
                return requirement.get("last-assigned-partition-id").equals(current.get("last-partition-id")) ? null : "partitions changed";
            }
            case "assert-default-sort-order-id" -> {
                return requirement.get("default-sort-order-id").equals(current.get("default-sort-order-id")) ? null : "sort order changed";
            }
            default -> {
                return "unknown requirement " + type;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static String apply(Map<String, Object> update, Map<String, Object> m) {
        String action = (String) update.get("action");
        switch (action) {
            case "assign-uuid" -> m.put("table-uuid", update.get("uuid"));
            case "upgrade-format-version" -> m.put("format-version", update.get("format-version"));
            case "add-schema" -> {
                Map<String, Object> schema = (Map<String, Object>) update.get("schema");
                ((List<Object>) m.get("schemas")).removeIf(s -> ((Map<String, Object>) s).get("schema-id").equals(schema.get("schema-id")));
                ((List<Object>) m.get("schemas")).add(schema);
                m.put("last-column-id", Math.max((Long) m.getOrDefault("last-column-id", 0L),
                        (Long) update.getOrDefault("last-column-id", maxId(schema))));
                m.put("last-added-schema-id", schema.get("schema-id"));
            }
            case "set-current-schema" -> {
                Object id = update.get("schema-id");
                m.put("current-schema-id", Long.valueOf(-1).equals(id) ? m.get("last-added-schema-id") : id);
            }
            case "add-spec" -> {
                Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) update.get("spec"));
                spec.putIfAbsent("spec-id", (long) ((List<?>) m.get("partition-specs")).size());
                ((List<Object>) m.get("partition-specs")).removeIf(s -> ((Map<String, Object>) s).get("spec-id").equals(spec.get("spec-id")));
                ((List<Object>) m.get("partition-specs")).add(spec);
                m.put("last-partition-id", Math.max((Long) m.getOrDefault("last-partition-id", 999L),
                        maxFieldId((List<Map<String, Object>>) spec.get("fields"))));
                m.put("last-added-spec-id", spec.get("spec-id"));
            }
            case "set-default-spec" -> {
                Object id = update.get("spec-id");
                m.put("default-spec-id", Long.valueOf(-1).equals(id) ? m.get("last-added-spec-id") : id);
            }
            case "add-sort-order" -> {
                Map<String, Object> order = (Map<String, Object>) update.get("sort-order");
                ((List<Object>) m.get("sort-orders")).removeIf(s -> ((Map<String, Object>) s).get("order-id").equals(order.get("order-id")));
                ((List<Object>) m.get("sort-orders")).add(order);
                m.put("last-added-sort-order-id", order.get("order-id"));
            }
            case "set-default-sort-order" -> {
                Object id = update.get("sort-order-id");
                m.put("default-sort-order-id", Long.valueOf(-1).equals(id) ? m.get("last-added-sort-order-id") : id);
            }
            case "set-location" -> m.put("location", update.get("location"));
            case "set-properties" -> ((Map<String, Object>) m.get("properties")).putAll((Map<String, Object>) update.get("updates"));
            case "remove-properties" -> ((List<?>) update.get("removals")).forEach(((Map<String, Object>) m.get("properties"))::remove);
            case "add-snapshot" -> {
                Map<String, Object> snapshot = (Map<String, Object>) update.get("snapshot");
                ((List<Object>) m.get("snapshots")).add(snapshot);
                m.put("last-sequence-number", Math.max((Long) m.get("last-sequence-number"),
                        (Long) snapshot.getOrDefault("sequence-number", 0L)));
            }
            case "set-snapshot-ref" -> {
                String ref = (String) update.get("ref-name");
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("snapshot-id", update.get("snapshot-id"));
                value.put("type", update.get("type"));
                ((Map<String, Object>) m.get("refs")).put(ref, value);
                if (ref.equals("main")) {
                    m.put("current-snapshot-id", update.get("snapshot-id"));
                    ((List<Object>) m.get("snapshot-log")).add(Map.of("snapshot-id", update.get("snapshot-id"),
                            "timestamp-ms", System.currentTimeMillis()));
                }
            }
            case "remove-snapshot-ref" -> ((Map<String, Object>) m.get("refs")).remove(update.get("ref-name"));
            case "remove-snapshots" -> {
                List<?> ids = (List<?>) update.get("snapshot-ids");
                ((List<Object>) m.get("snapshots")).removeIf(s -> ids.contains(((Map<String, Object>) s).get("snapshot-id")));
                ((List<Object>) m.get("snapshot-log")).removeIf(s -> ids.contains(((Map<String, Object>) s).get("snapshot-id")));
            }
            default -> {
                return "unknown update " + action;
            }
        }
        return null;
    }

    /** Writes the table's metadata to a new file in the store, and logs the one it replaces. */
    @SuppressWarnings("unchecked")
    private void persist(String key) {
        Map<String, Object> metadata = tables.get(key);
        String previous = locations.get(key);
        if (previous != null) {
            ((List<Object>) metadata.get("metadata-log")).add(Map.of("metadata-file", previous,
                    "timestamp-ms", System.currentTimeMillis()));
        }
        int version = versions.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement();
        String location = metadata.get("location") + "/metadata/" + String.format("%05d", version) + "-"
                + UUID.randomUUID() + ".metadata.json";
        Map<String, Object> written = new LinkedHashMap<>(metadata);
        written.keySet().removeIf(k -> k.startsWith("last-added-"));
        store.objects.put(location.substring("s3://".length()), Json.write(written).getBytes(StandardCharsets.UTF_8));
        store.written.put(location.substring("s3://".length()), System.currentTimeMillis());
        locations.put(key, location);
    }

    private Map<String, Object> loaded(String key) {
        Map<String, Object> metadata = new LinkedHashMap<>(tables.get(key));
        metadata.keySet().removeIf(k -> k.startsWith("last-added-"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metadata-location", locations.get(key));
        response.put("metadata", metadata);
        response.put("config", Map.of());
        return response;
    }

    private static Map<String, Object> fresh() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format-version", 2L);
        metadata.put("table-uuid", UUID.randomUUID().toString());
        metadata.put("last-sequence-number", 0L);
        metadata.put("last-column-id", 0L);
        metadata.put("schemas", new ArrayList<>());
        metadata.put("current-schema-id", 0L);
        metadata.put("partition-specs", new ArrayList<>());
        metadata.put("default-spec-id", 0L);
        metadata.put("last-partition-id", 999L);
        metadata.put("properties", new LinkedHashMap<>());
        metadata.put("sort-orders", new ArrayList<>());
        metadata.put("default-sort-order-id", 0L);
        metadata.put("refs", new LinkedHashMap<>());
        metadata.put("snapshots", new ArrayList<>());
        metadata.put("snapshot-log", new ArrayList<>());
        metadata.put("metadata-log", new ArrayList<>());
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static long maxId(Object node) {
        long max = 0;
        if (node instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (id instanceof Long l) {
                max = l;
            }
            for (Object value : map.values()) {
                max = Math.max(max, maxId(value));
            }
        }
        else if (node instanceof List<?> list) {
            for (Object value : list) {
                max = Math.max(max, maxId(value));
            }
        }
        return max;
    }

    private static long maxFieldId(List<Map<String, Object>> fields) {
        long max = 999;
        for (Map<String, Object> field : fields) {
            Object id = field.get("field-id");
            if (id instanceof Long l) {
                max = Math.max(max, l);
            }
        }
        return max;
    }

    private static void error(HttpExchange exchange, int code, String type, String message) throws IOException {
        reply(exchange, code, Map.of("error", Map.of("message", message, "type", type, "code", (long) code)));
    }

    private static void reply(HttpExchange exchange, int code, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
