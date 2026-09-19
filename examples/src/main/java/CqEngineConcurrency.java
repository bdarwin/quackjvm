/*
 * CQEngine collections backed by DuckDB, used from many threads at once - what is safe, what is
 * serialised, and what fails loudly instead of silently.
 *
 *   1. Readers are never blocked by writers. DuckDB gives every request its own snapshot, so a
 *      reader carries on at full speed while others write.
 *   2. Writes to different collections run in parallel, even in one database. Writes to the same
 *      collection take turns. Each collection has its own write lock, because two different tables
 *      cannot conflict.
 *   3. Two writers on the same keys. With the default serializeWrites(true) they take turns and
 *      nothing fails. With serializeWrites(false) some writes fail with a conflict error - loudly,
 *      the whole write rolled back - and the collection is never left inconsistent.
 *   4. One QueryOptions per operation. Sharing one between threads would give them one database
 *      connection, which deadlocks inside DuckDB's JDBC driver; quackjvm refuses it instead.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineConcurrency.java
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.googlecode.cqengine.query.QueryFactory.equal;

public class CqEngineConcurrency {

    public record Order(int orderId, String customer, String status, double amount) {
        static final SimpleAttribute<Order, Integer> ORDER_ID =
                new SimpleAttribute<>(Order.class, Integer.class, "orderId") {
                    public Integer getValue(Order order, QueryOptions options) {
                        return order.orderId();
                    }
                };
        static final Attribute<Order, String> CUSTOMER =
                new SimpleAttribute<>(Order.class, String.class, "customer") {
                    public String getValue(Order order, QueryOptions options) {
                        return order.customer();
                    }
                };
    }

    static Order order(int id) {
        return new Order(id, "c" + (id % 1_000), id % 3 == 0 ? "shipped" : "open", 10 + id % 500);
    }

    static IndexedCollection<Order> orders(DuckDBDatabase database, String name, int preload) {
        IndexedCollection<Order> orders = database.collection(Order.ORDER_ID)
                .name(name).columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
        orders.addIndex(DuckDBIndex.onAttribute(Order.CUSTOMER));
        List<Order> batch = new ArrayList<>(preload);
        for (int id = 0; id < preload; id++) {
            batch.add(order(id));
        }
        if (!batch.isEmpty()) {
            orders.addAll(batch);
        }
        return orders;
    }

    public static void main(String[] args) throws Exception {
        readersAreNeverBlocked();
        writesToDifferentCollectionsRunInParallel();
        twoWritersOnTheSameKeys();
        oneQueryOptionsPerOperation();
    }

    // ---------------------------------------------------------------------------------------------

    static void readersAreNeverBlocked() throws Exception {
        System.out.println("1. Readers are never blocked by writers");
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("1GB").build()) {
            IndexedCollection<Order> orders = orders(database, "orders", 200_000);
            for (int writers : new int[]{0, 4}) {
                Stats readers = run(4, writers, 3,
                        () -> {
                            String customer = "c" + ThreadLocalRandom.current().nextInt(1_000);
                            try (ResultSet<Order> results = orders.retrieve(equal(Order.CUSTOMER, customer))) {
                                results.size();
                            }
                        },
                        new AtomicInteger(1_000_000)::incrementAndGet,
                        id -> orders.add(order(id)));
                System.out.printf("   4 readers, %d writers: %,6.0f reads/s   p50 %5.2f ms   p99 %5.2f ms%s%n",
                        writers, readers.perSecond(), readers.percentile(50), readers.percentile(99),
                        writers == 0 ? "" : String.format("   (writers added %,d orders meanwhile)", readers.sideWork));
            }
        }
        System.out.println("   Reads slow a little because the machine is busier, not because they wait.");
        System.out.println();
    }

    // ---------------------------------------------------------------------------------------------

    static void writesToDifferentCollectionsRunInParallel() throws Exception {
        System.out.println("2. Different collections write in parallel; one collection writes in turn");
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("1GB").build()) {
            IndexedCollection<Order> east = orders(database, "east", 0);
            IndexedCollection<Order> west = orders(database, "west", 0);

            // Two threads, one collection each.
            AtomicInteger eastId = new AtomicInteger(), westId = new AtomicInteger();
            double separate = writeFor(2, thread -> {
                if (thread == 0) {
                    east.add(order(eastId.incrementAndGet()));
                }
                else {
                    west.add(order(westId.incrementAndGet()));
                }
            });
            // Two threads, the same collection.
            AtomicInteger sharedId = new AtomicInteger(1_000_000);
            double shared = writeFor(2, thread -> east.add(order(sharedId.incrementAndGet())));

            System.out.printf("   two threads, two collections, one database: %,5.0f orders/s%n", separate);
            System.out.printf("   two threads, one collection:                %,5.0f orders/s%n", shared);
        }
        System.out.println("   Each collection has its own write lock, so collections sharing a database for");
        System.out.println("   joins do not queue behind each other's writes.");
        System.out.println();
    }

    // ---------------------------------------------------------------------------------------------

    static void twoWritersOnTheSameKeys() throws Exception {
        System.out.println("3. Two writers replacing the same 50 orders, 400 writes each");
        for (boolean serialize : new boolean[]{true, false}) {
            try (DuckDBDatabase database = DuckDBDatabase.builder()
                    .memoryLimit("1GB").serializeWrites(serialize).build()) {
                IndexedCollection<Order> orders = orders(database, "orders", 50);
                AtomicInteger succeeded = new AtomicInteger(), failed = new AtomicInteger();
                List<String> firstError = new ArrayList<>();
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch go = new CountDownLatch(1);
                for (int t = 0; t < 2; t++) {
                    int writer = t;
                    pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < 400; i++) {
                            int id = i % 50;
                            // add() of an existing key replaces the stored order.
                            Order updated = new Order(id, "c" + id, "writer-" + writer, i);
                            try {
                                orders.add(updated);
                                succeeded.incrementAndGet();
                            }
                            catch (RuntimeException e) {
                                failed.incrementAndGet();
                                synchronized (firstError) {
                                    if (firstError.isEmpty()) {
                                        firstError.add(rootMessage(e));
                                    }
                                }
                            }
                        }
                        return null;
                    });
                }
                go.countDown();
                pool.shutdown();
                pool.awaitTermination(2, TimeUnit.MINUTES);

                // Whatever happened, the collection must still hold exactly one of each order.
                int distinct = orders.stream().mapToInt(Order::orderId).distinct().toArray().length;
                System.out.printf("   serializeWrites(%s): %d succeeded, %d failed; afterwards %d orders, %d distinct%n",
                        serialize, succeeded.get(), failed.get(), orders.size(), distinct);
                if (!firstError.isEmpty()) {
                    System.out.println("     a failure looks like: " + firstError.get(0));
                }
            }
        }
        System.out.println("   Turn serializeWrites off only when writers never touch the same keys.");
        System.out.println();
    }

    // ---------------------------------------------------------------------------------------------

    static void oneQueryOptionsPerOperation() throws Exception {
        System.out.println("4. A QueryOptions shared between threads is refused, not deadlocked");
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("1GB").build()) {
            IndexedCollection<Order> orders = orders(database, "orders", 10_000);

            QueryOptions shared = new QueryOptions();          // hoisted out of the loop: the mistake
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> outcomes = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                outcomes.add(pool.submit(() -> {
                    go.await();
                    try {
                        for (int i = 0; i < 100; i++) {
                            try (ResultSet<Order> results =
                                         orders.retrieve(equal(Order.CUSTOMER, "c" + i), shared)) {
                                results.size();
                            }
                        }
                        return "completed";
                    }
                    catch (IllegalStateException refused) {
                        return "refused: " + refused.getMessage().split("\\. ")[0];
                    }
                }));
            }
            go.countDown();
            pool.shutdown();
            for (Future<String> outcome : outcomes) {
                System.out.println("   " + outcome.get(1, TimeUnit.MINUTES));
            }
        }
        System.out.println("   The fix is one QueryOptions per operation; they are cheap to build.");
    }

    // ---------- measurement helpers ----------

    /** Latencies of the read operations, plus how much side work the writers got done. */
    static final class Stats {
        final long[] nanos;
        final double seconds;
        final long sideWork;

        Stats(long[] nanos, double seconds, long sideWork) {
            this.nanos = nanos;
            this.seconds = seconds;
            this.sideWork = sideWork;
        }

        double perSecond() {
            return nanos.length / seconds;
        }

        double percentile(double p) {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            return sorted[(int) Math.min(sorted.length - 1, sorted.length * p / 100)] / 1e6;
        }
    }

    interface IntTask {
        void run(int value) throws Exception;
    }

    interface Task {
        void run() throws Exception;
    }

    /** Readers timed for a few seconds while writers work alongside them. */
    static Stats run(int readers, int writers, int seconds, Task read,
                     java.util.function.IntSupplier nextId, IntTask write) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong sideWork = new AtomicLong();
        List<long[]> perThread = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
        List<Future<long[]>> futures = new ArrayList<>();
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                long[] latencies = new long[1 << 20];
                int n = 0;
                while (!stop.get() && n < latencies.length) {
                    long started = System.nanoTime();
                    read.run();
                    latencies[n++] = System.nanoTime() - started;
                }
                return Arrays.copyOf(latencies, n);
            }));
        }
        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    write.run(nextId.getAsInt());
                    sideWork.incrementAndGet();
                }
                return null;
            });
        }
        long startedAt = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        for (Future<long[]> future : futures) {
            perThread.add(future.get());
        }
        long[] all = perThread.stream().flatMapToLong(Arrays::stream).toArray();
        return new Stats(all, elapsed, sideWork.get());
    }

    /** Operations per second, with the given number of threads writing for two seconds. */
    static double writeFor(int threads, IntTask write) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            pool.submit(() -> {
                while (!stop.get()) {
                    write.run(thread);
                    done.incrementAndGet();
                }
                return null;
            });
        }
        long startedAt = System.nanoTime();
        Thread.sleep(2_000);
        stop.set(true);
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
        return done.get() / ((System.nanoTime() - startedAt) / 1e9);
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage()).lines().findFirst().orElse("");
    }
}

