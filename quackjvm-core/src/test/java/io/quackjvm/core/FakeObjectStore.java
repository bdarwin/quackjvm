package io.quackjvm.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Just enough of S3 for DuckDB's httpfs, in memory, for tests: PUT, multipart upload, HEAD, GET with
 * a range, DELETE and ListObjectsV2, path style. It checks no signatures, but records the
 * authorization header's access key, so a test can see which secret DuckDB used.
 */
final class FakeObjectStore implements AutoCloseable {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final String LAST_MODIFIED = HTTP_DATE.format(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

    /** "bucket/key" to bytes. */
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final Map<String, String> accessKeys = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();
    private final Map<String, Map<Integer, byte[]>> uploads = new ConcurrentHashMap<>();
    /** How many multipart uploads were started. */
    final AtomicInteger uploadIds = new AtomicInteger();
    private final HttpServer server;

    FakeObjectStore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    /** As {@code ObjectStore.s3} takes it. */
    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8).substring(1);
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.contains("Credential=")) {
                String key = authorization.substring(authorization.indexOf("Credential=") + 11);
                accessKeys.put(path, key.substring(0, key.indexOf('/')));
            }
            byte[] body = readAll(exchange.getRequestBody());
            switch (exchange.getRequestMethod()) {
                case "PUT" -> put(exchange, path, query, body);
                case "POST" -> post(exchange, path, query, body);
                case "HEAD" -> head(exchange, path);
                case "GET" -> get(exchange, path, query);
                case "DELETE" -> {
                    objects.remove(path);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private void put(HttpExchange exchange, String path, Map<String, String> query, byte[] body) throws IOException {
        if (query.containsKey("uploadId")) {
            uploads.get(query.get("uploadId")).put(Integer.parseInt(query.get("partNumber")), body);
        }
        else {
            objects.put(path, body);
        }
        exchange.getResponseHeaders().set("ETag", etag(body));
        exchange.sendResponseHeaders(200, -1);
    }

    private void post(HttpExchange exchange, String path, Map<String, String> query, byte[] body) throws IOException {
        String bucket = path.substring(0, path.indexOf('/'));
        String key = path.substring(path.indexOf('/') + 1);
        if (query.containsKey("uploads")) {
            String id = "upload-" + uploadIds.incrementAndGet();
            uploads.put(id, new TreeMap<>());
            xml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + escape(key)
                    + "</Key><UploadId>" + id + "</UploadId></InitiateMultipartUploadResult>");
            return;
        }
        Map<Integer, byte[]> parts = uploads.remove(query.get("uploadId"));
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        for (byte[] part : parts.values()) {
            whole.write(part);
        }
        objects.put(path, whole.toByteArray());
        xml(exchange, 200, "<CompleteMultipartUploadResult><Bucket>" + bucket + "</Bucket><Key>" + escape(key)
                + "</Key><ETag>" + etag(whole.toByteArray()) + "</ETag></CompleteMultipartUploadResult>");
    }

    private void head(HttpExchange exchange, String path) throws IOException {
        byte[] object = objects.get(path);
        if (object == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        headers(exchange, object);
        exchange.getResponseHeaders().set("Content-Length", Long.toString(object.length));
        exchange.sendResponseHeaders(200, -1);
    }

    private void get(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        if (query.containsKey("list-type")) {
            list(exchange, path.endsWith("/") ? path.substring(0, path.length() - 1) : path, query);
            return;
        }
        byte[] object = objects.get(path);
        if (object == null) {
            xml(exchange, 404, "<Error><Code>NoSuchKey</Code><Message>No such key</Message></Error>");
            return;
        }
        headers(exchange, object);
        String range = exchange.getRequestHeaders().getFirst("Range");
        int from = 0;
        int to = object.length - 1;
        int status = 200;
        if (range != null && range.startsWith("bytes=")) {
            String[] ends = range.substring(6).split("-", -1);
            from = Integer.parseInt(ends[0]);
            to = ends[1].isEmpty() ? to : Math.min(to, Integer.parseInt(ends[1]));
            status = 206;
            exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + object.length);
        }
        int length = to - from + 1;
        exchange.sendResponseHeaders(status, length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(object, from, length);
        }
    }

    private void list(HttpExchange exchange, String bucket, Map<String, String> query) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        StringBuilder xml = new StringBuilder("<ListBucketResult><Name>").append(bucket).append("</Name><Prefix>")
                .append(escape(prefix)).append("</Prefix><IsTruncated>false</IsTruncated>");
        int count = 0;
        for (Map.Entry<String, byte[]> entry : new TreeMap<>(objects).entrySet()) {
            String name = entry.getKey();
            if (name.startsWith(bucket + "/") && name.substring(bucket.length() + 1).startsWith(prefix)) {
                xml.append("<Contents><Key>").append(escape(name.substring(bucket.length() + 1)))
                        .append("</Key><LastModified>2026-01-01T00:00:00.000Z</LastModified><ETag>")
                        .append(etag(entry.getValue())).append("</ETag><Size>").append(entry.getValue().length)
                        .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
                count++;
            }
        }
        xml.append("<KeyCount>").append(count).append("</KeyCount></ListBucketResult>");
        xml(exchange, 200, xml.toString());
    }

    private static void headers(HttpExchange exchange, byte[] object) {
        exchange.getResponseHeaders().set("ETag", etag(object));
        exchange.getResponseHeaders().set("Last-Modified", LAST_MODIFIED);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    }

    private static void xml(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            query.put(name, equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)) + "\"";
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
