/*
 * The core module used from many threads at once. No CQEngine involved.
 *
 *   1. Dashboard users. Eight people refreshing the same GROUP BY panels over five million rows.
 *      DuckDB runs every query on many threads, so by default eight concurrent queries fight over
 *      the same cores. Setting threads to half the cores is the cheapest fix; a semaphore in front
 *      of the queries trims the tail further, for a little throughput.
 *   2. Your own transactions. Two threads doing read-modify-write on the same rows: one of them
 *      gets a conflict, is rolled back and retries, and no update is lost. Begin and end them with
 *      Transactions (SQL BEGIN/COMMIT), not setAutoCommit/commit - after a failed commit(), DuckDB's
 *      JDBC driver goes on to commit statements one at a time while reporting failure.
 *   3. SparseTable. A writer replacing records and a reader reading while optimize() reorders the
 *      whole table. Through the writer's own instance they take turns; through a second instance
 *      they conflict and optimize() fails, loudly and rolled back. Nothing is lost either way.
 *
 * One connection per thread, each from DuckDBConnection.duplicate() - a DuckDB connection is not
 * safe to share between threads.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreConcurrency.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.Transactions;
import io.quackjvm.core.sparse.SparseBatch;
import io.quackjvm.core.sparse.SparseRecord;
import io.quackjvm.core.sparse.SparseTable;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CoreConcurrency {

    static final int CORES = Runtime.getRuntime().availableProcessors();

    /** The panels a dashboard shows: each user refreshes one of these at random. */
    static final String[] PANELS = {
            "SELECT region, make, sum(price) AS revenue FROM sale GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 10",
            "SELECT year, count(*), avg(price) FROM sale GROUP BY 1 ORDER BY 1",
            "SELECT make, quantile_cont(price, 0.9) FROM sale WHERE region = 'EMEA' GROUP BY 1",
            "SELECT region, count(DISTINCT customer) FROM sale GROUP BY 1",
    };

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("memory_limit", "2GB");
        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", properties)) {
            dashboardUsers(connection);
            yourOwnTransactions(connection);
            sparseTableUnderLoad(connection);
        }
    }

    // ---------------------------------------------------------------------------------------------

    static void dashboardUsers(DuckDBConnection connection) throws Exception {
        Sql.execute(connection, """
                CREATE TABLE sale AS
                SELECT i AS sale_id,
                       ['EMEA','AMER','APAC','LATAM','ANZ','MEA'][1 + i % 6] AS region,
                       ['Ford','BMW','Toyota','Honda','Tesla','Kia','Audi','Fiat'][1 + (i // 7) % 8] AS make,
                       2015 + i % 10 AS year,
                       i % 200000 AS customer,
                       10000 + (i * 7919) % 50000 AS price
                FROM range(5000000) t(i)""");
        System.out.printf("1. Eight users refreshing dashboard panels over 5,000,000 rows (%d cores)%n", CORES);

        report("threads = " + CORES + " (DuckDB's default)", dashboard(connection, CORES, 0));
        report("threads = " + CORES / 2, dashboard(connection, CORES / 2, 0));
        report("threads = " + CORES / 2 + ", at most 4 panels at once", dashboard(connection, CORES / 2, 4));
        // threads is global to the database, not per connection - put it back for what follows.
        Sql.execute(connection, "SET threads = " + CORES);
        System.out.println("   threads is a database-wide setting: SET on any connection changes them all.");
        System.out.println();
    }

    static Latencies dashboard(DuckDBConnection connection, int threads, int admitted) throws Exception {
        Sql.execute(connection, "SET threads = " + threads);
        Semaphore admission = admitted > 0 ? new Semaphore(admitted, true) : null;
        return during(3, 8, connection, own -> {
            String panel = PANELS[ThreadLocalRandom.current().nextInt(PANELS.length)];
            if (admission != null) {
                admission.acquire();
            }
            try (Statement statement = own.createStatement(); ResultSet rows = statement.executeQuery(panel)) {
                while (rows.next()) {
                    // Drain it, as a dashboard would.
                }
            }
            finally {
                if (admission != null) {
                    admission.release();
                }
            }
        });
    }

    static void report(String label, Latencies latencies) {
        System.out.printf("   %-40s %5.1f panels/s   p50 %6.1f ms   p99 %6.1f ms%n",
                label, latencies.perSecond(), latencies.percentile(50), latencies.percentile(99));
    }

    // ---------------------------------------------------------------------------------------------

    static void yourOwnTransactions(DuckDBConnection connection) throws Exception {
        System.out.println("2. Two threads moving money between the same 10 accounts, 2,000 transfers each");
        Sql.execute(connection, "CREATE TABLE account (id INTEGER PRIMARY KEY, balance BIGINT)");
        Sql.execute(connection, "INSERT INTO account SELECT i, 1000 FROM range(10) t(i)");

        AtomicInteger committed = new AtomicInteger(), conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            done.add(pool.submit(() -> {
                try (Connection own = connection.duplicate()) {
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 2_000; i++) {
                        int from = random.nextInt(10), to = (from + 1 + random.nextInt(9)) % 10;
                        while (true) {
                            // The connection stays in auto-commit mode; the transaction is SQL.
                            Transactions.begin(own);
                            try {
                                long balance = Sql.queryLong(own,
                                        "SELECT balance FROM account WHERE id = ?", List.of(from));
                                Sql.executeUpdate(own, "UPDATE account SET balance = ? WHERE id = ?",
                                        List.of(balance - 1, from));
                                Sql.executeUpdate(own, "UPDATE account SET balance = balance + 1 WHERE id = ?",
                                        List.of(to));
                                Transactions.commit(own);
                                committed.incrementAndGet();
                                break;
                            }
                            catch (RuntimeException conflict) {
                                // Another transfer touched the same account first. Nothing of this
                                // one was applied; roll back and try again.
                                Transactions.rollbackQuietly(own);
                                conflicts.incrementAndGet();
                            }
                        }
                    }
                }
                return null;
            }));
        }
        for (Future<?> future : done) {
            future.get();
        }
        pool.shutdown();

        long total = Sql.queryLong(connection, "SELECT sum(balance) FROM account", List.of());
        System.out.printf("   %,d committed, %,d conflicts rolled back and retried%n", committed.get(), conflicts.get());
        System.out.printf("   money in the system: %,d (started with 10,000)%n", total);
        System.out.println();
    }

    // ---------------------------------------------------------------------------------------------

    static void sparseTableUnderLoad(DuckDBConnection connection) throws Exception {
        System.out.println("3. SparseTable: a writer and a reader running while optimize() reorders the table");
        SparseTable readings = SparseTable.named("reading");
        readings.create(connection);

        // 2,000 records, each with 50 of 2,000 possible data points.
        SparseBatch.Builder initial = SparseBatch.builder();
        for (int record = 0; record < RECORDS; record++) {
            initial.record(record);
            for (int k = 0; k < 50; k++) {
                initial.put("sensor_" + (record * 37 + k * 41) % 2_000, k);
            }
        }
        readings.append(connection, initial.build());
        System.out.printf("   loaded %,d records, %,d values%n",
                readings.recordCount(connection), readings.valueCount(connection));

        // The writer's own instance: optimize() takes its lock, so the writer waits for it.
        optimizeUnderLoad(connection, readings, readings, "through the writer's instance");
        // A second instance, as another part of the application might have. optimize() deletes
        // every row and so does a replace, so the two conflict: one fails, loudly and rolled back.
        optimizeUnderLoad(connection, readings, SparseTable.named("reading"), "through a second instance");
        System.out.println("   Either way nothing is lost. Run optimize() through the instance your writers use.");
    }

    static final int RECORDS = 2_000;

    static void optimizeUnderLoad(DuckDBConnection connection, SparseTable writes, SparseTable optimizes,
                                  String label) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        // What each record should hold at the end: the value its last successful replace gave it.
        double[] expected = new double[RECORDS];
        Arrays.fill(expected, Double.NaN);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> writer = pool.submit(() -> {
            int replaced = 0;
            try (Connection own = connection.duplicate()) {
                for (int n = 0; !stop.get(); n++) {
                    int record = n % RECORDS;
                    writes.replace(own, SparseBatch.builder().record(record).put("sensor_0", n).put("checked", 1).build());
                    expected[record] = n;
                    replaced++;
                }
            }
            return replaced;
        });
        Future<?> reader = pool.submit(() -> {
            try (Connection own = connection.duplicate()) {
                while (!stop.get()) {
                    writes.read(own, ThreadLocalRandom.current().nextInt(RECORDS));
                    reads.incrementAndGet();
                }
            }
            return null;
        });

        int succeeded = 0;
        String failure = null;
        try (Connection own = connection.duplicate()) {
            for (int i = 0; i < 5; i++) {
                try {
                    optimizes.optimize(own);
                    succeeded++;
                }
                catch (IllegalStateException conflict) {
                    failure = conflict.getMessage();
                }
            }
        }
        finally {
            // Whatever happened above, stop the workers - or the program never ends.
            stop.set(true);
            pool.shutdown();
        }
        int replaced = writer.get();
        reader.get();

        int checked = 0, lost = 0;
        for (int record = 0; record < RECORDS; record++) {
            if (!Double.isNaN(expected[record])) {
                checked++;
                SparseRecord stored = writes.read(connection, record);
                if (stored.size() != 2 || stored.get("sensor_0").orElse(Double.NaN) != expected[record]) {
                    lost++;
                }
            }
        }
        System.out.printf("   %s:%n", label);
        System.out.printf("     optimize: %d of 5 succeeded%s%n", succeeded,
                failure == null ? "" : " - \"" + failure + "\"");
        System.out.printf("     meanwhile %,d replaces and %,d reads; records holding exactly their last replace: %,d of %,d%n",
                replaced, reads.get(), checked - lost, checked);
    }

    // ---------- measurement helpers ----------

    interface Operation {
        void run(Connection connection) throws Exception;
    }

    record Latencies(long[] nanos, double seconds) {
        double perSecond() {
            return nanos.length / seconds;
        }

        double percentile(double p) {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            return sorted[(int) Math.min(sorted.length - 1, sorted.length * p / 100)] / 1e6;
        }
    }

    /** Runs the operation on the given number of threads, each with its own connection, timing each call. */
    static Latencies during(int seconds, int threads, DuckDBConnection connection, Operation operation)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> futures = new ArrayList<>();
        long startedAt = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                long[] latencies = new long[1 << 16];
                int n = 0;
                try (Connection own = connection.duplicate()) {
                    while (!stop.get() && n < latencies.length) {
                        long started = System.nanoTime();
                        operation.run(own);
                        latencies[n++] = System.nanoTime() - started;
                    }
                }
                return Arrays.copyOf(latencies, n);
            }));
        }
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        double elapsed = (System.nanoTime() - startedAt) / 1e9;
        pool.shutdown();
        List<long[]> all = new ArrayList<>();
        for (Future<long[]> future : futures) {
            all.add(future.get());
        }
        return new Latencies(all.stream().flatMapToLong(Arrays::stream).toArray(), elapsed);
    }
}

