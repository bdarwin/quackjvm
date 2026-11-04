package bench;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.option.EngineThresholds;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.questdb.client.Sender;
import io.quackjvm.core.Quack;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.*;

/**
 * One engine per JVM, the same seeded data each time:
 * <ul>
 *     <li>{@code readings(device, ts, value)}: n rows evenly spaced over one day, each with a random device</li>
 *     <li>{@code probes(device, ts)}: m random times in the same day, each with a random device</li>
 * </ul>
 * Prints the load time, memory, and the median of each question with a row count and sum to
 * cross-check against the other engines. Each figure is also printed as a tab-separated
 * {@code RESULT} line, which {@link Report} turns into the tables in docs/questdb.md.
 *
 * <pre>
 * java bench.Compare engine n m d runs [dir]
 *   engine  duck | quest | cq | java
 *   dir     where DuckDB writes its file (default .)
 * system properties:
 *   cq.probes  the number of probes CQEngine answers (all by default; it is slow)
 *   cq.skip    questions CQEngine skips, comma-separated (e.g. latest at 100,000 devices)
 *   qdb.pid    QuestDB's process id, for its resident memory
 *   qdb.root   QuestDB's root directory, for its disk use
 * </pre>
 */
public final class Compare {

    static final long START = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli() * 1000;
    static final long SPAN = 86_400_000_000L;
    /** One hour before the end of the day: the window for "last hour". */
    static final long RECENT = START + SPAN - 3_600_000_000L;

    static int n, m, d, runs;
    static long[] rTs, pTs;
    static int[] rDev, pDev;
    static double[] rVal;
    static String[] names;
    static String engineName;

