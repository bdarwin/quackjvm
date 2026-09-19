/*
 * Twenty users on a pivot dashboard over five million sales: the same panels served from the base
 * table, and from rollups - each panel's aggregate materialized at the grain it needs.
 *
 * DashboardDemo's pivots phase showed that no single query is the problem: every panel refresh
 * scans all five million rows, and twenty users doing that at once take every core. The answer is
 * not to scan them. This measures what that buys, and checks it gives the same answers:
 *
 *   1. Build a rollup per panel, and time it - that is also what a refresh costs.
 *   2. Every panel, for every year it can be asked about, must return exactly the same rows from its
 *      rollup as from the base table.
 *   3. Twenty users, base tables against rollups, and rollups with DuckDB's threads halved.
 *   4. The rollups refreshed while the twenty users read: how long, and whether any read fails.
 *
 * The sparse wide view stays on its base table in every run: it shows individual records, so there
 * is nothing to roll up. It is in the mix so the load is the same.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/PivotRollups.java
 */

import io.quackjvm.core.metrics.MetricsSnapshot;
import io.quackjvm.core.sql.SqlRow;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.persistence.DuckDBDatabase.ManagedMaterialization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PivotRollups {

    static final int USERS = 20;
    static final String REGIONS = "('EMEA', 'AMER', 'APAC', 'LATAM', 'ANZ', 'MEA')";
    static final String CHANNELS = "('web', 'store', 'partner', 'phone')";

    /** A panel: its SQL against the base tables, and against its rollup. Each takes a year. */
    record Panel(String name, String base, String rollup, boolean ordered) {
    }

    static final List<Panel> PANELS = List.of(
            new Panel("regions as columns",
                    "PIVOT (SELECT make, year, region, price FROM sale WHERE year >= ?) ON region IN " + REGIONS
                            + " USING sum(price) GROUP BY make, year ORDER BY make, year",
                    "PIVOT (SELECT make, year, region, price FROM sale_by_make_year_region WHERE year >= ?)"
                            + " ON region IN " + REGIONS + " USING sum(price) GROUP BY make, year ORDER BY make, year",
                    true),
            new Panel("months as columns", months("sale", "price * qty", "year = ?"),
                    months("sale_by_country_channel_month", "revenue", "year = ?"), true),
            new Panel("join, channels as columns",
                    "PIVOT (SELECT c.segment, c.tier, s.channel, s.price FROM sale s JOIN customer c ON c.id = s.customer"
                            + " WHERE s.year >= ?) ON channel IN " + CHANNELS
                            + " USING sum(price) AS revenue, count(*) AS sales GROUP BY segment, tier ORDER BY segment, tier",
                    "PIVOT (SELECT segment, tier, channel, revenue, sales FROM sale_by_segment_tier_channel WHERE year >= ?)"
                            + " ON channel IN " + CHANNELS
                            + " USING sum(revenue) AS revenue, sum(sales) AS sales GROUP BY segment, tier ORDER BY segment, tier",
                    true),
            new Panel("top 5 models per region",
                    "SELECT * FROM (SELECT region, model, sum(price * qty) AS revenue, rank() OVER (PARTITION BY region"
                            + " ORDER BY sum(price * qty) DESC) AS r FROM sale WHERE year >= ? GROUP BY region, model)"
                            + " WHERE r <= 5 ORDER BY region, r",
                    "SELECT * FROM (SELECT region, model, sum(revenue) AS revenue, rank() OVER (PARTITION BY region"
                            + " ORDER BY sum(revenue) DESC) AS r FROM sale_by_region_model_year WHERE year >= ?"
                            + " GROUP BY region, model) WHERE r <= 5 ORDER BY region, r",
                    // Equal revenues tie on rank, and tied rows may come back in either order.
                    false));

    /** Rollups: each panel's aggregate at the grain it needs, the year kept so it can be filtered. */
    static final String[][] ROLLUPS = {
            {"sale_by_make_year_region", "SELECT make, year, region, sum(price) AS price FROM sale GROUP BY ALL"},
            {"sale_by_country_channel_month",
                    "SELECT country, channel, year, month, sum(price * qty) AS revenue FROM sale GROUP BY ALL"},
            {"sale_by_segment_tier_channel", "SELECT c.segment, c.tier, s.channel, s.year, sum(s.price) AS revenue,"
                    + " count(*) AS sales FROM sale s JOIN customer c ON c.id = s.customer GROUP BY ALL"},
            {"sale_by_region_model_year",
                    "SELECT region, model, year, sum(price * qty) AS revenue FROM sale GROUP BY ALL"},
    };

    static final String SPARSE_WIDE = sparseWide();

    static String months(String table, String measure, String where) {
        StringBuilder sql = new StringBuilder("SELECT country, channel");
        for (int month = 1; month <= 12; month++) {
            sql.append(", sum(").append(measure).append(") FILTER (WHERE month = ").append(month).append(") AS m").append(month);
        }
        return sql.append(" FROM ").append(table).append(" WHERE ").append(where)
                .append(" GROUP BY ALL ORDER BY country, channel").toString();
    }

    static String sparseWide() {
        StringBuilder sql = new StringBuilder("SELECT record_id");
        StringBuilder shown = new StringBuilder();
        for (int k = 0; k < 40; k++) {
            sql.append(", max(value) FILTER (WHERE attr = ").append(k * 7).append(") AS \"sensor_").append(k * 7).append('"');
            shown.append(k == 0 ? "" : ", ").append(k * 7);
        }
        return sql.append(" FROM reading_point WHERE record_id >= ? AND attr IN (").append(shown)
                .append(") GROUP BY record_id ORDER BY record_id LIMIT 100").toString();
    }

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("3GB").build()) {
            createData(database);

            System.out.println("1. Rollups, one per panel");
            List<ManagedMaterialization> rollups = new ArrayList<>();
            long total = System.nanoTime();
            for (String[] rollup : ROLLUPS) {
                long started = System.nanoTime();
                ManagedMaterialization built = database.materialize(rollup[0]).as(rollup[1]).build();
                rollups.add(built);
                System.out.printf("   %-32s %,7d rows   built in %5.0f ms%n", rollup[0], built.rowCount(),
                        (System.nanoTime() - started) / 1e6);
            }
            System.out.printf("   all four: %.0f ms, from 5,000,000 sales%n%n", (System.nanoTime() - total) / 1e6);

            System.out.println("2. Same answers from the rollups as from the base table");
            int checked = 0;
            int identical = 0;
            for (Panel panel : PANELS) {
                for (int year = 2020; year <= 2026; year++) {
                    List<String> fromBase = rows(database, panel.base(), year, panel.ordered());
                    List<String> fromRollup = rows(database, panel.rollup(), year, panel.ordered());
                    checked++;
                    if (fromBase.equals(fromRollup) && !fromBase.isEmpty()) {
                        identical++;
                    }
                    else {
                        System.out.printf("   DIFFERENT: %s, year %d: %d rows against %d%n", panel.name(), year,
                                fromBase.size(), fromRollup.size());
                    }
                }
            }
            System.out.printf("   %d of %d panel-and-year combinations identical, row for row%n%n", identical, checked);

            System.out.printf("3. Twenty users, each refreshing one of five panels at random (%d cores)%n", cores);
            System.out.println("   panels/s   p50 ms   p99 ms   CPU");
            report("base tables", load(database, false, 15));
            report("rollups", load(database, true, 15));
            // threads is a database-wide setting, fixed here when the database is opened.
            try (DuckDBDatabase halved = DuckDBDatabase.builder().memoryLimit("3GB")
                    .property("threads", String.valueOf(cores / 2)).build()) {
                createData(halved);
                for (String[] rollup : ROLLUPS) {
                    halved.materialize(rollup[0]).as(rollup[1]).build();
                }
                report("rollups, threads = " + cores / 2, load(halved, true, 15));
            }
            System.out.println();

            System.out.println("4. Refreshing all four rollups while the twenty users read");
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong reads = new AtomicLong();
            java.util.Map<String, AtomicLong> failures = new java.util.concurrent.ConcurrentHashMap<>();
            ExecutorService users = Executors.newFixedThreadPool(USERS);
            for (int u = 0; u < USERS; u++) {
                users.submit(() -> {
                    while (!stop.get()) {
                        try {
                            refresh(database, true);
                            reads.incrementAndGet();
                        }
                        catch (RuntimeException e) {
                            // Counted by message: a failure is only useful if it says what it was.
                            Throwable root = e;
                            while (root.getCause() != null) {
                                root = root.getCause();
                            }
                            String message = String.valueOf(root.getMessage()).lines().findFirst().orElse("?");
                            failures.computeIfAbsent(message, k -> new AtomicLong()).incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            Thread.sleep(2_000);
            double[] refreshes = new double[5];
            for (int i = 0; i < refreshes.length; i++) {
                long started = System.nanoTime();
                for (ManagedMaterialization rollup : rollups) {
                    rollup.refresh();
                }
                refreshes[i] = (System.nanoTime() - started) / 1e6;
            }
            stop.set(true);
            users.shutdown();
            users.awaitTermination(1, TimeUnit.MINUTES);
            Arrays.sort(refreshes);
            System.out.printf("   %d refreshes of all four: median %.0f ms each, under load%n", refreshes.length,
                    refreshes[refreshes.length / 2]);
            long failed = failures.values().stream().mapToLong(AtomicLong::get).sum();
            System.out.printf("   meanwhile %,d panel reads, %d failed%n", reads.get(), failed);
            failures.forEach((message, count) -> System.out.printf("     %d x %s%n", count.get(), message));
        }
    }

    static final String[] PANEL_NAMES = {"regions as columns", "months as columns", "join, channels as columns",
            "top 5 models per region", "sparse wide view (base table)"};

    /** One panel, as a user refreshes it: a random panel, a random year, every row read. Returns which. */
    static int refresh(DuckDBDatabase database, boolean fromRollups) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int choice = random.nextInt(PANELS.size() + 1);
        if (choice == PANELS.size()) {
            database.query(SPARSE_WIDE, random.nextInt(100_000)).forEachRow(row -> {
            });
            return choice;
        }
        Panel panel = PANELS.get(choice);
        database.query(fromRollups ? panel.rollup() : panel.base(), 2020 + random.nextInt(5)).forEachRow(row -> {
        });
        return choice;
    }

    record Result(double perSecond, double p50, double p99, double cpu, double[][] perPanel) {
    }

    static Result load(DuckDBDatabase database, boolean fromRollups, int seconds) throws Exception {
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService users = Executors.newFixedThreadPool(USERS);
        List<Future<long[]>> timings = new ArrayList<>();
        for (int u = 0; u < USERS; u++) {
            timings.add(users.submit(() -> {
                long[] nanos = new long[1 << 16];
                int n = 0;
                while (!stop.get()) {
                    long started = System.nanoTime();
                    int panel = refresh(database, fromRollups);
                    if (measuring.get() && n < nanos.length) {
                        // The panel in the low bits, so one array carries both.
                        nanos[n++] = (System.nanoTime() - started) << 3 | panel;
                    }
                }
                return Arrays.copyOf(nanos, n);
            }));
        }
        Thread.sleep(3_000);                          // warm up, then measure
        MetricsSnapshot before = database.metrics().snapshot();
        measuring.set(true);
        long started = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        measuring.set(false);
        double elapsed = (System.nanoTime() - started) / 1e9;
        MetricsSnapshot interval = database.metrics().snapshot().minus(before);
        stop.set(true);
        users.shutdown();
        users.awaitTermination(1, TimeUnit.MINUTES);
        List<Long> all = new ArrayList<>();
        List<List<Long>> byPanel = new ArrayList<>();
        for (int p = 0; p < PANEL_NAMES.length; p++) {
            byPanel.add(new ArrayList<>());
        }
        for (Future<long[]> timing : timings) {
            for (long packed : timing.get()) {
                all.add(packed >>> 3);
                byPanel.get((int) (packed & 7)).add(packed >>> 3);
            }
        }
        all.sort(null);
        double[][] perPanel = new double[PANEL_NAMES.length][];
        for (int p = 0; p < PANEL_NAMES.length; p++) {
            List<Long> times = byPanel.get(p);
            times.sort(null);
            perPanel[p] = times.isEmpty() ? new double[]{0, 0, 0}
                    : new double[]{times.size() / elapsed, times.get(times.size() / 2) / 1e6,
                    times.get((int) (times.size() * 0.99)) / 1e6};
        }
        return new Result(all.size() / elapsed, all.get(all.size() / 2) / 1e6,
                all.get((int) (all.size() * 0.99)) / 1e6, interval.cpuUtilisation(), perPanel);
    }

    static void report(String label, Result result) {
        System.out.printf("   %8.1f %8.1f %8.1f %5.0f%%   %s%n", result.perSecond(), result.p50(), result.p99(),
                result.cpu() * 100, label);
        for (int p = 0; p < PANEL_NAMES.length; p++) {
            System.out.printf("   %8.1f %8.1f %8.1f          - %s%n", result.perPanel()[p][0], result.perPanel()[p][1],
                    result.perPanel()[p][2], PANEL_NAMES[p]);
        }
    }

    static List<String> rows(DuckDBDatabase database, String sql, int year, boolean ordered) {
        List<String> rows = new ArrayList<>();
        database.query(sql, year).forEachRow((SqlRow row) -> rows.add(Arrays.toString(row.toArray())));
        if (!ordered) {
            rows.sort(null);
        }
        return rows;
    }

    static void createData(DuckDBDatabase database) {
        database.materialize("sale").as("""
                SELECT i AS id,
                       ['EMEA','AMER','APAC','LATAM','ANZ','MEA'][1 + i % 6] AS region,
                       'country' || (i % 40) AS country,
                       ['Ford','BMW','Toyota','Honda','Tesla','Kia','Audi','Fiat','Volvo','Mazda','Seat','Skoda'][1 + (i // 7) % 12] AS make,
                       'model' || ((i // 7) % 120) AS model,
                       ['web','store','partner','phone'][1 + (i // 3) % 4] AS channel,
                       2020 + (i // 11) % 7 AS year,
                       1 + (i // 13) % 12 AS month,
                       (i * 7919) % 200000 AS customer,
                       10000 + (i * 7919) % 50000 AS price,
                       1 + i % 5 AS qty
                FROM range(5000000) t(i)""").build();
        database.materialize("customer").as("SELECT i AS id, ['retail','fleet','gov','rental','dealer'][1 + i % 5]"
                + " AS segment, ['gold','silver','bronze'][1 + i % 3] AS tier FROM range(200000) t(i)").build();
        database.materialize("reading_point").as("SELECT r AS record_id, (r * 37 + k * 41) % 2000 AS attr,"
                + " (r * k) % 1000 / 10.0 AS value FROM range(200000) a(r), range(50) b(k)").build();
    }
}

/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1). Sections 1 and 2 are from one run,
 * 3 and 4 from the next; the machine had IDEs running, 1-2 cores' worth.
 *
 * 1. Rollups, one per panel
 *    sale_by_make_year_region             504 rows   built in    26 ms
 *    sale_by_country_channel_month     10,080 rows   built in    33 ms
 *    sale_by_segment_tier_channel         420 rows   built in    39 ms
 *    sale_by_region_model_year          5,040 rows   built in    28 ms
 *    all four: 132 ms, from 5,000,000 sales
 *
 * 2. Same answers from the rollups as from the base table
 *    28 of 28 panel-and-year combinations identical, row for row
 *
 * 3. Twenty users, each refreshing one of five panels at random (10 cores)
 *    panels/s   p50 ms   p99 ms   CPU
 *        36.0    509.8   1502.9    83%   base tables
 *         6.3    521.2    999.5          - regions as columns
 *         7.2    472.1    888.5          - months as columns
 *         7.9    730.4   1571.3          - join, channels as columns
 *         7.5    388.2    712.4          - top 5 models per region
 *         7.1    587.4   1050.4          - sparse wide view (base table)
 *       164.5      3.8    965.4    88%   rollups
 *        32.1      2.0     72.9          - regions as columns
 *        32.6      3.4     81.1          - months as columns
 *        34.2      2.1     61.3          - join, channels as columns
 *        32.4      2.3     73.7          - top 5 models per region
 *        33.2    546.9   1059.4          - sparse wide view (base table)
 *       193.2      2.3    842.9    85%   rollups, threads = 5
 *        39.6      1.4     38.4          - regions as columns
 *        36.9      2.6     80.0          - months as columns
 *        39.5      1.6     69.1          - join, channels as columns
 *        40.7      1.6     65.2          - top 5 models per region
 *        36.5    505.2    995.2          - sparse wide view (base table)
 *
 * 4. Refreshing all four rollups while the twenty users read
 *    5 refreshes of all four: median 1124 ms each, under load
 *    meanwhile 1,320 panel reads, 0 failed
 *
 * A second run agreed on everything but "threads = 5": 88 panels/s with a 5.8 s p99 there, against
 * 193 and 0.8 s here - so that line settles nothing. Base tables and rollups repeated closely:
 * 41.6 and 164.2 panels/s, the four rolled-up panels at 2-4 ms, 0 failed reads during refresh.
 */
