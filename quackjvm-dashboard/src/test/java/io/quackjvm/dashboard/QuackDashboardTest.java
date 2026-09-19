package io.quackjvm.dashboard;

import io.quackjvm.core.metrics.QuackMetrics;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QuackDashboardTest {

    private QuackMetrics metrics;
    private QuackDashboard dashboard;

    @Before
    public void start() {
        metrics = new QuackMetrics();
        dashboard = QuackDashboard.builder(metrics).port(0).title("test").start();
    }

    @After
    public void stop() {
        dashboard.close();
    }

    @Test
    public void listensOnTheLoopbackAddressOnly() {
        assertTrue(dashboard.url(), dashboard.url().startsWith("http://127.0.0.1:"));
    }

    @Test
    public void servesThePageWithAContentSecurityPolicy() throws IOException {
        String response = get("/", "localhost");
        assertTrue(status(response), response.startsWith("HTTP/1.1 200"));
        assertTrue(response.toLowerCase().contains("content-security-policy: default-src 'self'"));
        assertTrue(response.toLowerCase().contains("x-frame-options: deny"));
        assertTrue(response.contains("<script src=\"app.js\""));
        assertTrue(get("/app.js", "localhost").startsWith("HTTP/1.1 200"));
        assertTrue(get("/app.css", "localhost").startsWith("HTTP/1.1 200"));
    }

    @Test
    public void theStateDescribesWhatWasRecorded() throws Exception {
        for (int i = 0; i < 100; i++) {
            metrics.timer(QuackMetrics.REQUEST_WRITE, "orders").record(1_000_000);
            metrics.timer(QuackMetrics.WRITE_LOCK_WAIT, "orders").record(9_000_000);
            metrics.statementTimer("DELETE FROM orders WHERE id IN (?, ?)").record(500_000);
        }
        Thread.sleep(2_200);
        String state = dashboard.state();
        assertTrue(state, state.contains("\"cause\":\"WRITE_LOCK\""));
        assertTrue(state, state.contains("\"name\":\"orders\""));
        assertTrue(state, state.contains("DELETE FROM orders WHERE id IN (?, ...)"));
        assertTrue(state, state.contains("\"series\":{\"t\":["));
        assertFalse("NaN is not JSON", state.contains("NaN"));
    }

    @Test
    public void refusesRequestsAddressedToAnotherHost() throws IOException {
        // What a DNS-rebinding attack looks like: a name the attacker controls, resolved to 127.0.0.1.
        assertTrue(get("/api/state", "attacker.example").startsWith("HTTP/1.1 403"));
        assertTrue(get("/api/state", "127.attacker.example").startsWith("HTTP/1.1 403"));
        assertTrue(get("/api/state", "127.0.0.1:" + dashboard.port()).startsWith("HTTP/1.1 200"));
        assertTrue(get("/api/state", "[::1]:" + dashboard.port()).startsWith("HTTP/1.1 200"));
    }

    @Test
    public void isReadOnly() throws IOException {
        assertTrue(request("POST", "/api/state", "localhost").startsWith("HTTP/1.1 405"));
        assertTrue(request("DELETE", "/", "localhost").startsWith("HTTP/1.1 405"));
    }

    @Test
    public void servesNothingElse() throws IOException {
        assertTrue(get("/../pom.xml", "localhost").startsWith("HTTP/1.1 404"));
        assertTrue(get("/io/quackjvm/dashboard/QuackDashboard.class", "localhost").startsWith("HTTP/1.1 404"));
        assertTrue(get("/api/sql", "localhost").startsWith("HTTP/1.1 404"));
    }

    @Test
    public void hostHeadersAreParsed() {
        assertTrue(QuackDashboard.addressedToLoopback("localhost"));
        assertTrue(QuackDashboard.addressedToLoopback("LOCALHOST:8090"));
        assertTrue(QuackDashboard.addressedToLoopback("127.0.0.1:8090"));
        assertTrue(QuackDashboard.addressedToLoopback("[::1]:8090"));
        assertFalse(QuackDashboard.addressedToLoopback(null));
        assertFalse(QuackDashboard.addressedToLoopback("127.0.0.1.nip.io"));
        assertFalse(QuackDashboard.addressedToLoopback("localhost.attacker.example"));
    }

    @Test
    public void statementTextCannotBreakOutOfJson() {
        String json = new Json().object().field("shape", "SELECT '</script><script>alert(1)</script>'\n").end().toString();
        assertFalse(json, json.contains("</script>"));
        assertFalse(json, json.contains("\n"));
    }

    // ---------- a raw HTTP client, since java.net.http will not let a test set the Host header ----------

    private String get(String path, String host) throws IOException {
        return request("GET", path, host);
    }

    private String request(String method, String path, String host) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", dashboard.port())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\r');
        return end < 0 ? response : response.substring(0, end);
    }
}