    public static void main(String[] args) throws Exception {
        engineName = args[0];
        n = Integer.parseInt(args[1]);
        m = Integer.parseInt(args[2]);
        d = Integer.parseInt(args[3]);
        runs = Integer.parseInt(args[4]);
        String dir = args.length > 5 ? args[5] : ".";
        generate();
        long before = usedHeap();
        Engine e = switch (engineName) {
            case "duck" -> new Duck(dir);
            case "quest" -> new Quest();
            case "cq" -> new Cq();
            case "java" -> new Arrays_();
            default -> throw new IllegalArgumentException(engineName);
        };
        long t0 = System.nanoTime();
        e.load();
        double loadMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%s n=%,d m=%,d d=%,d load %.0f ms (%.2f M rows/s)%n", engineName, n, m, d, loadMs,
                (n + m) / (loadMs / 1000) / 1e6);
        result("load", loadMs, loadMs, loadMs, "");
        for (Map.Entry<String, Callable<String>> q : e.queries().entrySet()) {
            time(q.getKey(), q.getValue());
        }
        String memory = e.memory(before);
        System.out.println("  memory  " + memory);
        System.out.printf("RESULT\t%s\t%d\t%d\t%d\tmemory\t\t\t\t%s%n", engineName, n, m, d, memory);
        e.close();
    }

    static void time(String name, Callable<String> query) {
        String result;
        try {
            result = query.call();
            int warm = Math.min(1, runs);
            for (int i = 0; i < warm; i++) result = query.call();
            double[] ms = new double[runs];
            for (int i = 0; i < runs; i++) {
                long s = System.nanoTime();
                result = query.call();
                ms[i] = (System.nanoTime() - s) / 1e6;
            }
            Arrays.sort(ms);
            System.out.printf("  %-14s median %9.1f ms  (min %8.1f, max %8.1f)  -> %s%n", name, ms[runs / 2],
                    ms[0], ms[runs - 1], result);
            result(name, ms[runs / 2], ms[0], ms[runs - 1], result);
        }
        catch (Exception failed) {
            String why = String.valueOf(failed.getMessage()).lines().findFirst().orElse("");
            System.out.printf("  %-14s FAILED: %s%n", name, why);
            System.out.printf("RESULT\t%s\t%d\t%d\t%d\t%s\tFAILED\t\t\t%s%n", engineName, n, m, d, name, why);
        }
    }

    static void result(String name, double median, double min, double max, String summary) {
        System.out.printf("RESULT\t%s\t%d\t%d\t%d\t%s\t%.2f\t%.2f\t%.2f\t%s%n", engineName, n, m, d, name, median,
                min, max, summary);
    }

    static void generate() {
        Random r = new Random(42);
        names = new String[d];
        for (int i = 0; i < d; i++) names[i] = String.format("d%05d", i);
        rTs = new long[n];
        rDev = new int[n];
        rVal = new double[n];
        for (int i = 0; i < n; i++) {
            rTs[i] = START + (long) ((double) i * SPAN / n);
            rDev[i] = r.nextInt(d);
            rVal[i] = Math.round(r.nextDouble() * 10_000) / 100.0;
        }
        pTs = new long[m];
        pDev = new int[m];
        for (int i = 0; i < m; i++) pTs[i] = START + (long) (r.nextDouble() * SPAN);
        Arrays.sort(pTs);
        for (int i = 0; i < m; i++) pDev[i] = r.nextInt(d);
    }

    static long usedHeap() {
        for (int i = 0; i < 3; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static String mb(long bytes) {
        return String.format("%,.0f MB", bytes / 1024.0 / 1024.0);
    }

    static String summary(long rows, double sum) {
        return String.format("rows=%d sum=%.2f", rows, sum);
    }

    interface Engine extends AutoCloseable {
        void load() throws Exception;

        Map<String, Callable<String>> queries();

        String memory(long heapBefore) throws Exception;

        @Override
        default void close() throws Exception {
        }
    }

    // ---------------------------------------------------------------- DuckDB through quackjvm

    public record ProbeRow(String device, java.time.LocalDateTime ts, Double value) {
    }

    public record HourRow(String device, java.time.LocalDateTime b, Double a) {
    }

    public record LatestRow(String device, Double v, java.time.LocalDateTime t) {
    }

    public record DeviceAvg(String device, Double a) {
    }

    static final class Duck implements Engine {
        final String path;
        Quack db;

        Duck(String dir) {
            path = new File(dir, "compare.duckdb").getPath();
        }

        public void load() throws Exception {
            Files.deleteIfExists(Path.of(path));
            Files.deleteIfExists(Path.of(path + ".wal"));
            db = Quack.open(path);
            db.execute("CREATE TABLE readings (device VARCHAR, ts TIMESTAMP, value DOUBLE)");
            db.execute("CREATE TABLE probes (device VARCHAR, ts TIMESTAMP)");
            DuckDBConnection c = db.sharedConnection().unwrap(DuckDBConnection.class);
            try (DuckDBAppender a = c.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "readings")) {
                for (int i = 0; i < n; i++) a.beginRow().append(names[rDev[i]]).appendEpochMicros(rTs[i]).append(rVal[i]).endRow();
            }
            try (DuckDBAppender a = c.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "probes")) {
                for (int i = 0; i < m; i++) a.beginRow().append(names[pDev[i]]).appendEpochMicros(pTs[i]).endRow();
            }
            // Quack.execute runs executeUpdate, which DuckDB refuses for CHECKPOINT (it returns a row).
            try (Statement st = db.sharedConnection().createStatement()) {
                st.execute("CHECKPOINT");
            }
        }

        String run(String sql, int sumColumn) {
            long[] rows = {0};
            double[] sum = {0};
            db.query(sql).forEachRow(row -> {
                rows[0]++;
                Object v = row.get(sumColumn + 1);
                if (v != null) sum[0] += ((Number) v).doubleValue();
            });
            return summary(rows[0], sum[0]);
        }

        public Map<String, Callable<String>> queries() {
            Map<String, Callable<String>> q = new LinkedHashMap<>();
            q.put("asof", () -> {
                var row = db.query("SELECT count(r.value), sum(r.value) FROM probes p ASOF JOIN readings r"
                        + " ON p.device = r.device AND p.ts >= r.ts").stream().findFirst().orElseThrow();
                return summary(row.getLong(1), row.getDouble(2));
            });
            q.put("asofRows", () -> run("SELECT p.device, p.ts, r.value FROM probes p ASOF LEFT JOIN readings r"
                    + " ON p.device = r.device AND p.ts >= r.ts", 2));
            q.put("asofRows+ord", () -> run("SELECT p.device, p.ts, r.value FROM probes p ASOF LEFT JOIN readings r"
                    + " ON p.device = r.device AND p.ts >= r.ts ORDER BY p.ts", 2));
            q.put("bucket", () -> run("SELECT device, time_bucket(INTERVAL 1 HOUR, ts) b, avg(value) a FROM readings"
                    + " GROUP BY ALL", 2));
            q.put("latest", () -> run("SELECT device, arg_max(value, ts) v, max(ts) t FROM readings GROUP BY device", 1));
            q.put("recent", () -> run("SELECT device, avg(value) FROM readings WHERE ts >= make_timestamp(" + RECENT
                    + ") GROUP BY device", 1));
            // The same questions read as records, which quackjvm reads through Arrow when it is on
            // the classpath, instead of value by value through JDBC.
            q.put("asofRows/records", () -> records(ProbeRow.class, ProbeRow::value, "SELECT p.device, p.ts, r.value"
                    + " FROM probes p ASOF LEFT JOIN readings r ON p.device = r.device AND p.ts >= r.ts"));
            q.put("asofRows+ord/records", () -> records(ProbeRow.class, ProbeRow::value, "SELECT p.device, p.ts, r.value"
                    + " FROM probes p ASOF LEFT JOIN readings r ON p.device = r.device AND p.ts >= r.ts ORDER BY p.ts"));
            q.put("bucket/records", () -> records(HourRow.class, HourRow::a, "SELECT device, time_bucket(INTERVAL 1 HOUR, ts) b,"
                    + " avg(value) a FROM readings GROUP BY ALL"));
            q.put("latest/records", () -> records(LatestRow.class, LatestRow::v, "SELECT device, arg_max(value, ts) v,"
                    + " max(ts) t FROM readings GROUP BY device"));
            q.put("recent/records", () -> records(DeviceAvg.class, DeviceAvg::a, "SELECT device, avg(value) a FROM readings"
                    + " WHERE ts >= make_timestamp(" + RECENT + ") GROUP BY device"));
            return q;
        }

        <T> String records(Class<T> type, java.util.function.Function<T, Double> value, String sql) {
            long[] rows = {0};
            double[] sum = {0};
            db.query(sql).forEachRecord(type, r -> {
                rows[0]++;
                Double v = value.apply(r);
                if (v != null) sum[0] += v;
            });
            return summary(rows[0], sum[0]);
        }

        public String memory(long heapBefore) {
            Long mem = db.query("SELECT sum(memory_usage_bytes)::BIGINT FROM duckdb_memory()").scalar(Long.class);
            return "duckdb buffers " + mb(mem) + ", file " + mb(new File(path).length());
        }

        public void close() {
            db.close();
        }
    }

    // ---------------------------------------------------------------- QuestDB, ILP in, PG wire out

    static final class Quest implements Engine {
        final long pid = Long.getLong("qdb.pid", -1);
        final String root = System.getProperty("qdb.root");
        Connection pg;

        public void load() throws Exception {
            pg = DriverManager.getConnection("jdbc:postgresql://localhost:8812/qdb", "admin", "quest");
            int capacity = Math.max(256, Integer.highestOneBit(d) * 2);
            try (Statement s = pg.createStatement()) {
                s.execute("DROP TABLE IF EXISTS readings");
                s.execute("DROP TABLE IF EXISTS probes");
                s.execute("CREATE TABLE readings (device SYMBOL CAPACITY " + capacity
                        + ", value DOUBLE, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY WAL");
                s.execute("CREATE TABLE probes (device SYMBOL CAPACITY " + capacity
                        + ", ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY WAL");
            }
            try (Sender sender = Sender.fromConfig("http::addr=localhost:9000;auto_flush_rows=100000;")) {
                for (int i = 0; i < n; i++) {
                    sender.table("readings").symbol("device", names[rDev[i]]).doubleColumn("value", rVal[i])
                            .at(rTs[i], ChronoUnit.MICROS);
                }
                for (int i = 0; i < m; i++) {
                    sender.table("probes").symbol("device", names[pDev[i]]).at(pTs[i], ChronoUnit.MICROS);
                }
            }
            // Written is not visible until the WAL is applied; time until it is.
            waitFor("readings", n);
            waitFor("probes", m);
        }

        void waitFor(String table, long rows) throws Exception {
            while (true) {
                try (Statement s = pg.createStatement(); var rs = s.executeQuery("SELECT count() FROM " + table)) {
                    rs.next();
                    if (rs.getLong(1) >= rows) return;
                }
                Thread.sleep(20);
            }
        }

        String run(String sql, int sumColumn) throws Exception {
            long rows = 0;
            double sum = 0;
            try (Statement s = pg.createStatement(); var rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    rows++;
                    double v = rs.getDouble(sumColumn + 1);
                    if (!rs.wasNull()) sum += v;
                }
            }
            return summary(rows, sum);
        }

        String totals(String sql) throws Exception {
            try (Statement s = pg.createStatement(); var rs = s.executeQuery(sql)) {
                rs.next();
                return summary(rs.getLong(1), rs.getDouble(2));
            }
        }

        static String at(long micros) {
            return "'" + Instant.ofEpochMilli(micros / 1000) + "'";
        }

        public Map<String, Callable<String>> queries() {
            Map<String, Callable<String>> q = new LinkedHashMap<>();
            q.put("asof", () -> totals("SELECT count(r.value), sum(r.value) FROM probes p ASOF JOIN readings r ON (device)"));
            for (String hint : new String[] {"dense", "linear"}) {
                q.put("asof+" + hint, () -> totals("SELECT /*+ asof_" + hint
                        + "(p r) */ count(r.value), sum(r.value) FROM probes p ASOF JOIN readings r ON (device)"));
            }
            q.put("asofRows+dense", () -> run("SELECT /*+ asof_dense(p r) */ p.device, p.ts, r.value FROM probes p"
                    + " ASOF JOIN readings r ON (device)", 2));
            q.put("asofRows+dense+ord", () -> run("SELECT /*+ asof_dense(p r) */ p.device, p.ts, r.value FROM probes p"
                    + " ASOF JOIN readings r ON (device) ORDER BY p.ts", 2));
            q.put("bucket", () -> run("SELECT device, ts, avg(value) a FROM readings SAMPLE BY 1h", 2));
            q.put("latest", () -> run("SELECT device, value, ts FROM readings LATEST ON ts PARTITION BY device", 1));
            q.put("recent", () -> run("SELECT device, avg(value) FROM readings WHERE ts >= " + at(RECENT), 1));
            return q;
        }

        public String memory(long heapBefore) throws Exception {
            String rss = "?";
            if (pid > 0) {
                Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid)).start();
                rss = mb(Long.parseLong(new String(p.getInputStream().readAllBytes()).trim()) * 1024);
            }
            return "server RSS " + rss + (root == null ? "" : ", tables on disk " + mb(tablesOnDisk()));
        }

        /** The two tables' directories under QuestDB's db folder: readings~N and probes~N. */
        long tablesOnDisk() throws IOException {
            long bytes = 0;
            try (Stream<Path> tables = Files.list(Path.of(root, "db"))) {
                for (Path table : (Iterable<Path>) tables::iterator) {
                    String name = table.getFileName().toString();
                    if (name.startsWith("readings~") || name.startsWith("probes~")) {
                        try (Stream<Path> files = Files.walk(table)) {
                            bytes += files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
                        }
                    }
                }
            }
            return bytes;
        }

        public void close() throws Exception {
            pg.close();
        }
    }

    // ---------------------------------------------------------------- CQEngine on the heap

    public record Reading(String device, long ts, double value) {
    }

    static final SimpleAttribute<Reading, String> DEVICE = attribute(Reading.class, String.class, "device", Reading::device);
    static final SimpleAttribute<Reading, Long> TS = attribute(Reading.class, Long.class, "ts", Reading::ts);

    static final class Cq implements Engine {
        final IndexedCollection<Reading> readings = new ConcurrentIndexedCollection<>();
        final QueryOptions newestFirst = queryOptions(orderBy(descending(TS)),
                applyThresholds(threshold(EngineThresholds.INDEX_ORDERING_SELECTIVITY, 1.0)));
        final int probes = Integer.getInteger("cq.probes", Integer.MAX_VALUE);
        final Set<String> skip = Set.of(System.getProperty("cq.skip", "").split(","));

        public void load() {
            readings.addIndex(HashIndex.onAttribute(DEVICE));
            readings.addIndex(NavigableIndex.onAttribute(TS));
            List<Reading> all = new ArrayList<>(n);
            for (int i = 0; i < n; i++) all.add(new Reading(names[rDev[i]], rTs[i], rVal[i]));
            readings.addAll(all);
        }

        Reading newestAtOrBefore(String device, long ts) {
            try (ResultSet<Reading> rs = readings.retrieve(and(equal(DEVICE, device), lessThanOrEqualTo(TS, ts)), newestFirst)) {
                var it = rs.iterator();
                return it.hasNext() ? it.next() : null;
            }
        }

        static String groupAvg(Stream<Reading> rows) {
            Map<String, double[]> acc = new HashMap<>();
            rows.forEach(r -> {
                double[] a = acc.computeIfAbsent(r.device(), k -> new double[2]);
                a[0] += r.value();
                a[1]++;
            });
            return summary(acc.size(), acc.values().stream().mapToDouble(a -> a[0] / a[1]).sum());
        }

        public Map<String, Callable<String>> queries() {
            Map<String, Callable<String>> q = new LinkedHashMap<>();
            int k = Math.min(m, probes);
            q.put(k < m ? "asof/" + k : "asof", () -> {
                long rows = 0;
                double sum = 0;
                for (int i = 0; i < k; i++) {
                    Reading r = newestAtOrBefore(names[pDev[i]], pTs[i]);
                    if (r != null) {
                        rows++;
                        sum += r.value();
                    }
                }
                return summary(rows, sum);
            });
            q.put("bucket", () -> {
                Map<String, double[]> acc = new HashMap<>();
                for (Reading r : readings) {
                    double[] a = acc.computeIfAbsent(r.device() + "/" + Math.floorDiv(r.ts(), 3_600_000_000L), x -> new double[2]);
                    a[0] += r.value();
                    a[1]++;
                }
                return summary(acc.size(), acc.values().stream().mapToDouble(a -> a[0] / a[1]).sum());
            });
            q.put("latest", () -> {
                long rows = 0;
                double sum = 0;
                for (String name : names) {
                    Reading r = newestAtOrBefore(name, Long.MAX_VALUE);
                    if (r != null) {
                        rows++;
                        sum += r.value();
                    }
                }
                return summary(rows, sum);
            });
            q.put("recent", () -> {
                try (ResultSet<Reading> rs = readings.retrieve(greaterThanOrEqualTo(TS, RECENT))) {
                    return groupAvg(rs.stream());
                }
            });
            q.keySet().removeAll(skip);
            return q;
        }

        public String memory(long heapBefore) {
            return "heap " + mb(usedHeap() - heapBefore);
        }
    }

    // ---------------------------------------------------------------- hand-written arrays: the floor

    static final class Arrays_ implements Engine {
        long[][] ts;
        double[][] val;

        public void load() {
            int[] count = new int[d];
            for (int i = 0; i < n; i++) count[rDev[i]]++;
            ts = new long[d][];
            val = new double[d][];
            for (int k = 0; k < d; k++) {
                ts[k] = new long[count[k]];
                val[k] = new double[count[k]];
            }
            int[] at = new int[d];
            for (int i = 0; i < n; i++) {
                int k = rDev[i];
                ts[k][at[k]] = rTs[i];
                val[k][at[k]++] = rVal[i];
            }
        }

        public Map<String, Callable<String>> queries() {
            Map<String, Callable<String>> q = new LinkedHashMap<>();
            q.put("asof", () -> {
                long rows = 0;
                double sum = 0;
                for (int i = 0; i < m; i++) {
                    long[] t = ts[pDev[i]];
                    int j = Arrays.binarySearch(t, pTs[i]);
                    int idx = j >= 0 ? j : -j - 2;
                    if (idx >= 0) {
                        rows++;
                        sum += val[pDev[i]][idx];
                    }
                }
                return summary(rows, sum);
            });
            q.put("bucket", () -> {
                long rows = 0;
                double sum = 0;
                for (int k = 0; k < d; k++) {
                    long[] t = ts[k];
                    double[] v = val[k];
                    int i = 0;
                    while (i < t.length) {
                        long b = Math.floorDiv(t[i], 3_600_000_000L);
                        double s = 0;
                        int c = 0;
                        while (i < t.length && Math.floorDiv(t[i], 3_600_000_000L) == b) {
                            s += v[i++];
                            c++;
                        }
                        rows++;
                        sum += s / c;
                    }
                }
                return summary(rows, sum);
            });
            q.put("latest", () -> {
                long rows = 0;
                double sum = 0;
                for (int k = 0; k < d; k++) {
                    if (ts[k].length > 0) {
                        rows++;
                        sum += val[k][ts[k].length - 1];
                    }
                }
                return summary(rows, sum);
            });
            q.put("recent", () -> {
                long rows = 0;
                double sum = 0;
                for (int k = 0; k < d; k++) {
                    int j = Arrays.binarySearch(ts[k], RECENT);
                    int from = j >= 0 ? j : -j - 1;
                    if (from < ts[k].length) {
                        double s = 0;
                        for (int i = from; i < ts[k].length; i++) s += val[k][i];
                        rows++;
                        sum += s / (ts[k].length - from);
                    }
                }
                return summary(rows, sum);
            });
            return q;
        }

        public String memory(long heapBefore) {
            return "heap " + mb(usedHeap() - heapBefore);
        }
    }
}
