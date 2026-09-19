package io.quackjvm.cqengine;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.metrics.Diagnosis;
import io.quackjvm.core.metrics.MetricsSnapshot;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Chokes quackjvm on purpose, one cause at a time, and checks that {@link Diagnosis} names that
 * cause first. If the metrics could not tell these apart, a dashboard built on them would only be
 * decoration.
 */
public class MetricsDiagnosisTest {

    public record Order(int orderId, String customer, double amount) {
        static final SimpleAttribute<Order, Integer> ORDER_ID =
                new SimpleAttribute<>(Order.class, Integer.class, "orderId") {
                    public Integer getValue(Order order, QueryOptions options) {
                        return order.orderId();
                    }
                };
        static final SimpleAttribute<Order, String> CUSTOMER =
                new SimpleAttribute<>(Order.class, String.class, "customer") {
                    public String getValue(Order order, QueryOptions options) {
                        return order.customer();
                    }
                };
    }

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    private DuckDBDatabase database;

    @After
    public void close() {
        if (database != null) {
            database.close();
        }
    }

    private IndexedCollection<Order> orders(String name) {
        return database.collection(Order.ORDER_ID).name(name)
                .columnarLayout(ColumnarLayout.ofRecord(Order.class)).build();
    }

    @Test(timeout = 120_000)
    public void manyThreadsAddingToOneCollectionQueueForItsLock() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("1GB").build();
        IndexedCollection<Order> orders = orders("orders");
        AtomicInteger ids = new AtomicInteger();

        List<Diagnosis.Finding> findings = diagnoseWhile(8, () -> {
            int id = ids.incrementAndGet();
            orders.add(new Order(id, "c" + id % 100, id));
        });

        assertTopCause(Diagnosis.Cause.WRITE_LOCK, findings);
        assertTrue(findings.get(0).headline(), findings.get(0).headline().contains("orders"));
    }

    @Test(timeout = 120_000)
    public void manyUsersRunningHeavyAggregatesCompeteForCpu() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("2GB").build();
        database.materialize("sale").as("SELECT i AS id, i % 1000 AS customer, i % 97 AS region,"
                + " (i * 7919) % 50000 AS price FROM range(3000000) t(i)").build();

        List<Diagnosis.Finding> findings = diagnoseWhile(Math.max(4, 2 * CORES), () ->
                database.query("SELECT region, count(DISTINCT customer), quantile_cont(price, 0.9)"
                        + " FROM sale GROUP BY 1").count());

        assertTopCause(Diagnosis.Cause.CPU, findings);
    }

    @Test(timeout = 120_000)
    public void unserialisedWritersOnTheSameKeysConflict() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("1GB").serializeWrites(false).build();
        IndexedCollection<Order> orders = orders("orders");
        orders.addIndex(io.quackjvm.cqengine.index.DuckDBIndex.onAttribute(Order.CUSTOMER));
        for (int id = 0; id < 20; id++) {
            orders.add(new Order(id, "c", id));
        }
        AtomicInteger n = new AtomicInteger();

        List<Diagnosis.Finding> findings = diagnoseWhile(4, () -> {
            int i = n.incrementAndGet();
            try {
                // A random one of three keys, so that writers do land on the same key at once;
                // a shared counter would hand concurrent writers neighbouring keys and never clash.
                orders.add(new Order(java.util.concurrent.ThreadLocalRandom.current().nextInt(3), "c" + i, i));
            }
            catch (RuntimeException expected) {
                // The conflict is what is being diagnosed.
            }
        });

        assertTopCause(Diagnosis.Cause.CONFLICTS, findings);
    }

    @Test(timeout = 120_000)
    public void sqlWithValuesWrittenIntoItIsPreparedEveryTime() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("1GB").build();
        database.materialize("customer").as("SELECT i AS id, 'name' || i AS name FROM range(1000) t(i)").build();
        AtomicInteger n = new AtomicInteger();

        List<Diagnosis.Finding> findings = diagnoseWhile(2, () ->
                database.query("SELECT name FROM customer WHERE id = " + n.incrementAndGet() % 1000
                        + " AND name <> 'x" + n.get() + "'").count());

        assertTopCause(Diagnosis.Cause.PREPARES, findings);
    }

    @Test(timeout = 120_000)
    public void aPoolTooSmallForItsThreadsOpensConnectionsAllTheTime() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("1GB").maxPooledConnections(0).build();
        database.materialize("customer").as("SELECT i AS id, 'name' || i AS name FROM range(1000) t(i)").build();
        AtomicInteger n = new AtomicInteger();

        List<Diagnosis.Finding> findings = diagnoseWhile(2, () ->
                database.query("SELECT name FROM customer WHERE id = ?", n.incrementAndGet() % 1000).count());

        assertTopCause(Diagnosis.Cause.CONNECTION_CHURN, findings);
    }

    @Test(timeout = 120_000)
    public void sortsLargerThanTheMemoryLimitSpillToDisk() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("200MB").property("threads", "2")
                .file(java.nio.file.Files.createTempDirectory("quackjvm-spill").resolve("db.duckdb").toFile())
                .build();
        database.materialize("event").as("SELECT i AS id, md5(i::VARCHAR) AS payload FROM range(4000000) t(i)").build();

        List<Diagnosis.Finding> findings = diagnoseWhile(2, () ->
                database.query("SELECT payload FROM event ORDER BY payload OFFSET 3999990").count());

        assertTopCause(Diagnosis.Cause.MEMORY, findings);
    }

    @Test(timeout = 120_000)
    public void aLightLoadHasNothingToReport() throws Exception {
        database = DuckDBDatabase.builder().memoryLimit("1GB").build();
        IndexedCollection<Order> orders = orders("orders");
        database.materialize("customer").as("SELECT i AS id, 'name' || i AS name FROM range(1000) t(i)").build();
        AtomicInteger n = new AtomicInteger();

        List<Diagnosis.Finding> findings = diagnoseWhile(1, () -> {
            int i = n.incrementAndGet();
            orders.add(new Order(i, "c", i));
            database.query("SELECT name FROM customer WHERE id = ?", i % 1000).count();
            Thread.sleep(2);
        });

        assertEquals("" + findings, 0, findings.size());
    }

    // ---------------------------------------------------------------------------------------------

    interface Operation {
        void run() throws Exception;
    }

    /**
     * Runs the operation on the given number of threads, and diagnoses the interval while they are
     * still running - as a dashboard polling a live system would.
     */
    private List<Diagnosis.Finding> diagnoseWhile(int threads, Operation operation) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> running = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            running.add(pool.submit(() -> {
                try {
                    while (!stop.get()) {
                        operation.run();
                    }
                }
                catch (Throwable e) {
                    failures.add(e);
                }
            }));
        }
        MetricsSnapshot interval;
        try {
            // Warm up past JIT and first prepares, then measure.
            Thread.sleep(500);
            MetricsSnapshot before = database.metrics().snapshot();
            Thread.sleep(2_000);
            interval = database.metrics().snapshot().minus(before);
        }
        finally {
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        }
        assertEquals("" + failures, 0, failures.size());
        List<Diagnosis.Finding> findings = Diagnosis.of(interval);
        System.out.printf("--- %d threads: CPU %.0f%%, %.1f statements at once%n", threads,
                interval.cpuUtilisation() * 100, interval.statementConcurrency());
        findings.forEach(System.out::println);
        return findings;
    }

    private static void assertTopCause(Diagnosis.Cause expected, List<Diagnosis.Finding> findings) {
        assertTrue("expected " + expected + " but nothing was found", !findings.isEmpty());
        assertEquals("" + findings, expected, findings.get(0).cause());
    }
}
