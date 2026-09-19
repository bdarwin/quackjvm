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
import java.nio.file.Path;
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
    /** Null when not recording, or once recording has failed. Used by the sampling thread only. */
    private Recorder recorder;
    private volatile String recordingDirectory;
    private volatile String recordingError;
    private long recordedSeconds;
    /** When each statement's profile was last written to disk, so each is written once. */
    private final Map<String, java.time.Instant> recordedProfiles = new java.util.HashMap<>();
    /** Null when not watching. Used by the sampling thread only. */
    private final OtherProcesses otherProcesses;
    private volatile OtherProcessesSeen otherProcessesSeen;

    /** What was last found about other programs, for the page. */
    private record OtherProcessesSeen(long at, double othersShare, List<OtherProcesses.Busy> busy) {
    }

    private QuackDashboard(Builder builder) {
        this.metrics = builder.metrics;
        this.title = builder.title;
        this.loopbackOnly = builder.bindAddress.isLoopbackAddress();
        this.sampler = new Sampler(metrics, (int) builder.history.toSeconds());
        this.otherProcesses = builder.watchOtherProcesses ? new OtherProcesses() : null;
        if (builder.recordTo != null) {
            try {
                this.recorder = new Recorder(builder.recordTo, builder.retention);
                this.recordingDirectory = recorder.directory().toString();
            }
            catch (IOException e) {
                // The page is still worth having without the files; say why they are missing.
                this.recordingError = "Could not record to " + builder.recordTo.toAbsolutePath() + ": " + e;
            }
        }
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
        samplerThread.scheduleAtFixedRate(this::sampleAndRecord, 1, 1, TimeUnit.SECONDS);
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
        private Path recordTo = Path.of("quackjvm-metrics");
        private Duration retention = Duration.ofDays(7);
        private boolean watchOtherProcesses = true;

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

        /**
         * Where to record what the dashboard samples, as JSON Lines files that DuckDB can query:
         * {@code quackjvm-metrics} under the working directory by default. Null not to record.
         * See the class comment for what is written.
         */
        public Builder recordTo(Path directory) {
            this.recordTo = directory;
            return this;
        }

        /**
         * Whether to name the other programs on the machine when they hold a quarter of its cores
         * or more. On by default; costs nothing while the machine is not that busy. On macOS it
         * runs {@code /bin/ps}, at most every five seconds. Only names and process ids are kept.
         */
        public Builder watchOtherProcesses(boolean watch) {
            this.watchOtherProcesses = watch;
            return this;
        }

        /** How long recorded files are kept. Seven days by default. */
        public Builder retention(Duration retention) {
            this.retention = retention;
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

    /** Where the samples are being recorded, or null if they are not. */
    public Path recordingDirectory() {
        String directory = recordingDirectory;
        return directory == null ? null : Path.of(directory);
    }

    @Override
    public void close() {
        server.stop(0);
        samplerThread.shutdown();
        try {
            samplerThread.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        requestThreads.shutdownNow();
        if (recorder != null) {
            try {
                recorder.close();
            }
            catch (IOException ignored) {
                // Nothing more can be done with a file that will not close.
            }
        }
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

    /** Takes a sample now, as the sampling thread does each second. For the tests. */
    void sampleNow() {
        sampleAndRecord();
    }

    private synchronized void sampleAndRecord() {
        MetricsSnapshot second;
        try {
            second = sampler.sample();
        }
        catch (RuntimeException e) {
            // A failed sample leaves a gap in the charts; stopping the sampler would freeze them.
            return;
        }
        if (second == null) {
            return;
        }
        List<OtherProcesses.Busy> busy = lookAtOtherProcesses(second);
        if (recorder == null) {
            return;
        }
        try {
            record(second, busy);
        }
        catch (IOException | RuntimeException e) {
            // A full disk must not take the application down with it: stop recording, and say so.
            recordingError = "Recording stopped: " + e;
            try {
                recorder.close();
            }
            catch (IOException ignored) {
                // Already failing.
            }
            recorder = null;
        }
    }

    /** Names the other programs, if they hold enough of the machine; null if it did not look. */
    private List<OtherProcesses.Busy> lookAtOtherProcesses(MetricsSnapshot second) {
        if (otherProcesses == null) {
            return null;
        }
        double own = second.cpuUtilisation();
        double machine = second.gauge(QuackMetrics.CPU_MACHINE);
        double others = Double.isNaN(own) || Double.isNaN(machine) ? Double.NaN : Math.max(0, machine - own);
        List<OtherProcesses.Busy> busy = otherProcesses.lookIfBusy(others);
        if (busy != null) {
            otherProcessesSeen = new OtherProcessesSeen(System.currentTimeMillis(), others, busy);
        }
        return busy;
    }

    /** One line a second of headline numbers; every ten, the statements, collections and findings. */
    private void record(MetricsSnapshot second, List<OtherProcesses.Busy> busy) throws IOException {
        java.time.Instant at = second.getTakenAt();
        String ts = at.toString();
        Json line = new Json().object().field("ts", ts).field("seconds", second.getIntervalSeconds());
        headlineFields(line, second);
        recorder.write(Recorder.Kind.METRICS, at, line.end().toString());
        for (io.quackjvm.core.metrics.QueryProfile profile : metrics.profiles().values()) {
            if (!profile.getCapturedAt().equals(recordedProfiles.get(profile.getShape()))) {
                recordedProfiles.put(profile.getShape(), profile.getCapturedAt());
                Json row = new Json().object().field("ts", profile.getCapturedAt().toString());
                profileFields(row, profile);
                recorder.write(Recorder.Kind.PROFILES, at, row.end().toString());
            }
        }
        if (busy != null) {
            double others = otherProcessesSeen.othersShare();
            for (OtherProcesses.Busy process : busy) {
                recorder.write(Recorder.Kind.PROCESSES, at, new Json().object().field("ts", ts)
                        .field("othersShare", others).field("name", process.name())
                        .field("pid", process.pid()).field("share", process.share()).end().toString());
            }
        }
        if (++recordedSeconds % Sampler.SHORT_WINDOW == 0) {
            MetricsSnapshot window = sampler.shortWindow();
            double seconds = window.getIntervalSeconds();
            for (TimerSnapshot timer : window.statementsByTotalTime()) {
                Json row = new Json().object().field("ts", ts).field("seconds", seconds);
                statementFields(row, timer, seconds, Double.NaN);
                recorder.write(Recorder.Kind.STATEMENTS, at, row.end().toString());
            }
            forEachCollection(window, (name, fields) -> {
                Json row = new Json().object().field("ts", ts).field("seconds", seconds);
                fields.accept(row);
                recorder.write(Recorder.Kind.COLLECTIONS, at, row.end().toString());
            });
            for (Diagnosis.Finding finding : Diagnosis.of(window)) {
                Json row = new Json().object().field("ts", ts).field("seconds", seconds);
                findingFields(row, finding);
                recorder.write(Recorder.Kind.FINDINGS, at, row.end().toString());
            }
        }
        recorder.flush();
    }

    // ---------- The state the page draws ----------

    /** Everything the page shows, as JSON. Package-private for the tests. */
    String state() {
        MetricsSnapshot recent = sampler.shortWindow();
        MetricsSnapshot minute = sampler.longWindow();
        Json json = new Json().object();
        json.field("title", title).field("timestamp", System.currentTimeMillis()).field("startedAt", startedAt);
        json.key("recording").object().field("directory", recordingDirectory).field("error", recordingError).end();
        if (recent == null) {
            return json.field("warmingUp", true).end().toString();
        }
        json.field("windowSeconds", recent.getIntervalSeconds());
        json.field("longWindowSeconds", minute.getIntervalSeconds());

        List<Diagnosis.Finding> findings = Diagnosis.of(recent);
        json.key("findings").array();
        for (Diagnosis.Finding finding : findings) {
            findingFields(json.object(), finding);
            json.end();
        }
        json.endArray();

        json.key("now").object();
        headlineFields(json, recent);
        json.end();

        // What was last found about other programs, while it is still recent enough to matter.
        OtherProcessesSeen seen = otherProcessesSeen;
        json.key("otherProcesses").object().field("watching", otherProcesses != null);
        if (seen != null && System.currentTimeMillis() - seen.at() < 30_000) {
            json.field("at", seen.at()).field("othersShare", seen.othersShare()).key("processes").array();
            for (OtherProcesses.Busy process : seen.busy()) {
                json.object().field("name", process.name()).field("pid", process.pid())
                        .field("share", process.share()).end();
            }
            json.endArray();
        }
        json.end();
        collections(json, recent);
        statements(json, minute, metrics);
        series(json, sampler.points());
        return json.end().toString();
    }

    private static void findingFields(Json json, Diagnosis.Finding finding) {
        json.field("cause", finding.cause().name()).field("severity", finding.severity())
                .field("headline", finding.headline()).field("evidence", finding.evidence())
                .field("advice", finding.advice());
    }

    /** The headline numbers of an interval, written into the object already open. */
    private static void headlineFields(Json json, MetricsSnapshot recent) {
        double seconds = Math.max(1e-9, recent.getIntervalSeconds());
        TimerSnapshot reads = Sampler.total(recent, QuackMetrics.REQUEST_READ);
        TimerSnapshot writes = Sampler.total(recent, QuackMetrics.REQUEST_WRITE);
        TimerSnapshot waits = Sampler.total(recent, QuackMetrics.WRITE_LOCK_WAIT);
        double waitAndWork = waits.totalNanos() + writes.totalNanos();
        long hits = recent.counter(QuackMetrics.PREPARE_HITS);
        long misses = recent.counter(QuackMetrics.PREPARE_MISSES);
        json.field("readsPerSecond", reads.count() / seconds)
                .field("readP50", reads.count() == 0 ? Double.NaN : reads.percentileMillis(50))
                .field("readP99", reads.count() == 0 ? Double.NaN : reads.percentileMillis(99))
                .field("writesPerSecond", writes.count() / seconds)
                .field("writeP50", writes.count() == 0 ? Double.NaN : writes.percentileMillis(50))
                .field("writeP99", writes.count() == 0 ? Double.NaN : writes.percentileMillis(99))
                .field("lockWaitShare", waitAndWork == 0 ? 0 : waits.totalNanos() / waitAndWork)
                .field("cpu", recent.cpuUtilisation())
                .field("machineCpu", recent.gauge(QuackMetrics.CPU_MACHINE))
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
                .field("prepareHits", hits)
                .field("prepareMisses", misses)
                .field("prepareHitRate", hits + misses == 0 ? Double.NaN : hits / (double) (hits + misses));
    }

    /** Receives one row: a name, and the fields to write into an object already open. */
    private interface RowWriter {
        void write(String name, java.util.function.Consumer<Json> fields) throws IOException;
    }

    private static void collections(Json json, MetricsSnapshot recent) {
        json.key("collections").array();
        try {
            forEachCollection(recent, (name, fields) -> {
                json.object();
                fields.accept(json);
                json.end();
            });
        }
        catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        json.endArray();
    }

    /** Wait against work for each collection active in the interval - for the page and the files. */
    private static void forEachCollection(MetricsSnapshot recent, RowWriter out) throws IOException {
        double seconds = Math.max(1e-9, recent.getIntervalSeconds());
        Map<String, TimerSnapshot> reads = recent.scopes(QuackMetrics.REQUEST_READ);
        Map<String, TimerSnapshot> writes = recent.scopes(QuackMetrics.REQUEST_WRITE);
        Map<String, TimerSnapshot> waits = recent.scopes(QuackMetrics.WRITE_LOCK_WAIT);
        Set<String> names = new TreeSet<>(reads.keySet());
        names.addAll(writes.keySet());
        names.addAll(waits.keySet());
        for (String name : names) {
            TimerSnapshot read = reads.getOrDefault(name, TimerSnapshot.none(name));
            TimerSnapshot write = writes.getOrDefault(name, TimerSnapshot.none(name));
            TimerSnapshot wait = waits.getOrDefault(name, TimerSnapshot.none(name));
            if (read.count() + write.count() + wait.count() == 0) {
                continue;
            }
            double waitAndWork = wait.totalNanos() + write.totalNanos();
            out.write(name, json -> json.field("name", name)
                    .field("readsPerSecond", read.count() / seconds)
                    .field("readP50", read.count() == 0 ? Double.NaN : read.percentileMillis(50))
                    .field("readP99", read.count() == 0 ? Double.NaN : read.percentileMillis(99))
                    .field("writesPerSecond", write.count() / seconds)
                    .field("writeP50", write.count() == 0 ? Double.NaN : write.percentileMillis(50))
                    .field("writeP99", write.count() == 0 ? Double.NaN : write.percentileMillis(99))
                    .field("lockWaitP50", wait.count() == 0 ? Double.NaN : wait.percentileMillis(50))
                    .field("lockWaitP99", wait.count() == 0 ? Double.NaN : wait.percentileMillis(99))
                    .field("waitShare", waitAndWork == 0 ? 0 : wait.totalNanos() / waitAndWork));
        }
    }

    private static void statements(Json json, MetricsSnapshot minute, QuackMetrics metrics) {
        double seconds = Math.max(1e-9, minute.getIntervalSeconds());
        List<TimerSnapshot> hottest = minute.statementsByTotalTime();
        long total = 0;
        for (TimerSnapshot timer : hottest) {
            total += timer.totalNanos();
        }
        json.field("statementErrors", minute.counterTotal(QuackMetrics.STATEMENT_ERRORS));
        json.key("statements").array();
        for (TimerSnapshot timer : hottest.subList(0, Math.min(15, hottest.size()))) {
            statementFields(json.object(), timer, seconds, total == 0 ? 0 : timer.totalNanos() / (double) total);
            String name = timer.getName();
            io.quackjvm.core.metrics.QueryProfile profile =
                    metrics.profile(name.substring(QuackMetrics.STATEMENT.length() + 1, name.length() - 1));
            if (profile != null) {
                json.key("profile").object();
                profileFields(json, profile);
                json.end();
            }
            json.end();
        }
        json.endArray();
    }

    /** One profile, values already removed by the core: totals, then the plan a step at a time. */
    private static void profileFields(Json json, io.quackjvm.core.metrics.QueryProfile profile) {
        json.field("shape", profile.getShape())
                .field("capturedAt", profile.getCapturedAt().toEpochMilli())
                .field("latencyMillis", profile.getLatencyMillis())
                .field("cpuMillis", profile.getCpuMillis())
                .field("parallelism", profile.getParallelism())
                .field("rowsScanned", profile.getRowsScanned())
                .field("rowsReturned", profile.getRowsReturned())
                .field("peakMemoryBytes", profile.getPeakMemoryBytes())
                .field("tempBytes", profile.getTempBytes());
        json.key("operators").array();
        for (io.quackjvm.core.metrics.QueryProfile.Operator operator : profile.getOperators()) {
            json.object().field("depth", operator.depth()).field("name", operator.name())
                    .field("millis", operator.millis()).field("rows", operator.rows())
                    .field("estimatedRows", operator.estimatedRows()).field("rowsScanned", operator.rowsScanned())
                    .field("detail", operator.detail()).end();
        }
        json.endArray();
    }

    private static void statementFields(Json json, TimerSnapshot timer, double seconds, double share) {
        String name = timer.getName();
        json.field("shape", name.substring(QuackMetrics.STATEMENT.length() + 1, name.length() - 1))
                .field("perSecond", timer.count() / Math.max(1e-9, seconds))
                .field("count", timer.count())
                .field("mean", timer.meanMillis())
                .field("p50", timer.percentileMillis(50))
                .field("p99", timer.percentileMillis(99))
                .field("totalMillis", timer.totalMillis());
        if (!Double.isNaN(share)) {
            json.field("share", share);
        }
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
        json.numbers("machineCpu", points.stream().map(Sampler.Point::machineCpu).toList());
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
