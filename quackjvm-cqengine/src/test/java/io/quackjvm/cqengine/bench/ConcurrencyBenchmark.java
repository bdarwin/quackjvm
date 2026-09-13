package io.quackjvm.cqengine.bench;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.DuckDBFlags;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;

/**
 * Concurrency measurements for the DuckDB persistence: read scaling, write scaling, and the two
 * of them mixed. Run one scenario at a time:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED \
 *      -cp target/test-classes:target/classes:$(cat /tmp/cp.txt) \
 *      io.quackjvm.cqengine.bench.ConcurrencyBenchmark reads
 * </pre>
 *
 * Scenarios: {@code reads}, {@code rawjdbc}, {@code writes}, {@code twotables}, {@code mixed},
 * {@code knobs}, {@code pool}.
 */
public final class ConcurrencyBenchmark {

    private static final int ROWS = 200_000;
    private static final int[] THREAD_COUNTS = {1, 2, 4, 8, 16, 32};

    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "reads" : args[0];
        switch (scenario) {
            case "reads" -> reads();
            case "rawjdbc" -> rawJdbc();
            case "writes" -> writes();
            case "twotables" -> twoTables();
            case "mixed" -> mixed();
            case "knobs" -> knobs();
            case "pool" -> pool();
            case "lockcost" -> lockCost();
            case "conflicts" -> conflicts();
            case "sharedoptions" -> sharedOptions();
            case "closerace" -> closeRace();
            case "scanthreads" -> scanThreads();
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    // ---------- fixtures ----------

    static final class Fx implements AutoCloseable {
        final DuckDBDatabase database;
        final IndexedCollection<Car> cars;

        Fx(DuckDBDatabase database, IndexedCollection<Car> cars) {
            this.database = database;
            this.cars = cars;
        }

        @Override
        public void close() {
            if (database != null) database.close();
        }
    }

    /** A DuckDB-backed collection of {@link #ROWS} cars, columnar, with four indexes. */
    static Fx duck(Properties properties, boolean serializeWrites, int maxPooled, int rows) {
        DuckDBDatabase.Builder builder = DuckDBDatabase.builder()
                .serializeWrites(serializeWrites)
                .maxPooledConnections(maxPooled);
        if (properties != null) builder.properties(properties);
        DuckDBDatabase database = builder.build();
        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .build();
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        cars.addIndex(DuckDBIndex.onAttribute(Car.MODEL));
        if (rows > 0) {
            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
            cars.update(List.of(), Cars.generate(rows, 42), bulk);
        }
        return new Fx(database, cars);
    }

    static IndexedCollection<Car> onHeap(int rows) {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>();
        cars.addIndex(HashIndex.onAttribute(Car.CAR_ID));
        cars.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        cars.addIndex(HashIndex.onAttribute(Car.MODEL));
        if (rows > 0) cars.addAll(Cars.generate(rows, 42));
        return cars;
    }

    static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) p.setProperty(keyValues[i], keyValues[i + 1]);
        return p;
    }

    // ---------- 1. read scaling ----------

    private static void reads() {
        System.out.println("### Read scaling, " + ROWS + " rows, in-memory columnar DuckDB vs on-heap CQEngine");
        System.out.println("cores=" + Runtime.getRuntime().availableProcessors());

        IndexedCollection<Car> heap = onHeap(ROWS);
        System.out.println("\n-- on-heap ConcurrentIndexedCollection, point lookup");
        for (int n : THREAD_COUNTS) {
            System.out.println("   " + Conc.best(n, 1500, 3000, 2, t -> {
                SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                return (thread, i) -> {
                    try (ResultSet<Car> rs = heap.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                        if (rs.uniqueResult() == null) throw new IllegalStateException("miss");
                    }
                };
            }));
        }
        System.out.println("\n-- on-heap ConcurrentIndexedCollection, narrow range on PRICE");
        for (int n : THREAD_COUNTS) {
            System.out.println("   " + Conc.best(n, 1500, 3000, 2, t -> {
                SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                return (thread, i) -> {
                    double lo = r.nextDouble() * 40_000;
                    try (ResultSet<Car> rs = heap.retrieve(between(Car.PRICE, lo, lo + 2.5))) {
                        int c = 0;
                        for (Car car : rs) if (car != null) c++;
                        if (c < 0) throw new IllegalStateException();
                    }
                };
            }));
        }

        for (String threadsSetting : new String[]{"default", "1", "2", "4"}) {
            Properties p = threadsSetting.equals("default") ? null : props("threads", threadsSetting);
            try (Fx fx = duck(p, true, 32, ROWS)) {
                System.out.println("\n-- DuckDB columnar in-memory, duckdb threads=" + threadsSetting + ", point lookup");
                for (int n : THREAD_COUNTS) {
                    System.out.println("   " + Conc.best(n, 2000, 3000, 2, t -> {
                        SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                        return (thread, i) -> {
                            try (ResultSet<Car> rs = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                                if (rs.uniqueResult() == null) throw new IllegalStateException("miss");
                            }
                        };
                    }));
                }
                System.out.println("\n-- DuckDB columnar in-memory, duckdb threads=" + threadsSetting + ", narrow range on PRICE");
                for (int n : THREAD_COUNTS) {
                    System.out.println("   " + Conc.best(n, 2000, 3000, 2, t -> {
                        SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                        return (thread, i) -> {
                            double lo = r.nextDouble() * 40_000;
                            try (ResultSet<Car> rs = fx.cars.retrieve(between(Car.PRICE, lo, lo + 2.5))) {
                                int c = 0;
                                for (Car car : rs) if (car != null) c++;
                                if (c < 0) throw new IllegalStateException();
                            }
                        };
                    }));
                }
                System.out.println("\n-- DuckDB columnar in-memory, duckdb threads=" + threadsSetting
                        + ", aggregate (avg(price) group by manufacturer, full scan)");
                for (int n : new int[]{1, 2, 4, 8, 16}) {
                    System.out.println("   " + Conc.best(n, 2000, 3000, 2, t -> (thread, i) ->
                            fx.database.query("SELECT avg(price) FROM car").scalar(Double.class)));
                }
            }
        }
    }

    // ---------- read scaling anchor: raw DuckDB JDBC ----------

    private static void rawJdbc() throws Exception {
        System.out.println("### Raw DuckDB JDBC anchor: one duplicated connection per thread, no pool");
        Properties p = new Properties();
        p.setProperty("jdbc_stream_results", "true");
        try (DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:", p)) {
            try (java.sql.Statement s = root.createStatement()) {
                s.execute("CREATE TABLE t AS SELECT i AS id, i%50 AS model, i*0.37 AS price,"
                        + " 'abcdefghijklmnop' AS descr FROM range(" + ROWS + ") tbl(i)");
            }
            for (int n : THREAD_COUNTS) {
                System.out.println("   point lookup  " + Conc.best(n, 1500, 3000, 2, t -> {
                    SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                    Connection c;
                    PreparedStatement ps;
                    try {
                        c = root.duplicate();
                        ps = c.prepareStatement("SELECT id, model, price, descr FROM t WHERE id = ?");
                    }
                    catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return (thread, i) -> {
                        ps.setInt(1, r.nextInt(ROWS));
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException("miss");
                        }
                    };
                }));
            }
            System.out.println();
            for (int n : THREAD_COUNTS) {
                System.out.println("   SELECT 1      " + Conc.best(n, 1500, 2000, 2, t -> {
                    Connection c;
                    PreparedStatement ps;
                    try {
                        c = root.duplicate();
                        ps = c.prepareStatement("SELECT 1");
                    }
                    catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return (thread, i) -> {
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException();
                        }
                    };
                }));
            }
            System.out.println();
            for (int n : new int[]{1, 2, 4, 8, 16}) {
                System.out.println("   full scan agg " + Conc.best(n, 2000, 3000, 2, t -> {
                    Connection c;
                    PreparedStatement ps;
                    try {
                        c = root.duplicate();
                        ps = c.prepareStatement("SELECT avg(price) FROM t");
                    }
                    catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return (thread, i) -> {
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new IllegalStateException();
                        }
                    };
                }));
            }
        }
    }

    // ---------- 2. write scaling ----------

    private static void writes() {
        System.out.println("### Write scaling: collection.add() of one object per operation");
        int perThread = 1000;
        // The JIT has to see the whole write path before any number means anything, and a fresh
        // fixture per configuration would otherwise pay that cost in the first measured run.
        try (Fx warm = duck(null, true, 32, 0)) {
            List<Car> pool = Cars.generate(4096, 11);
            for (int i = 0; i < 3000; i++) {
                Car c = pool.get(i % pool.size());
                warm.cars.add(new Car(i, c.manufacturer(), c.model(), c.color(), c.doors(),
                        c.price(), c.description(), c.registered()));
            }
        }
        for (boolean serialize : new boolean[]{true, false}) {
            System.out.println("\n-- DuckDB, serializeWrites=" + serialize);
            for (int n : new int[]{1, 2, 4, 8, 16}) {
                try (Fx fx = duck(null, serialize, 32, 0)) {
                    AtomicInteger ids = new AtomicInteger();
                    List<Car> pool = Cars.generate(4096, 11);
                    // Per-fixture warmup, so the measured run starts on a warm table.
                    Conc.fixedCount(n, 100, t -> (thread, i) -> {
                        Car c = pool.get((int) (i % pool.size()));
                        fx.cars.add(new Car(-1 - ids.incrementAndGet(), c.manufacturer(), c.model(),
                                c.color(), c.doors(), c.price(), c.description(), c.registered()));
                    });
                    Conc.Result result = Conc.fixedCount(n, perThread, t -> (thread, i) -> {
                        Car c = pool.get((int) (i % pool.size()));
                        int id = ids.incrementAndGet();
                        fx.cars.add(new Car(id, c.manufacturer(), c.model(), c.color(), c.doors(),
                                c.price(), c.description(), c.registered()));
                    });
                    System.out.println("   " + result);
                    if (!result.errorKinds.isEmpty()) {
                        System.out.println("      first errors: " + result.errorKinds.subList(0,
                                Math.min(3, result.errorKinds.size())));
                    }
                    long stored = fx.database.query("SELECT count(*) FROM car").scalar(Long.class);
                    System.out.println("      rows stored " + stored + " of "
                            + ((long) n * (perThread + 100)) + " attempted (incl. warmup)");
                }
            }
        }
        System.out.println("\n-- on-heap ConcurrentIndexedCollection, same operation (baseline)");
        for (int n : new int[]{1, 2, 4, 8, 16}) {
            IndexedCollection<Car> heap = onHeap(0);
            AtomicInteger ids = new AtomicInteger();
            List<Car> pool = Cars.generate(4096, 11);
            System.out.println("   " + Conc.fixedCount(n, perThread * 20, t -> (thread, i) -> {
                Car c = pool.get((int) (i % pool.size()));
                int id = ids.incrementAndGet();
                heap.add(new Car(id, c.manufacturer(), c.model(), c.color(), c.doors(),
                        c.price(), c.description(), c.registered()));
            }));
        }
    }

    // ---------- 2b. two collections, one database vs two ----------

    private static void twoTables() {
        System.out.println("### Two collections written by two threads: shared database vs one database each");
        int perThread = 400;
        List<Car> pool = Cars.generate(4096, 11);
        try (Fx warm = duck(null, true, 32, 0)) {
            for (int i = 0; i < 3000; i++) {
                Car c = pool.get(i % pool.size());
                warm.cars.add(new Car(i, c.manufacturer(), c.model(), c.color(), c.doors(),
                        c.price(), c.description(), c.registered()));
            }
        }

        for (boolean serialize : new boolean[]{false, true, false, true}) {
            // Shared database.
            DuckDBDatabase shared = DuckDBDatabase.builder().serializeWrites(serialize).build();
            IndexedCollection<Car> a = shared.collection(Car.CAR_ID).name("cara")
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            IndexedCollection<Car> b = shared.collection(Car.CAR_ID).name("carb")
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            a.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            b.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            AtomicInteger ids = new AtomicInteger();
            Conc.Result sharedResult = Conc.fixedCount(2, perThread, t -> (thread, i) -> {
                Car c = pool.get((int) (i % pool.size()));
                IndexedCollection<Car> target = thread == 0 ? a : b;
                target.add(new Car(ids.incrementAndGet(), c.manufacturer(), c.model(), c.color(),
                        c.doors(), c.price(), c.description(), c.registered()));
            });
            System.out.println("   shared db,  serializeWrites=" + serialize + "  " + sharedResult);
            if (!sharedResult.errorKinds.isEmpty()) {
                System.out.println("      first errors: " + sharedResult.errorKinds.subList(0,
                        Math.min(3, sharedResult.errorKinds.size())));
            }
            shared.close();

            // One database each.
            DuckDBDatabase d1 = DuckDBDatabase.builder().serializeWrites(serialize).build();
            DuckDBDatabase d2 = DuckDBDatabase.builder().serializeWrites(serialize).build();
            IndexedCollection<Car> c1 = d1.collection(Car.CAR_ID).name("cara")
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            IndexedCollection<Car> c2 = d2.collection(Car.CAR_ID).name("carb")
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            c1.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            c2.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            AtomicInteger ids2 = new AtomicInteger();
            Conc.Result splitResult = Conc.fixedCount(2, perThread, t -> (thread, i) -> {
                Car c = pool.get((int) (i % pool.size()));
                IndexedCollection<Car> target = thread == 0 ? c1 : c2;
                target.add(new Car(ids2.incrementAndGet(), c.manufacturer(), c.model(), c.color(),
                        c.doors(), c.price(), c.description(), c.registered()));
            });
            System.out.println("   separate dbs, serializeWrites=" + serialize + "  " + splitResult);
            d1.close();
            d2.close();
        }
    }

    // ---------- 3. mixed read / write ----------

    private static void mixed() throws Exception {
        System.out.println("### Mixed: 4 reader threads, W writer threads, shared collection");
        for (int writers : new int[]{0, 1, 2, 4, 8}) {
            try (Fx fx = duck(null, true, 32, ROWS)) {
                AtomicInteger ids = new AtomicInteger(ROWS);
                List<Car> pool = Cars.generate(4096, 11);
                int readers = 4;
                int total = readers + writers;
                // Warm up.
                Conc.run(total, 1500, t -> op(fx, t, readers, ids, pool));
                Conc.Result combined = Conc.run(total, 4000, t -> op(fx, t, readers, ids, pool));
                // Separate reader latency by running readers only afterwards for contrast.
                System.out.printf("   writers=%d  combined %s%n", writers, combined);
                // Reader-only latency under this write load, measured by a dedicated pass.
                ReadStats rs = readLatencyUnderLoad(fx, readers, writers, ids, pool);
                System.out.printf("      readers: %8.0f ops/s  p50 %7.1f us  p99 %8.1f us"
                                + " | writers: %7.0f ops/s  p50 %8.1f us  p99 %9.1f us  errors %d%n",
                        rs.readOps, rs.readP50, rs.readP99, rs.writeOps, rs.writeP50, rs.writeP99, rs.writeErrors);
            }
        }
    }

    private record ReadStats(double readOps, double readP50, double readP99,
                             double writeOps, double writeP50, double writeP99, long writeErrors) {
    }

    private static Conc.Op op(Fx fx, int thread, int readers, AtomicInteger ids, List<Car> pool) {
        if (thread < readers) {
            SplittableRandom r = new SplittableRandom(thread * 7919L + 1);
            return (t, i) -> {
                try (ResultSet<Car> results = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                    results.uniqueResult();
                }
            };
        }
        return (t, i) -> {
            Car c = pool.get((int) (i % pool.size()));
            fx.cars.add(new Car(ids.incrementAndGet(), c.manufacturer(), c.model(), c.color(),
                    c.doors(), c.price(), c.description(), c.registered()));
        };
    }

    /** Runs readers and writers together but records their latencies separately. */
    private static ReadStats readLatencyUnderLoad(Fx fx, int readers, int writers,
                                                  AtomicInteger ids, List<Car> pool) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        long[][] readSamples = new long[readers][];
        long[][] writeSamples = new long[Math.max(writers, 1)][];
        long[] readCounts = new long[readers];
        long[] writeCounts = new long[Math.max(writers, 1)];
        java.util.concurrent.atomic.AtomicLong writeErrors = new java.util.concurrent.atomic.AtomicLong();
        Thread[] threads = new Thread[readers + writers];
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < readers; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                SplittableRandom r = new SplittableRandom(index * 7919L + 1);
                long[] mine = new long[400_000];
                int at = 0;
                try {
                    go.await();
                }
                catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    long start = System.nanoTime();
                    try (ResultSet<Car> results = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                        results.uniqueResult();
                    }
                    catch (RuntimeException ignored) {
                        // counted as an op; a read failure would show up in the plugin's own tests
                    }
                    if (at < mine.length) mine[at++] = System.nanoTime() - start;
                    readCounts[index]++;
                }
                readSamples[index] = java.util.Arrays.copyOf(mine, at);
            });
        }
        for (int i = 0; i < writers; i++) {
            final int index = i;
            threads[readers + i] = new Thread(() -> {
                long[] mine = new long[400_000];
                int at = 0;
                long iteration = 0;
                try {
                    go.await();
                }
                catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    long start = System.nanoTime();
                    try {
                        Car c = pool.get((int) (iteration % pool.size()));
                        fx.cars.add(new Car(ids.incrementAndGet(), c.manufacturer(), c.model(), c.color(),
                                c.doors(), c.price(), c.description(), c.registered()));
                    }
                    catch (RuntimeException e) {
                        writeErrors.incrementAndGet();
                    }
                    if (at < mine.length) mine[at++] = System.nanoTime() - start;
                    writeCounts[index]++;
                    iteration++;
                }
                writeSamples[index] = java.util.Arrays.copyOf(mine, at);
            });
        }
        for (Thread t : threads) t.start();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(4000);
        stop.set(true);
        for (Thread t : threads) t.join();
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] reads = flatten(readSamples);
        long[] writesArr = flatten(writeSamples);
        long readOps = 0;
        for (long c : readCounts) readOps += c;
        long writeOps = 0;
        for (int i = 0; i < writers; i++) writeOps += writeCounts[i];
        return new ReadStats(readOps / seconds, pct(reads, 50), pct(reads, 99),
                writeOps / seconds, pct(writesArr, 50), pct(writesArr, 99), writeErrors.get());
    }

    private static long[] flatten(long[][] arrays) {
        int total = 0;
        for (long[] a : arrays) total += a == null ? 0 : a.length;
        long[] all = new long[total];
        int at = 0;
        for (long[] a : arrays) {
            if (a != null) {
                System.arraycopy(a, 0, all, at, a.length);
                at += a.length;
            }
        }
        java.util.Arrays.sort(all);
        return all;
    }

    private static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[(int) Math.min(sorted.length - 1L, Math.round(p / 100 * (sorted.length - 1)))] / 1000.0;
    }

    // ---------- 5. DuckDB knobs ----------

    private static void knobs() {
        System.out.println("### DuckDB settings under 8 concurrent readers (point lookup + narrow range)");
        String[][] settings = {
                {},
                {"threads", "1"},
                {"threads", "2"},
                {"threads", "4"},
                {"preserve_insertion_order", "false"},
                {"threads", "2", "preserve_insertion_order", "false"},
                {"memory_limit", "256MB"},
                {"threads", "2", "memory_limit", "256MB"},
        };
        for (String[] setting : settings) {
            Properties p = setting.length == 0 ? null : props(setting);
            try (Fx fx = duck(p, true, 32, ROWS)) {
                Conc.Result point = Conc.best(8, 2000, 3000, 2, t -> {
                    SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                    return (thread, i) -> {
                        try (ResultSet<Car> rs = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                            rs.uniqueResult();
                        }
                    };
                });
                Conc.Result range = Conc.best(8, 2000, 3000, 2, t -> {
                    SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                    return (thread, i) -> {
                        double lo = r.nextDouble() * 40_000;
                        try (ResultSet<Car> rs = fx.cars.retrieve(between(Car.PRICE, lo, lo + 2.5))) {
                            for (Car car : rs) {
                                if (car == null) throw new IllegalStateException();
                            }
                        }
                    };
                });
                System.out.printf("   %-52s point %8.0f ops/s (p99 %7.1f us)   range %8.0f ops/s (p99 %8.1f us)%n",
                        setting.length == 0 ? "(defaults)" : String.join(" ", setting),
                        point.opsPerSecond(), point.percentileMicros(99),
                        range.opsPerSecond(), range.percentileMicros(99));
            }
        }
    }

    // ---------- what pinning DuckDB's thread count costs the analytic queries ----------

    /**
     * Capping {@code threads} is what makes many small concurrent queries fast, but it is also
     * what intra-query parallelism is for. This is the other side of the trade: one big scan over
     * 4M rows, run by one application thread, at each setting.
     */
    private static void scanThreads() {
        System.out.println("### One analytic query over 4M rows, single application thread");
        int rows = 4_000_000;
        for (String setting : new String[]{"default", "1", "2", "4"}) {
            Properties p = setting.equals("default") ? null : props("threads", setting);
            try (Fx fx = duck(p, true, 32, rows)) {
                Conc.Result agg = Conc.best(1, 2000, 4000, 2, t -> (thread, i) ->
                        fx.database.query("SELECT avg(price), count(*) FROM car").scalar(Double.class));
                Conc.Result group = Conc.best(1, 2000, 4000, 2, t -> (thread, i) ->
                        fx.database.query("SELECT manufacturer, avg(price) FROM car GROUP BY 1"
                                + " ORDER BY 2 DESC LIMIT 1").scalar(String.class));
                System.out.printf("   threads=%-8s avg-over-4M %8.0f us    group-by %8.0f us%n",
                        setting, agg.meanMicros(), group.meanMicros());
            }
        }
    }

    // ---------- closing the database while requests are still running ----------

    /**
     * {@code DuckDBDatabase.close()} closes every pooled connection. A request in flight is
     * holding a connection which is not in the idle deque, but a request which releases its
     * connection <em>during</em> the close can have that same connection closed under it.
     * duckdb_jdbc takes its per-connection and per-statement locks in opposite orders on the two
     * paths, so this can hang rather than throw. Reports HUNG and exits if so.
     */
    private static void closeRace() throws Exception {
        System.out.println("### database.close() racing in-flight requests, 20 attempts");
        for (int attempt = 0; attempt < 20; attempt++) {
            Fx fx = duck(null, true, 32, 5_000);
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();
            java.util.Set<String> kinds = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Thread[] readers = new Thread[8];
            for (int i = 0; i < readers.length; i++) {
                final int index = i;
                readers[i] = new Thread(() -> {
                    SplittableRandom r = new SplittableRandom(index * 7919L + 1);
                    while (!stop.get()) {
                        try (ResultSet<Car> rs = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(5_000)))) {
                            rs.uniqueResult();
                        }
                        catch (Throwable e) {
                            failures.incrementAndGet();
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            kinds.add(root.getClass().getSimpleName());
                        }
                    }
                });
                readers[i].setDaemon(true);
                readers[i].start();
            }
            Thread.sleep(200);
            Thread closer = new Thread(fx::close);
            closer.setDaemon(true);
            closer.start();
            closer.join(20_000);
            stop.set(true);
            if (closer.isAlive()) {
                System.out.println("   attempt " + attempt + ": HUNG - close() did not return in 20s");
                Thread.getAllStackTraces().forEach((t, s) -> {
                    if (t.getName().startsWith("Thread-") || t.getName().startsWith("bench")) {
                        System.out.println("   " + t.getName() + " " + t.getState());
                        for (int j = 0; j < Math.min(8, s.length); j++) System.out.println("       " + s[j]);
                    }
                });
                System.exit(1);
            }
            for (Thread t : readers) t.join(10_000);
            long stillAlive = java.util.Arrays.stream(readers).filter(Thread::isAlive).count();
            System.out.println("   attempt " + attempt + ": closed cleanly, reader failures=" + failures.get()
                    + " kinds=" + kinds + " readersStuck=" + stillAlive);
        }
    }

    // ---------- the one way a caller can break the one-thread-per-connection invariant ----------

    /**
     * The statement cache is safe because a pooled connection is borrowed by one thread at a time,
     * and that holds because CQEngine opens a request-scoped connection per {@code QueryOptions}.
     * Hand the <em>same</em> {@code QueryOptions} instance to two threads - which an application
     * does the moment it hoists a {@code QueryOptions} carrying a flag out of its request loop -
     * and both threads share one connection and one {@link io.quackjvm.core.duckdb.StatementCache}.
     * This shows what that costs.
     */
    private static void sharedOptions() throws Exception {
        System.out.println("### Same QueryOptions instance shared between threads");
        try (Fx fx = duck(null, true, 32, 10_000)) {
            for (boolean share : new boolean[]{false, true}) {
                QueryOptions shared = new QueryOptions();
                AtomicInteger failures = new AtomicInteger();
                java.util.Set<String> kinds = java.util.concurrent.ConcurrentHashMap.newKeySet();
                Conc.Result r = Conc.fixedCount(8, 500, t -> {
                    SplittableRandom rnd = new SplittableRandom(t * 7919L + 1);
                    return (thread, i) -> {
                        QueryOptions options = share ? shared : new QueryOptions();
                        try (ResultSet<Car> rs = fx.cars.retrieve(equal(Car.CAR_ID, rnd.nextInt(10_000)), options)) {
                            if (rs.uniqueResult() == null) {
                                failures.incrementAndGet();
                                kinds.add("wrong or missing result");
                            }
                        }
                        catch (Throwable e) {
                            failures.incrementAndGet();
                            Throwable root = e;
                            while (root.getCause() != null) root = root.getCause();
                            kinds.add(root.getClass().getSimpleName() + ": " + root.getMessage());
                        }
                    };
                });
                System.out.printf("   shared QueryOptions=%-5s %s  wrongOrFailed=%d%n", share, r, failures.get());
                if (!kinds.isEmpty()) {
                    System.out.println("      " + kinds.stream().limit(5).toList());
                }
            }
        }
    }

    // ---------- when serializeWrites=false actually fails ----------

    /**
     * The write scaling run never sees a conflict because each thread writes primary keys of its
     * own. This is the case the lock exists for: every thread rewrites the <em>same</em> keys, so
     * two DuckDB transactions touch the same tuple. It reports how often that aborts a write, and
     * with what message, and whether the collection is left consistent afterwards.
     */
    private static void conflicts() {
        System.out.println("### serializeWrites=false with overlapping keys (the case the lock exists for)");
        int keys = 50;
        List<Car> pool = Cars.generate(keys, 11);
        for (boolean serialize : new boolean[]{false, true}) {
            for (int n : new int[]{2, 4, 8}) {
                try (Fx fx = duck(null, serialize, 32, 0)) {
                    fx.cars.addAll(pool);
                    Conc.Result r = Conc.fixedCount(n, 300, t -> (thread, i) -> {
                        Car c = pool.get((int) (i % keys));
                        // Same primary key from every thread: delete-then-insert of one tuple.
                        fx.cars.add(new Car(c.carId(), c.manufacturer(), c.model(), c.color(),
                                c.doors(), c.price() + thread, c.description(), c.registered()));
                    });
                    long stored = fx.database.query("SELECT count(*) FROM car").scalar(Long.class);
                    long distinct = fx.database.query("SELECT count(DISTINCT carId) FROM car").scalar(Long.class);
                    System.out.printf("   serializeWrites=%-5s %s  rows=%d distinctKeys=%d (expected %d/%d)%n",
                            serialize, r, stored, distinct, keys, keys);
                    if (!r.errorKinds.isEmpty()) {
                        System.out.println("      errors seen: " + new java.util.LinkedHashSet<>(r.errorKinds));
                    }
                }
            }
        }
    }

    // ---------- lock fairness cost in isolation ----------

    /**
     * The write lock is a <em>fair</em> {@code ReentrantLock}. Fairness hands the lock to the
     * longest waiter, which forbids barging and so costs a context switch per handover. This
     * measures the lock alone, holding it for roughly as long as a one-object write does (700 us).
     */
    private static void lockCost() {
        System.out.println("### ReentrantLock fair vs unfair, critical section ~700 us of busy work");
        for (boolean fair : new boolean[]{true, false}) {
            java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock(fair);
            for (int n : new int[]{1, 2, 4, 8, 16}) {
                Conc.Result r = Conc.best(n, 1000, 2000, 2, t -> (thread, i) -> {
                    lock.lock();
                    try {
                        busy(700_000);
                    }
                    finally {
                        lock.unlock();
                    }
                });
                System.out.printf("   fair=%-5s %s%n", fair, r);
            }
            System.out.println();
        }
    }

    private static void busy(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    // ---------- pool sizing ----------

    private static void pool() {
        System.out.println("### maxPooledConnections under 16 concurrent readers (point lookup)");
        for (int max : new int[]{0, 1, 2, 4, 8, 16, 32, 64}) {
            try (Fx fx = duck(null, true, max, ROWS)) {
                Conc.Result result = Conc.best(16, 2000, 3000, 2, t -> {
                    SplittableRandom r = new SplittableRandom(t * 7919L + 1);
                    return (thread, i) -> {
                        try (ResultSet<Car> rs = fx.cars.retrieve(equal(Car.CAR_ID, r.nextInt(ROWS)))) {
                            rs.uniqueResult();
                        }
                    };
                });
                System.out.printf("   maxPooled=%-3d %s%n", max, result);
            }
        }
    }
}
