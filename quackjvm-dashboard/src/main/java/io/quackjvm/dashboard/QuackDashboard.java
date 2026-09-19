package io.quackjvm.dashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quackjvm.core.metrics.Diagnosis;
import io.quackjvm.core.metrics.MetricsSnapshot;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.metrics.TimerSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A live web page showing where a quackjvm database spends its time and where it is choking.
 *
 * <pre>
 * try (QuackDashboard dashboard = QuackDashboard.start(database.metrics(), 8090)) {
 *     System.out.println(dashboard.url());     // http://127.0.0.1:8090/
 *     ...
 * }
 * </pre>
 *
 * <p>The page shows the {@link Diagnosis} of the last ten seconds, headline numbers with five
 * minutes of history, wait against work per collection, and the statements DuckDB spent its time
 * on over the last minute. It refreshes every second.</p>
 *
 * <p><b>Safe by default.</b> It listens on the loopback address only, and answers only requests
 * addressed to {@code localhost} or a loopback address, so a web page elsewhere cannot reach it by
 * rebinding a DNS name. It is read-only: there is no endpoint that runs SQL or changes anything.
 * Statement shapes have their literal values removed before they are recorded, so the page shows
 * no data values. Binding another address with {@link Builder#bindAddress} exposes the page to
 * that network - put it behind whatever already guards your internal tools.</p>
 *
 * <p>Runs on the JDK's own HTTP server with two daemon threads, plus one for sampling. Needs
 * nothing beyond quackjvm-core.</p>
 */
public final class QuackDashboard implements AutoCloseable {

    private static final java.util.regex.Pattern LOOPBACK_IPV4 =
            java.util.regex.Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final Set<String> STATIC_FILES = Set.of("index.html", "app.js", "app.css");

    private final QuackMetrics metrics;
    private final String title;
    private final HttpServer server;
    private final ExecutorService requestThreads;
    private final ScheduledExecutorService samplerThread;
    private final Sampler sampler;
    private final boolean loopbackOnly;
    private final long startedAt = System.currentTimeMillis();

    private QuackDashboard(Builder builder) {
        this.metrics = builder.metrics;
        this.title = builder.title;
        this.loopbackOnly = builder.bindAddress.isLoopbackAddress();
        this.sampler = new Sampler(metrics, (int) builder.history.toSeconds());
        try {
            this.server = HttpServer.create(new InetSocketAddress(builder.bindAddress, builder.port), 16);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the quackjvm dashboard on "
                    + builder.bindAddress.getHostAddress() + ":" + builder.port, e);
        }
        this.requestThreads = Executors.newFixedThreadPool(2, daemon("quackjvm-dashboard"));
        this.samplerThread = Executors.newSingleThreadScheduledExecutor(daemon("quackjvm-dashboard-sampler"));
        server.setExecutor(requestThreads);
        server.createContext("/", this::handle);
        sampler.sample();
        samplerThread.scheduleAtFixedRate(this::sampleQuietly, 1, 1, TimeUnit.SECONDS);
        server.start();
    }

    /** Starts a dashboard on the loopback address and the given port; 0 picks a free one. */
    public static QuackDashboard start(QuackMetrics metrics, int port) {
        return builder(metrics).port(port).start();
    }

    public static Builder builder(QuackMetrics metrics) {
        return new Builder(metrics);
    }

    public static final class Builder {
        private final QuackMetrics metrics;
        private int port = 8090;
        private InetAddress bindAddress = InetAddress.getLoopbackAddress();
        private String title = "quackjvm";
        private Duration history = Duration.ofMinutes(5);

        private Builder(QuackMetrics metrics) {
            if (metrics == null) {
                throw new IllegalArgumentException("A dashboard needs metrics to show");
            }
            this.metrics = metrics;
        }

        /** The port to listen on; 0 picks a free one, which {@link QuackDashboard#port()} reports. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * The address to listen on. The loopback address by default; anything else makes the page
         * reachable from that network, and switches off the check that requests are addressed to
         * localhost.
         */
        public Builder bindAddress(InetAddress bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        /** Shown at the top of the page - the application's name, say. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /** How far back the charts go. Five minutes by default. */
        public Builder history(Duration history) {
            this.history = history;
            return this;
        }

        public QuackDashboard start() {
            return new QuackDashboard(this);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        InetAddress address = server.getAddress().getAddress();
        String host = address.isLoopbackAddress() ? "127.0.0.1" : address.getHostAddress();
        return "http://" + host + ":" + port() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
        samplerThread.shutdownNow();
        requestThreads.shutdownNow();
    }

    // ---------- Requests ----------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (loopbackOnly && !addressedToLoopback(exchange.getRequestHeaders().getFirst("Host"))) {
                send(exchange, 403, "text/plain", "This dashboard only answers requests addressed to localhost.");
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                send(exchange, 405, "text/plain", "Read-only.");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("/api/state".equals(path)) {
                send(exchange, 200, "application/json", state());
                return;
            }
            String file = "/".equals(path) ? "index.html" : path.substring(1);
            if (!STATIC_FILES.contains(file)) {
                send(exchange, 404, "text/plain", "Not found.");
                return;
            }
            if (file.endsWith(".html")) {
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:;"
                        + " frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
                exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            }
            send(exchange, 200, contentType(file), resource(file));
        }
        catch (RuntimeException e) {
            send(exchange, 500, "text/plain", "The dashboard failed to answer: " + e.getClass().getSimpleName());
        }
    }

    /** Whether the Host header names this machine - the defence against DNS rebinding. */
    static boolean addressedToLoopback(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[")) {
            int close = name.indexOf(']');
            name = close < 0 ? name : name.substring(1, close);
        }
        else if (name.indexOf(':') >= 0) {
            name = name.substring(0, name.indexOf(':'));
        }
        // A numeric 127.x.x.x only: a name such as 127.example.com is anyone's to point anywhere.
        return name.equals("localhost") || name.equals("::1") || LOOPBACK_IPV4.matcher(name).matches();
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String contentType(String file) {
        if (file.endsWith(".js")) {
            return "text/javascript";
        }
        if (file.endsWith(".css")) {
            return "text/css";
        }
        return "text/html";
    }

    private static String resource(String file) {
        try (InputStream in = QuackDashboard.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("Missing dashboard resource " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void sampleQuietly() {
        try {
            sampler.sample();
        }
        catch (RuntimeException e) {
            // A failed sample leaves a gap in the charts; stopping the sampler would freeze them.
        }
    }

    // ---------- The state the page draws ----------

    /** Everything the page shows, as JSON. Package-private for the tests. */
    String state() {
        MetricsSnapshot recent = sampler.shortWindow();
        MetricsSnapshot minute = sampler.longWindow();
        Json json = new Json().object();
        json.field("title", title).field("timestamp", System.currentTimeMillis()).field("startedAt", startedAt);
        if (recent == null) {
            return json.field("warmingUp", true).end().toString();
        }
        json.field("windowSeconds", recent.getIntervalSeconds());
        json.field("longWindowSeconds", minute.getIntervalSeconds());

        List<Diagnosis.Finding> findings = Diagnosis.of(recent);
        json.key("findings").array();
        for (Diagnosis.Finding finding : findings) {
            json.object().field("cause", finding.cause().name()).field("severity", finding.severity())
                    .field("headline", finding.headline()).field("evidence", finding.evidence())
                    .field("advice", finding.advice()).end();
        }
        json.endArray();

        headline(json, recent);
        collections(json, recent);
        statements(json, minute);
        series(json, sampler.points());
        return json.end().toString();
    }

    private static void headline(Json json, MetricsSnapshot recent) {
        double seconds = Math.max(1e-9, recent.getIntervalSeconds());
        TimerSnapshot reads = Sampler.total(recent, QuackMetrics.REQUEST_READ);
        TimerSnapshot writes = Sampler.total(recent, QuackMetrics.REQUEST_WRITE);
        TimerSnapshot waits = Sampler.total(recent, QuackMetrics.WRITE_LOCK_WAIT);
        double waitAndWork = waits.totalNanos() + writes.totalNanos();
        long hits = recent.counter(QuackMetrics.PREPARE_HITS);
        long misses = recent.counter(QuackMetrics.PREPARE_MISSES);
        json.key("now").object()
                .field("readsPerSecond", reads.count() / seconds)
                .field("readP50", reads.count() == 0 ? Double.NaN : reads.percentileMillis(50))
                .field("readP99", reads.count() == 0 ? Double.NaN : reads.percentileMillis(99))
                .field("writesPerSecond", writes.count() / seconds)
                .field("writeP50", writes.count() == 0 ? Double.NaN : writes.percentileMillis(50))
                .field("writeP99", writes.count() == 0 ? Double.NaN : writes.percentileMillis(99))
                .field("lockWaitShare", waitAndWork == 0 ? 0 : waits.totalNanos() / waitAndWork)
                .field("cpu", recent.cpuUtilisation())
                .field("statementsAtOnce", recent.statementConcurrency())
                .field("heavyStatementsAtOnce", recent.statementConcurrency(Diagnosis.HEAVY_STATEMENT_MILLIS))
                .field("cores", recent.gauge(QuackMetrics.CORES))
                .field("duckdbThreads", recent.gauge(QuackMetrics.DUCKDB_THREADS))
                .field("memoryBytes", recent.gauge(QuackMetrics.DUCKDB_MEMORY))
                .field("memoryLimitBytes", recent.gauge(QuackMetrics.DUCKDB_MEMORY_LIMIT))
                .field("tempBytes", recent.gauge(QuackMetrics.DUCKDB_TEMP))
                .field("heapBytes", recent.gauge(QuackMetrics.HEAP_USED))
                .field("conflictsPerSecond", recent.counter(QuackMetrics.STATEMENT_ERRORS, "conflict") / seconds)
                .field("errorsPerSecond", recent.counterTotal(QuackMetrics.STATEMENT_ERRORS) / seconds)
                .field("connectionsInUse", recent.gauge(QuackMetrics.CONNECTIONS_IN_USE))
                .field("connectionsOpenedPerSecond", recent.counter(QuackMetrics.CONNECTIONS_OPENED) / seconds)
                .field("prepareHitRate", hits + misses == 0 ? Double.NaN : hits / (double) (hits + misses))
                .end();
    }

    private static void collections(Json json, MetricsSnapshot recent) {
        double seconds = Math.max(1e-9, recent.getIntervalSeconds());
        Map<String, TimerSnapshot> reads = recent.scopes(QuackMetrics.REQUEST_READ);
        Map<String, TimerSnapshot> writes = recent.scopes(QuackMetrics.REQUEST_WRITE);
        Map<String, TimerSnapshot> waits = recent.scopes(QuackMetrics.WRITE_LOCK_WAIT);
        Set<String> names = new TreeSet<>(reads.keySet());
        names.addAll(writes.keySet());
        names.addAll(waits.keySet());
        json.key("collections").array();
        for (String name : names) {
            TimerSnapshot read = reads.getOrDefault(name, TimerSnapshot.none(name));
            TimerSnapshot write = writes.getOrDefault(name, TimerSnapshot.none(name));
            TimerSnapshot wait = waits.getOrDefault(name, TimerSnapshot.none(name));
            if (read.count() + write.count() + wait.count() == 0) {
                continue;
            }
            double waitAndWork = wait.totalNanos() + write.totalNanos();
            json.object().field("name", name)
                    .field("readsPerSecond", read.count() / seconds)
                    .field("readP50", read.count() == 0 ? Double.NaN : read.percentileMillis(50))
                    .field("readP99", read.count() == 0 ? Double.NaN : read.percentileMillis(99))
                    .field("writesPerSecond", write.count() / seconds)
                    .field("writeP50", write.count() == 0 ? Double.NaN : write.percentileMillis(50))
                    .field("writeP99", write.count() == 0 ? Double.NaN : write.percentileMillis(99))
                    .field("lockWaitP99", wait.count() == 0 ? Double.NaN : wait.percentileMillis(99))
                    .field("waitShare", waitAndWork == 0 ? 0 : wait.totalNanos() / waitAndWork)
                    .end();
        }
        json.endArray();
    }

    private static void statements(Json json, MetricsSnapshot minute) {
        double seconds = Math.max(1e-9, minute.getIntervalSeconds());
        List<TimerSnapshot> hottest = minute.statementsByTotalTime();
        long total = 0;
        for (TimerSnapshot timer : hottest) {
            total += timer.totalNanos();
        }
        long errors = minute.counterTotal(QuackMetrics.STATEMENT_ERRORS);
        json.field("statementErrors", errors);
        json.key("statements").array();
        for (TimerSnapshot timer : hottest.subList(0, Math.min(15, hottest.size()))) {
            String name = timer.getName();
            json.object()
                    .field("shape", name.substring(QuackMetrics.STATEMENT.length() + 1, name.length() - 1))
                    .field("perSecond", timer.count() / seconds)
                    .field("count", timer.count())
                    .field("mean", timer.meanMillis())
                    .field("p99", timer.percentileMillis(99))
                    .field("share", total == 0 ? 0 : timer.totalNanos() / (double) total)
                    .end();
        }
        json.endArray();
    }

    private static void series(Json json, List<Sampler.Point> points) {
        json.key("series").object();
        json.numbers("t", points.stream().map(p -> (double) p.epochMillis()).toList());
        json.numbers("readsPerSecond", points.stream().map(Sampler.Point::readsPerSecond).toList());
        json.numbers("writesPerSecond", points.stream().map(Sampler.Point::writesPerSecond).toList());
        json.numbers("readP99", points.stream().map(Sampler.Point::readP99).toList());
        json.numbers("writeP99", points.stream().map(Sampler.Point::writeP99).toList());
        json.numbers("lockWaitShare", points.stream().map(Sampler.Point::lockWaitShare).toList());
        json.numbers("cpu", points.stream().map(Sampler.Point::cpu).toList());
        json.numbers("heavyStatements", points.stream().map(Sampler.Point::heavyStatements).toList());
        json.numbers("memoryBytes", points.stream().map(Sampler.Point::memoryBytes).toList());
        json.numbers("tempBytes", points.stream().map(Sampler.Point::tempBytes).toList());
        json.numbers("conflictsPerSecond", points.stream().map(Sampler.Point::conflictsPerSecond).toList());
        json.end();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
