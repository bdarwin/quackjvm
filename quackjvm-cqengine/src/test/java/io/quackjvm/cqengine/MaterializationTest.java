package io.quackjvm.cqengine;

import com.googlecode.cqengine.IndexedCollection;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MaterializationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

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

    private IndexedCollection<Car> cars(DuckDBDatabase database, int count) {
        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        cars.addAll(Cars.generate(count, 1));
        return cars;
    }

    private String rollupSql(DuckDBDatabase database, IndexedCollection<Car> cars) {
        return "SELECT manufacturer, count(*) AS n, sum(price) AS total FROM "
                + database.table(cars) + " GROUP BY 1";
    }

    @Test
    public void aMaterializationIsBuiltAndQueryableAsATable() {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 500);

        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();

        assertTrue(rollup.exists());
        assertNotNull("building it records when", rollup.getBuiltAt());
        assertTrue(rollup.rowCount() > 0);

        // The point of a table rather than a cached result: it can still be sliced.
        long fromBase = database.query("SELECT count(*) FROM " + database.table(cars)).scalar(Long.class);
        long fromRollup = database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class);
        assertEquals("the rollup must account for every object", fromBase, fromRollup);
        assertTrue(database.query("SELECT n FROM car_rollup WHERE manufacturer = ?", "Ford")
                .scalarOptional(Long.class).orElse(0L) > 0);
    }

    @Test
    public void buildingTwiceDoesNotRebuild() {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 100);
        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();
        long before = rollup.rowCount();

        // A second build() over an existing table leaves it alone, however stale.
        cars.addAll(Cars.generate(50, 99));
        DuckDBDatabase.ManagedMaterialization again = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();

        assertEquals("createIfAbsent must not rebuild", before, again.rowCount());
    }

    @Test
    public void refreshPicksUpChangesToTheBaseData() {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 200);
        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();
        long before = database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class);

        cars.addAll(Cars.generate(100, 7).stream()
                .map(c -> new Car(c.carId() + 100_000, c.manufacturer(), c.model(), c.color(),
                        c.doors(), c.price(), c.description(), c.registered()))
                .toList());

        assertEquals("still stale before a refresh", before,
                (long) database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class));
        rollup.refresh();
        assertEquals("refreshed", before + 100,
                (long) database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class));
    }

    @Test
    public void staleness() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 50);
        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();

        assertFalse(rollup.isOlderThan(Duration.ofMinutes(5)));
        Thread.sleep(30);
        assertTrue(rollup.isOlderThan(Duration.ofMillis(10)));
        rollup.refreshIfOlderThan(Duration.ofMillis(10));
        assertFalse(rollup.isOlderThan(Duration.ofMinutes(5)));
    }

    @Test
    public void dropRemovesItAndForgetsIt() {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 50);
        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();
        assertTrue(database.getMaterializations().containsKey("car_rollup"));

        rollup.drop();
        assertFalse(rollup.exists());
        assertNull(rollup.getBuiltAt());
        assertFalse(database.getMaterializations().containsKey("car_rollup"));
    }

    /**
     * The reason this is in the library at all. Refreshing by dropping and recreating leaves a
     * window in which the table is gone: measured at 1,280 reader failures over fifteen refreshes.
     * Building aside and swapping inside one transaction must leave none.
     */
    @Test(timeout = 120_000)
    public void refreshingNeverLetsAReaderSeeTheTableMissing() throws Exception {
        DuckDBDatabase database = database();
        IndexedCollection<Car> cars = cars(database, 20_000);
        DuckDBDatabase.ManagedMaterialization rollup = database.materialize("car_rollup")
                .as(rollupSql(database, cars)).build();

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch running = new CountDownLatch(4);
        ExecutorService readers = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            readers.submit(() -> {
                running.countDown();
                while (!stop.get()) {
                    try {
                        long total = database.query("SELECT sum(n) FROM car_rollup").scalar(Long.class);
                        if (total != 20_000) {
                            throw new IllegalStateException("half-built rollup: saw " + total);
                        }
                        reads.incrementAndGet();
                    }
                    catch (Exception e) {
                        failure.compareAndSet(null, e);
                        return;
                    }
                }
            });
        }
        assertTrue(running.await(30, TimeUnit.SECONDS));

        for (int i = 0; i < 15; i++) {
            rollup.refresh();
        }
        stop.set(true);
        readers.shutdown();
        assertTrue(readers.awaitTermination(60, TimeUnit.SECONDS));

        if (failure.get() != null) {
            throw new AssertionError("a reader saw the materialization missing or half-built",
                    failure.get());
        }
        assertTrue("readers should have got through plenty of reads", reads.get() > 20);
    }

    @Test
    public void aMaterializationSurvivesReopeningAFileDatabase() throws Exception {
        java.io.File file = temporaryFolder.newFile("materialized.duckdb");
        file.delete();
        long rows;
        try (DuckDBDatabase database = DuckDBDatabase.inFile(file)) {
            IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            cars.addAll(Cars.generate(300, 5));
            rows = database.materialize("car_rollup").as(rollupSql(database, cars)).build().rowCount();
        }
        try (DuckDBDatabase reopened = DuckDBDatabase.inFile(file)) {
            assertEquals("the table is real, so it is still there",
                    rows, (long) reopened.query("SELECT count(*) FROM car_rollup").scalar(Long.class));
        }
    }
}
