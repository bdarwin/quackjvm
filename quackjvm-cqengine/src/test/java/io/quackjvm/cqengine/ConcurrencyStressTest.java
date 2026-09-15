package io.quackjvm.cqengine;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Concurrency as correctness rather than as throughput.
 *
 * <p>The benchmarks in {@code bench/} answer how fast things go under load. These answer whether
 * they are still right: whether a reader can see a half-written collection, whether a query running
 * during a bulk load returns something that never existed, whether two writers can lose an object
 * between them, and whether anything deadlocks. Every one is bounded by a timeout, because the
 * failure mode of a concurrency bug here is a hang rather than an exception.</p>
 */
public class ConcurrencyStressTest {

    private static final int TIMEOUT_MS = 180_000;

    private final List<DuckDBDatabase> open = new ArrayList<>();

    @After
    public void closeAll() {
        open.forEach(DuckDBDatabase::close);
    }

    private DuckDBDatabase database() {
        DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("256MB").build();
        open.add(database);
        return database;
    }

    private IndexedCollection<Car> collection(DuckDBDatabase database, String name) {
        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .name(name).columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        return cars;
    }

    private static Car withId(Car car, int id) {
        return new Car(id, car.manufacturer(), car.model(), car.color(), car.doors(),
                car.price(), car.description(), car.registered());
    }