/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 1. Readers are never blocked by writers
 *    4 readers, 0 writers:  3,577 reads/s   p50  1.08 ms   p99  1.72 ms
 *    4 readers, 4 writers:  3,352 reads/s   p50  1.12 ms   p99  2.24 ms   (writers added 4,717 orders meanwhile)
 *    Reads slow a little because the machine is busier, not because they wait.
 *
 * 2. Different collections write in parallel; one collection writes in turn
 *    two threads, two collections, one database: 3,214 orders/s
 *    two threads, one collection:                2,049 orders/s
 *    Each collection has its own write lock, so collections sharing a database for
 *    joins do not queue behind each other's writes.
 *
 * 3. Two writers replacing the same 50 orders, 400 writes each
 *    serializeWrites(true): 800 succeeded, 0 failed; afterwards 50 orders, 50 distinct
 *    serializeWrites(false): 422 succeeded, 378 failed; afterwards 50 orders, 50 distinct
 *      a failure looks like: TransactionContext Error: Conflict on tuple deletion!
 *    Turn serializeWrites off only when writers never touch the same keys.
 *
 * 4. A QueryOptions shared between threads is refused, not deadlocked
 *    refused: A QueryOptions instance is being used by two threads at once (opened on pool-7-thread-2, now used by pool-7-thread-1)
 *    completed
 *    refused: A QueryOptions instance is being used by two threads at once (opened on pool-7-thread-2, now used by pool-7-thread-3)
 *    refused: A QueryOptions instance is being used by two threads at once (opened on pool-7-thread-2, now used by pool-7-thread-4)
 *    The fix is one QueryOptions per operation; they are cheap to build.
 */
