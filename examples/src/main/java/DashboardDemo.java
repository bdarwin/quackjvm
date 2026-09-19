/*
 * The quackjvm dashboard, watching an application that chokes in a different way every half
 * minute. Open the printed address in a browser and watch the diagnosis change with the load.
 *
 *   quiet          one user reading and writing now and then - nothing to report
 *   write lock     eight threads adding objects one at a time to the same collection
 *   heavy queries  twenty users running heavy GROUP BY reports over two million rows
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

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int secondsPerPhase = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        int cores = Runtime.getRuntime().availableProcessors();

        DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("2GB").build();
        IndexedCollection<Order> orders = database.collection(Order.ORDER_ID).name("orders")
                .columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
        IndexedCollection<Order> refunds = database.collection(Order.ORDER_ID).name("refunds")
                .columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
        database.materialize("sale").as("SELECT i AS id, i % 5000 AS customer, i % 40 AS region,"
                + " (i * 7919) % 50000 AS price FROM range(2000000) t(i)").build();

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
                new Phase("heavy queries", 2 * cores, () ->
                        database.query("SELECT region, count(DISTINCT customer), quantile_cont(price, 0.9)"
                                + " FROM sale GROUP BY 1").count()),
                new Phase("literal SQL", 2, () -> {
                    int id = ThreadLocalRandom.current().nextInt(2_000_000);
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
