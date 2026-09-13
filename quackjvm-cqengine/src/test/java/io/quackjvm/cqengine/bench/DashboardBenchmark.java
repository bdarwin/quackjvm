package io.quackjvm.cqengine.bench;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;

/**
 * The dashboard shape: several people looking at the same panels at once.
 *
 * <p>Every other benchmark here measures either many small concurrent queries or one large
 * analytical query. A dashboard is neither - it is many *large* queries at once, which is where
 * those two results pull in opposite directions: DuckDB's intra-query parallelism is what makes a
 * group-by fast, and is also what makes concurrent queries fight each other for cores.</p>
 *
 * <pre>
 * java -Xmx6g --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
 *   -cp ... io.quackjvm.cqengine.bench.DashboardBenchmark [rows] [seconds]
 * </pre>
 */
public final class DashboardBenchmark {

    public record Sale(int saleId, String region, String make, String model, String colour,
                       double price, int year) {
        static final SimpleAttribute<Sale, Integer> ID =
                new SimpleAttribute<>(Sale.class, Integer.class, "saleId") {
                    public Integer getValue(Sale s, QueryOptions o) { return s.saleId(); }
                };
        static final Attribute<Sale, String> MAKE =
                new SimpleAttribute<>(Sale.class, String.class, "make") {
                    public String getValue(Sale s, QueryOptions o) { return s.make(); }
                };
        static final Attribute<Sale, String> REGION =
                new SimpleAttribute<>(Sale.class, String.class, "region") {
                    public String getValue(Sale s, QueryOptions o) { return s.region(); }
                };
    }

    private static final String[] REGIONS = {"EMEA", "AMER", "APAC", "LATAM"};
    private static final String[] MAKES = {"Ford", "BMW", "Toyota", "Honda", "Tesla", "Kia", "Audi", "Fiat"};
    private static final String[] COLOURS = {"red", "blue", "black", "white", "silver"};

    /** The panels on the dashboard. Each returns a row count so nothing can be optimised away. */
    private interface Panel { long run(); }

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 5;
        int[] users = {1, 2, 4, 8, 16};

        List<Sale> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            data.add(new Sale(i, REGIONS[i % REGIONS.length], MAKES[(i / 3) % MAKES.length],
                    "model" + (i % 250), COLOURS[(i / 7) % COLOURS.length],
                    5_000 + (i * 7919L) % 60_000, 2000 + (i % 25)));
        }
        System.out.printf("%,d sales. Each 'user' loops over four dashboard panels.%n%n", rows);

        System.out.println("== on-heap CQEngine: the same panels, aggregated in Java ==");
        heap(data, users, seconds);

        for (String threads : new String[]{null, "1", "2", "4"}) {
            System.out.printf("%n== quackjvm, DuckDB threads=%s ==%n",
                    threads == null ? "default" : threads);
            duck(data, users, seconds, threads);
        }
    }

    private static void heap(List<Sale> data, int[] users, long seconds) {
        IndexedCollection<Sale> sales = new ConcurrentIndexedCollection<>();
        sales.addIndex(HashIndex.onAttribute(Sale.MAKE));
        sales.addIndex(HashIndex.onAttribute(Sale.REGION));
        sales.addAll(data);
        Panel[] panels = {
                () -> { // group by make
                    Map<String, double[]> g = new HashMap<>();
                    for (Sale s : sales) {
                        double[] acc = g.computeIfAbsent(s.make(), k -> new double[2]);
                        acc[0]++; acc[1] += s.price();
                    }
                    return g.size();
                },
                () -> { // pivot: make x colour
                    Map<String, Map<String, long[]>> g = new HashMap<>();
                    for (Sale s : sales) {
                        g.computeIfAbsent(s.make(), k -> new HashMap<>())
                         .computeIfAbsent(s.colour(), k -> new long[1])[0]++;
                    }
                    return g.size();
                },
                () -> { // revenue by year for one make
                    String make = MAKES[ThreadLocalRandom.current().nextInt(MAKES.length)];
                    Map<Integer, double[]> g = new HashMap<>();
                    try (ResultSet<Sale> rs = sales.retrieve(equal(Sale.MAKE, make))) {
                        for (Sale s : rs) {
                            g.computeIfAbsent(s.year(), k -> new double[1])[0] += s.price();
                        }
                    }
                    return g.size();
                },
                () -> { // headline numbers for one region
                    String region = REGIONS[ThreadLocalRandom.current().nextInt(REGIONS.length)];
                    long n = 0; double total = 0;
                    try (ResultSet<Sale> rs = sales.retrieve(equal(Sale.REGION, region))) {
                        for (Sale s : rs) { n++; total += s.price(); }
                    }
                    return n + (long) total;
                }};
        report(users, seconds, panels);
        sales.clear();
    }

    private static void duck(List<Sale> data, int[] users, long seconds, String threads) {
        File file = new File(System.getProperty("java.io.tmpdir"), "dashboard-bench.duckdb");
        file.delete();
        new File(file.getPath() + ".wal").delete();
        DuckDBDatabase.Builder builder = DuckDBDatabase.builder().file(file).memoryLimit("2GB");
        if (threads != null) {
            builder.property("threads", threads);
        }
        try (DuckDBDatabase db = builder.build()) {
            IndexedCollection<Sale> sales = db.collection(Sale.ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Sale.class)).build();
            sales.addAll(data);
            String t = db.table(sales);
            Panel[] panels = {
                    () -> db.query("SELECT make, count(*), avg(price) FROM " + t
                            + " GROUP BY 1 ORDER BY 2 DESC").count(),
                    () -> db.query("PIVOT " + t + " ON colour USING count(*) GROUP BY make").count(),
                    () -> db.query("SELECT year, sum(price) FROM " + t
                            + " WHERE make = ? GROUP BY 1 ORDER BY 1",
                            MAKES[ThreadLocalRandom.current().nextInt(MAKES.length)]).count(),
                    () -> db.query("SELECT count(*), avg(price), median(price) FROM " + t
                            + " WHERE region = ?",
                            REGIONS[ThreadLocalRandom.current().nextInt(REGIONS.length)]).count()};
            report(users, seconds, panels);
        }
        file.delete();
        new File(file.getPath() + ".wal").delete();
    }

    private static void report(int[] users, long seconds, Panel[] panels) {
        System.out.printf("  %-7s %14s %12s %12s %12s%n", "users", "panels/s", "p50", "p95", "p99");
        for (int n : users) {
            Conc.Result r = Conc.best(n, 1500, seconds * 1000, 2, thread ->
                    (threadIndex, iteration) -> {
                        // Each user cycles the panels, offset so they are not all on the same one.
                        int panel = Math.floorMod(threadIndex + (int) iteration, panels.length);
                        if (panels[panel].run() < 0) {
                            throw new IllegalStateException();
                        }
                    });
            System.out.printf("  %-7d %14.1f %10.1f ms %10.1f ms %10.1f ms%s%n", n, r.opsPerSecond(),
                    r.percentileMicros(50) / 1000, r.percentileMicros(95) / 1000,
                    r.percentileMicros(99) / 1000, r.errors > 0 ? "  errors=" + r.errors : "");
        }
    }
}