    /** Runs the tasks at once and fails if any threw or if they did not all finish. */
    private void inParallel(int threads, ThrowingIntConsumer task) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int index = t;
            pool.submit(() -> {
                try {
                    go.await();
                    task.accept(index);
                }
                catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue("threads did not finish - a deadlock or a very slow machine",
                pool.awaitTermination(TIMEOUT_MS / 1000, TimeUnit.SECONDS));
        assertEquals("threads failed: " + failures, 0, failures.size());
    }

    private interface ThrowingIntConsumer {
        void accept(int index) throws Exception;
    }

    // ---------- Writers do not lose each other's objects ----------

    @Test(timeout = TIMEOUT_MS)
    public void concurrentWritersToOneCollectionAllLandExactlyOnce() throws Exception {
        IndexedCollection<Car> cars = collection(database(), "car");
        int threads = 8;
        int each = 150;
        List<Car> template = Cars.generate(each, 31);

        inParallel(threads, thread -> {
            for (int i = 0; i < each; i++) {
                cars.add(withId(template.get(i), thread * 10_000 + i));
            }
        });

        assertEquals("every object written by every thread must be there",
                threads * each, cars.size());
        for (int thread = 0; thread < threads; thread++) {
            for (int i = 0; i < each; i++) {
                int id = thread * 10_000 + i;
                assertEquals("object " + id + " must be readable",
                        id, cars.retrieve(equal(Car.CAR_ID, id)).uniqueResult().carId());
            }
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void concurrentBatchWritersAllLand() throws Exception {
        IndexedCollection<Car> cars = collection(database(), "car");
        int threads = 4;
        int each = 500;

        inParallel(threads, thread -> {
            List<Car> batch = new ArrayList<>(each);
            for (int i = 0; i < each; i++) {
                batch.add(withId(Cars.generate(1, thread * 100 + i).get(0), thread * 100_000 + i));
            }
            cars.addAll(batch);
        });

        assertEquals(threads * each, cars.size());
    }

    // ---------- Readers never see a torn state ----------

    @Test(timeout = TIMEOUT_MS)
    public void aReaderNeverSeesAnObjectMissingFromAnIndexItShouldBeIn() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = collection(database, "car");
        cars.addAll(Cars.generate(500, 3));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong checks = new AtomicLong();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService readers = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            readers.submit(() -> {
                while (!stop.get()) {
                    try {
                        // Everything the object table holds must also be in the index, and vice
                        // versa: a torn write would show up as these disagreeing.
                        long all = cars.size();
                        long byIndex;
                        try (ResultSet<Car> results = cars.retrieve(greaterThan(Car.PRICE, -1.0))) {
                            byIndex = results.size();
                        }
                        if (all != byIndex) {
                            throw new AssertionError("object table has " + all
                                    + " but the price index has " + byIndex);
                        }
                        checks.incrementAndGet();
                    }
                    catch (Throwable e) {
                        failures.add(e);
                        return;
                    }
                }
            });
        }
        for (int i = 0; i < 300; i++) {
            cars.add(withId(Cars.generate(1, i).get(0), 800_000 + i));
        }
        stop.set(true);
        readers.shutdown();
        assertTrue(readers.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("a reader saw a torn collection: " + failures, 0, failures.size());
        assertTrue("the readers should have checked plenty of times", checks.get() > 10);
    }

    @Test(timeout = TIMEOUT_MS)
    public void readersAndWritersOnSeparateCollectionsDoNotInterfere() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> read = collection(database, "readonly");
        IndexedCollection<Car> written = collection(database, "written");
        read.addAll(Cars.generate(1_000, 13));

        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    try {
                        if (read.size() != 1_000) {
                            throw new AssertionError("a collection nobody is writing changed size");
                        }
                    }
                    catch (Throwable e) {
                        failures.add(e);
                        return;
                    }
                }
            });
        }
        for (int i = 0; i < 400; i++) {
            written.add(withId(Cars.generate(1, i).get(0), i));
        }
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("" + failures, 0, failures.size());
        assertEquals(400, written.size());
    }

    // ---------- Joins and materializations under write load ----------

    @Test(timeout = TIMEOUT_MS)
    public void joiningWhileTheOtherSideIsBeingWrittenNeverFails() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> left = collection(database, "left");
        IndexedCollection<Car> right = collection(database, "right");
        left.addAll(Cars.generate(400, 5));
        right.addAll(Cars.generate(400, 5));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong joins = new AtomicLong();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            while (!stop.get()) {
                try {
                    joins.addAndGet(database.join(left, right)
                            .on(Car.CAR_ID, Car.CAR_ID).count() >= 0 ? 1 : 0);
                }
                catch (Throwable e) {
                    failures.add(e);
                    return;
                }
            }
        });
        for (int i = 0; i < 200; i++) {
            right.add(withId(Cars.generate(1, i).get(0), 500_000 + i));
        }
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("joining during writes must not fail: " + failures, 0, failures.size());
        assertTrue("some joins should have run", joins.get() > 3);
    }

    @Test(timeout = TIMEOUT_MS)
    public void refreshingAMaterializationDuringWritesAndReadsNeverFails() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = collection(database, "car");
        cars.addAll(Cars.generate(2_000, 17));
        String sql = "SELECT manufacturer, count(*) AS n FROM " + database.table(cars) + " GROUP BY 1";
        DuckDBDatabase.ManagedMaterialization rollup =
                database.materialize("car_rollup").as(sql).build();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    try {
                        database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class);
                        reads.incrementAndGet();
                    }
                    catch (Throwable e) {
                        failures.add(e);
                        return;
                    }
                }
            });
        }
        pool.submit(() -> {
            for (int i = 0; i < 150 && !stop.get(); i++) {
                try {
                    cars.add(withId(Cars.generate(1, i).get(0), 700_000 + i));
                }
                catch (Throwable e) {
                    failures.add(e);
                    return;
                }
            }
        });
        for (int i = 0; i < 10; i++) {
            rollup.refresh();
        }
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals("refreshing under load must not fail anyone: " + failures, 0, failures.size());
        assertTrue(reads.get() > 10);
    }

    // ---------- Shutdown ----------

    @Test(timeout = TIMEOUT_MS)
    public void closingTheDatabaseWhileReadersAreRunningTerminatesCleanly() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("256MB").build();
            IndexedCollection<Car> cars = collection(database, "car");
            cars.addAll(Cars.generate(500, 9));

            AtomicBoolean started = new AtomicBoolean();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    while (true) {
                        try {
                            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                                results.size();
                            }
                            started.set(true);
                        }
                        catch (RuntimeException expectedOnceClosed) {
                            return;     // a closed database rejects work; that is the contract
                        }
                    }
                });
            }
            while (!started.get()) {
                Thread.onSpinWait();
            }
            database.close();
            pool.shutdown();
            assertTrue("readers did not stop after close() - attempt " + attempt,
                    pool.awaitTermination(60, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void manyCollectionsInOneDatabaseBuiltConcurrently() throws Exception {
        DuckDBDatabase database = database();
        int collections = 8;
        List<IndexedCollection<Car>> built = Collections.synchronizedList(new ArrayList<>());

        // Cold start: every thread creates its own tables and indexes at the same moment.
        inParallel(collections, thread -> {
            IndexedCollection<Car> cars = collection(database, "c" + thread);
            cars.addAll(Cars.generate(200, thread));
            built.add(cars);
        });

        assertEquals(collections, built.size());
        for (IndexedCollection<Car> cars : built) {
            assertEquals(200, cars.size());
        }
    }
}