/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 1. Eight users refreshing dashboard panels over 5,000,000 rows (10 cores)
 *    threads = 10 (DuckDB's default)           71.2 panels/s   p50   97.7 ms   p99  431.0 ms
 *    threads = 5                               70.0 panels/s   p50  101.7 ms   p99  357.5 ms
 *    threads = 5, at most 4 panels at once     58.1 panels/s   p50  136.2 ms   p99  301.5 ms
 *    threads is a database-wide setting: SET on any connection changes them all.
 *
 * 2. Two threads moving money between the same 10 accounts, 2,000 transfers each
 *    4,000 committed, 1,523 conflicts rolled back and retried
 *    money in the system: 10,000 (started with 10,000)
 *
 * 3. SparseTable: a writer and a reader running while optimize() reorders the table
 *    loaded 2,000 records, 100,000 values
 *    through the writer's instance:
 *      optimize: 5 of 5 succeeded
 *      meanwhile 5 replaces and 68 reads; records holding exactly their last replace: 5 of 5
 *    through a second instance:
 *      optimize: 0 of 5 succeeded - "Failed to optimize reading after 10 attempts"
 *      meanwhile 1,141 replaces and 1,197 reads; records holding exactly their last replace: 1,141 of 1,141
 *    Either way nothing is lost. Run optimize() through the instance your writers use.
 */
