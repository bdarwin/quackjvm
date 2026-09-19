/*
 * The quackjvm dashboard, watching an application that chokes in a different way every half
 * minute. Open the printed address in a browser and watch the diagnosis change with the load.
 *
 *   quiet          one user reading and writing now and then - nothing to report
 *   write lock     eight threads adding objects one at a time to the same collection
 *   pivots         twenty users refreshing pivot dashboards over five million sales, and a
 *                  wide view of sparse sensor data pivoted from ten million long rows
 *   literal SQL    queries built by pasting values into the SQL instead of binding them
 *
 * Starting a dashboard is one line; everything else here is the load.
 *
 *   QuackDashboard dashboard = QuackDashboard.start(database.metrics(), 8090);
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/DashboardDemo.java [port] [secondsPerPhase]
 *
 * Stop it with Ctrl-C.
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.dashboard.QuackDashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.equal;

public class DashboardDemo {

    public record Order(int orderId, String customer, double amount) {
        static final SimpleAttribute<Order, Integer> ORDER_ID =
                new SimpleAttribute<>(Order.class, Integer.class, "orderId") {
                    public Integer getValue(Order order, QueryOptions options) {
                        return order.orderId();
                    }
                };
    }

    interface Work {
        void run() throws Exception;
    }

    record Phase(String name, int threads, Work work) {
    }

    /** Sparse data shown wide: 40 of 2,000 possible sensors as columns, one row per record. */
    static final String SPARSE_WIDE = sparseWide();

    /** The panels of a sales dashboard, and the sparse wide view. Each takes one parameter. */
    static final String[] PIVOTS = {
            // Regions as columns.
            "PIVOT (SELECT make, year, region, price FROM sale WHERE year >= ?)"
                    + " ON region IN ('EMEA', 'AMER', 'APAC', 'LATAM', 'ANZ', 'MEA')"
                    + " USING sum(price) GROUP BY make, year ORDER BY make, year",
            // Months as columns, the conditional-aggregate way.
            monthsAsColumns(),
            // A join to a dimension, then channels as columns with two measures each.
            "PIVOT (SELECT c.segment, c.tier, s.channel, s.price FROM sale s JOIN customer c ON c.id = s.customer"
                    + " WHERE s.year >= ?) ON channel IN ('web', 'store', 'partner', 'phone')"
                    + " USING sum(price) AS revenue, count(*) AS sales GROUP BY segment, tier",
            // Top five models in each region, by a window over an aggregate.
            "SELECT * FROM (SELECT region, model, sum(price * qty) AS revenue,"
                    + " rank() OVER (PARTITION BY region ORDER BY sum(price * qty) DESC) AS r"
                    + " FROM sale WHERE year >= ? GROUP BY region, model) WHERE r <= 5 ORDER BY region, r",
            SPARSE_WIDE,
    };

    static String monthsAsColumns() {
        StringBuilder sql = new StringBuilder("SELECT country, channel");
        for (int month = 1; month <= 12; month++) {
            sql.append(", sum(price * qty) FILTER (WHERE month = ").append(month).append(") AS m").append(month);
        }
        return sql.append(" FROM sale WHERE year = ? GROUP BY ALL ORDER BY country, channel").toString();
    }

    /**
     * What SparseTable.viewSql writes - one max(value) FILTER per sensor shown, only those sensors'
     * rows read, grouped by record - with a page of 100 records. Leaving out the attr IN filter
     * makes DuckDB group all ten million rows to show 40 sensors: 320 ms instead of 40, measured.
     */
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

    /**
     * Five million sales with a customer dimension, and sparse sensor readings stored long - 200,000
     * records with 50 of 2,000 sensors each, ten million rows - in SparseTable's layout.
     */
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

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int secondsPerPhase = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int cores = Runtime.getRuntime().availableProcessors();

        DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("3GB").build();
        IndexedCollection<Order> orders = database.collection(Order.ORDER_ID).name("orders")
                .columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
        IndexedCollection<Order> refunds = database.collection(Order.ORDER_ID).name("refunds")
                .columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
        createData(database);

        QuackDashboard dashboard = QuackDashboard.builder(database.metrics())
                .port(port).title("DashboardDemo").start();
        System.out.println("Dashboard: " + dashboard.url());

        AtomicInteger ids = new AtomicInteger();
        List<Phase> phases = List.of(
                new Phase("quiet", 1, () -> {
                    int id = ids.incrementAndGet();
                    orders.add(new Order(id, "c" + id % 100, id));
                    try (ResultSet<Order> found = orders.retrieve(equal(Order.ORDER_ID, id))) {
                        found.size();
                    }
                    Thread.sleep(20);
                }),
                new Phase("write lock", 8, () -> {
                    int id = ids.incrementAndGet();
                    orders.add(new Order(id, "c" + id % 100, id));
                    if (id % 20 == 0) {
                        refunds.add(new Order(id, "c", -id));
                    }
                }),
                new Phase("pivots", 2 * cores, () -> {
                    // Each user refreshes one panel at random; every row is read, as a page would.
                    String panel = PIVOTS[ThreadLocalRandom.current().nextInt(PIVOTS.length)];
                    int year = 2020 + ThreadLocalRandom.current().nextInt(5);
                    database.query(panel, SPARSE_WIDE.equals(panel) ? 0 : year).forEachRow(row -> {
                    });
                }),
                new Phase("literal SQL", 2, () -> {
                    int id = ThreadLocalRandom.current().nextInt(5_000_000);
                    database.query("SELECT price FROM sale WHERE id = " + id).count();
                }));

        while (true) {
            for (Phase phase : phases) {
                System.out.printf("%tT  %s (%d threads)%n", System.currentTimeMillis(), phase.name(), phase.threads());
                run(phase, secondsPerPhase);
            }
        }
    }

    static void run(Phase phase, int seconds) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(phase.threads());
        List<Throwable> failures = new ArrayList<>();
        for (int t = 0; t < phase.threads(); t++) {
            pool.submit(() -> {
                try {
                    while (!stop.get()) {
                        phase.work().run();
                    }
                }
                catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
        }
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        if (!failures.isEmpty()) {
            System.out.println("  failed: " + failures.get(0));
        }
    }
}
