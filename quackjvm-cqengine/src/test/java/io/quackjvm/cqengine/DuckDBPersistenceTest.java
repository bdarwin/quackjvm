package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import io.quackjvm.cqengine.testutil.MutableCar;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.ascending;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static com.googlecode.cqengine.query.QueryFactory.has;
import static com.googlecode.cqengine.query.QueryFactory.in;
import static com.googlecode.cqengine.query.QueryFactory.lessThan;
import static com.googlecode.cqengine.query.QueryFactory.not;
import static com.googlecode.cqengine.query.QueryFactory.or;
import static com.googlecode.cqengine.query.QueryFactory.orderBy;
import static com.googlecode.cqengine.query.QueryFactory.queryOptions;
import static com.googlecode.cqengine.query.QueryFactory.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DuckDBPersistenceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final List<DuckDBPersistence<?, ?>> openPersistences = new ArrayList<>();

    @After
    public void closeAll() {
        for (DuckDBPersistence<?, ?> persistence : openPersistences) {
            persistence.close();
        }
    }

    private DuckDBPersistence<Car, Integer> inMemory() {
        return track(DuckDBPersistence.onPrimaryKeyInMemory(Car.CAR_ID));
    }

    private DuckDBPersistence<Car, Integer> columnarInMemory() {
        return track(DuckDBPersistence.builder(Car.CAR_ID)
                .inMemory()
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .build());
    }

    private <O, A extends Comparable<A>> DuckDBPersistence<O, A> track(DuckDBPersistence<O, A> persistence) {
        openPersistences.add(persistence);
        return persistence;
    }

    // ---------- Set semantics ----------

    @Test
    public void addAndRetrieveSingleObject() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        Car car = Cars.generate(1, 1).get(0);

        assertTrue(cars.add(car));
        assertEquals(1, cars.size());
        assertEquals(car, cars.retrieve(equal(Car.CAR_ID, car.carId())).uniqueResult());
        assertTrue(cars.contains(car));
    }

    @Test
    public void addingTheSameObjectTwiceIsANoOp() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        Car car = Cars.generate(1, 2).get(0);

        assertTrue(cars.add(car));
        assertFalse("adding an object already in the collection should report no modification", cars.add(car));

        assertEquals(1, cars.size());
        assertEquals(1, cars.retrieve(equal(Car.MANUFACTURER, car.manufacturer())).size());
    }

    @Test
    public void removeDeletesFromObjectStoreAndIndexes() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(20, 3);
        cars.addAll(generated);

        Car removed = generated.get(5);
        assertTrue(cars.remove(removed));
        assertFalse(cars.remove(removed));

        assertEquals(19, cars.size());
        assertFalse(cars.contains(removed));
        for (Car car : cars.retrieve(equal(Car.MANUFACTURER, removed.manufacturer()))) {
            assertFalse(car.equals(removed));
        }
    }

    @Test
    public void clearEmptiesCollectionAndIndexes() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addAll(Cars.generate(30, 4));

        cars.clear();

        assertEquals(0, cars.size());
        assertTrue(cars.isEmpty());
        assertEquals(0, cars.retrieve(equal(Car.MANUFACTURER, "Ford")).size());
    }

    @Test
    public void updateReplacesObjects() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(10, 5);
        cars.addAll(generated);

        Car old = generated.get(3);
        Car updated = new Car(old.carId(), "Porsche", old.model(), old.color(), old.doors(), old.price(),
                old.description(), old.registered());
        cars.update(List.of(old), List.of(updated));

        assertEquals(10, cars.size());
        assertEquals(updated, cars.retrieve(equal(Car.CAR_ID, old.carId())).uniqueResult());
        assertEquals(1, cars.retrieve(equal(Car.MANUFACTURER, "Porsche")).size());
        for (Car car : cars.retrieve(equal(Car.MANUFACTURER, old.manufacturer()))) {
            assertFalse(car.carId() == old.carId());
        }
    }

    // ---------- Query coverage, against an on-heap collection as the oracle ----------

    @Test
    public void blobStoreMatchesOnHeapCollection() {
        assertMatchesOnHeap(new ConcurrentIndexedCollection<>(inMemory()), true);
    }

    @Test
    public void columnarStoreMatchesOnHeapCollection() {
        assertMatchesOnHeap(new ConcurrentIndexedCollection<>(columnarInMemory()), true);
    }

    @Test
    public void unindexedQueriesMatchOnHeapCollection() {
        assertMatchesOnHeap(new ConcurrentIndexedCollection<>(inMemory()), false);
    }

    private void assertMatchesOnHeap(IndexedCollection<Car> duckDbCars, boolean withIndexes) {
        List<Car> generated = Cars.generate(500, 6);

        IndexedCollection<Car> onHeap = new ConcurrentIndexedCollection<>();
        if (withIndexes) {
            // Give the reference collection equivalent on-heap indexes, so that both collections
            // take the same code paths through CQEngine's query engine.
            onHeap.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
            onHeap.addIndex(NavigableIndex.onAttribute(Car.PRICE));
            onHeap.addIndex(HashIndex.onAttribute(Car.COLOR));
            onHeap.addIndex(HashIndex.onAttribute(Car.FEATURES));
            onHeap.addIndex(NavigableIndex.onAttribute(Car.REGISTERED));
            onHeap.addIndex(NavigableIndex.onAttribute(Car.MODEL));
        }
        onHeap.addAll(generated);

        if (withIndexes) {
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.COLOR));
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.FEATURES));
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.REGISTERED));
            duckDbCars.addIndex(DuckDBIndex.onAttribute(Car.MODEL));
        }
        duckDbCars.addAll(generated);

        List<Query<Car>> queries = List.of(
                equal(Car.MANUFACTURER, "Ford"),
                equal(Car.COLOR, Car.Color.BLUE),
                equal(Car.DOORS, 3),
                in(Car.MANUFACTURER, "Ford", "Tesla"),
                lessThan(Car.PRICE, 25000.0),
                greaterThan(Car.PRICE, 40000.0),
                between(Car.PRICE, 10000.0, 20000.0),
                between(Car.PRICE, 10000.0, false, 20000.0, false),
                startsWith(Car.MODEL, "model1"),
                has(Car.REGISTERED),
                not(has(Car.REGISTERED)),
                equal(Car.FEATURES, "hybrid"),
                greaterThan(Car.REGISTERED, Cars.toDate(LocalDate.of(2015, 1, 1))),
                and(equal(Car.MANUFACTURER, "BMW"), lessThan(Car.PRICE, 30000.0)),
                or(equal(Car.MANUFACTURER, "BMW"), equal(Car.COLOR, Car.Color.RED)),
                and(equal(Car.COLOR, Car.Color.RED), not(equal(Car.MANUFACTURER, "Ford"))));

        for (Query<Car> query : queries) {
            Set<Car> expected = new HashSet<>();
            int expectedSize;
            try (ResultSet<Car> onHeapResults = onHeap.retrieve(query)) {
                onHeapResults.forEach(expected::add);
                expectedSize = onHeapResults.size();
            }
            Set<Car> actual = new HashSet<>();
            try (ResultSet<Car> resultSet = duckDbCars.retrieve(query)) {
                resultSet.forEach(actual::add);
                assertEquals("results differ for query: " + query, expected, actual);
                assertEquals("size() differs for query: " + query, expectedSize, resultSet.size());
            }
        }
    }

    @Test
    public void orderedRetrievalIsSupported() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        List<Car> generated = Cars.generate(100, 7);
        cars.addAll(generated);

        List<Car> expected = new ArrayList<>(generated);
        expected.sort(Comparator.comparingDouble(Car::price).thenComparingInt(Car::carId));

        List<Car> actual = new ArrayList<>();
        cars.retrieve(has(Car.PRICE), queryOptions(orderBy(ascending(Car.PRICE), ascending(Car.CAR_ID))))
                .forEach(actual::add);

        assertEquals(expected, actual);
    }

    // ---------- Indexes ----------

    @Test
    public void indexAddedToAPopulatedCollectionIsBuiltFromExistingObjects() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        List<Car> generated = Cars.generate(2000, 8);
        cars.addAll(generated);

        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        long expected = generated.stream().filter(car -> car.manufacturer().equals("Honda")).count();
        ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Honda"));
        assertEquals(expected, results.size());
    }

    @Test
    public void multiValueAttributeIndexReturnsEachObjectOnce() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.FEATURES));
        Car car = new Car(1, "Ford", "focus", Car.Color.RED, 5, 100.0, "fast fast cheap", null);
        cars.add(car);

        ResultSet<Car> results = cars.retrieve(equal(Car.FEATURES, "fast"));
        assertEquals(1, results.size());
        assertEquals(car, results.uniqueResult());
    }

    @Test
    public void onHeapIndexesCanBeMixedWithDuckDbPersistence() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        List<Car> generated = Cars.generate(50, 9);
        cars.addAll(generated);

        long expected = generated.stream().filter(car -> car.price() < 20000.0).count();
        assertEquals(expected, cars.retrieve(lessThan(Car.PRICE, 20000.0)).size());
    }

    @Test
    public void defaultPersistenceIsInMemory() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        assertNull("onPrimaryKey should not create a file", persistence.getFile());

        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(100, 15);
        cars.addAll(generated);

        assertEquals(generated.size(), cars.size());
        long expected = generated.stream().filter(car -> car.manufacturer().equals("Ford")).count();
        assertEquals(expected, cars.retrieve(equal(Car.MANUFACTURER, "Ford")).size());
        // An in-memory database still reports what it is using.
        assertTrue(persistence.getBytesUsed() >= 0);
    }

    // ---------- Persistence to a file ----------

    @Test
    public void dataAndIndexesSurviveReopeningTheFile() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "cars.duckdb");
        List<Car> generated = Cars.generate(200, 10);

        DuckDBPersistence<Car, Integer> persistence = DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, file);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addAll(generated);
        long bytesUsed = persistence.getBytesUsed();
        persistence.close();

        assertTrue("the database file should have been written", bytesUsed > 0);
        assertTrue(file.exists());

        DuckDBPersistence<Car, Integer> reopened = track(DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, file));
        IndexedCollection<Car> reopenedCars = new ConcurrentIndexedCollection<>(reopened);
        reopenedCars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        assertEquals(generated.size(), reopenedCars.size());
        long expected = generated.stream().filter(car -> car.manufacturer().equals("Toyota")).count();
        assertEquals(expected, reopenedCars.retrieve(equal(Car.MANUFACTURER, "Toyota")).size());
    }

    @Test
    public void reopeningWithADifferentLayoutIsRejected() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "layout.duckdb");

        DuckDBPersistence<Car, Integer> blobPersistence = DuckDBPersistence.onPrimaryKeyInFile(Car.CAR_ID, file);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(blobPersistence);
        cars.addAll(Cars.generate(5, 14));
        blobPersistence.close();

        // The same file now holds BLOB-shaped objects; opening it with a columnar layout must fail
        // loudly rather than write rows the reader cannot make sense of.
        DuckDBPersistence<Car, Integer> columnarPersistence = track(DuckDBPersistence.builder(Car.CAR_ID)
                .file(file)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .build());
        try {
            new ConcurrentIndexedCollection<>(columnarPersistence);
            fail("expected the mismatched object layout to be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("different object layout"));
        }
    }

    @Test
    public void optimizePreservesEveryQueryResult() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        cars.addIndex(DuckDBIndex.onAttribute(Car.FEATURES));
        List<Car> generated = Cars.generate(2_000, 41);
        cars.addAll(generated);

        Set<Car> beforeFords = collect(cars, equal(Car.MANUFACTURER, "Ford"));
        Set<Car> beforeCheap = collect(cars, lessThan(Car.PRICE, 20_000.0));
        Set<Car> beforeFast = collect(cars, equal(Car.FEATURES, "fast"));

        persistence.optimize();

        assertEquals(generated.size(), cars.size());
        assertEquals(beforeFords, collect(cars, equal(Car.MANUFACTURER, "Ford")));
        assertEquals(beforeCheap, collect(cars, lessThan(Car.PRICE, 20_000.0)));
        assertEquals(beforeFast, collect(cars, equal(Car.FEATURES, "fast")));

        // The collection must still be writable after being reorganised.
        Car extra = new Car(999_999, "Ford", "gt", Car.Color.RED, 3, 1.0, "fast", null);
        assertTrue(cars.add(extra));
        assertEquals(beforeFords.size() + 1, collect(cars, equal(Car.MANUFACTURER, "Ford")).size());
        assertTrue(cars.remove(extra));
    }

    private static Set<Car> collect(IndexedCollection<Car> cars, Query<Car> query) {
        Set<Car> results = new HashSet<>();
        try (ResultSet<Car> resultSet = cars.retrieve(query)) {
            resultSet.forEach(results::add);
        }
        return results;
    }

    // ---------- Columnar layouts ----------

    @Test
    public void reflectiveLayoutRebuildsPlainObjects() {
        DuckDBPersistence<MutableCar, Long> persistence = track(DuckDBPersistence.builder(MutableCar.ID)
                .inMemory()
                .columnarLayout(ColumnarLayout.reflective(MutableCar.class))
                .build());
        IndexedCollection<MutableCar> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(MutableCar.NAME));

        MutableCar car = new MutableCar(7L, "Fiesta", 2019);
        cars.add(car);
        cars.add(new MutableCar(8L, "Focus", null));

        assertEquals(car, cars.retrieve(equal(MutableCar.NAME, "Fiesta")).uniqueResult());
        MutableCar withNullYear = cars.retrieve(equal(MutableCar.NAME, "Focus")).uniqueResult();
        assertNotNull(withNullYear);
        assertEquals(null, withNullYear.getYear());
    }

    @Test
    public void explicitLayoutIsUsedForColumns() {
        ColumnarLayout<MutableCar> layout = ColumnarLayout.builder(MutableCar.class)
                .column("id", Long.class, MutableCar::getId)
                .column("name", String.class, MutableCar::getName)
                .column("year", Integer.class, MutableCar::getYear)
                .rowFactory(values -> new MutableCar((Long) values[0], (String) values[1], (Integer) values[2]))
                .build();
        DuckDBPersistence<MutableCar, Long> persistence = track(DuckDBPersistence.builder(MutableCar.ID)
                .inMemory().columnarLayout(layout).build());
        IndexedCollection<MutableCar> cars = new ConcurrentIndexedCollection<>(persistence);

        MutableCar car = new MutableCar(1L, "Civic", 2020);
        cars.add(car);

        assertEquals(car, cars.retrieve(equal(MutableCar.ID, 1L)).uniqueResult());
    }

    // ---------- Bulk import ----------

    @Test
    public void bulkImportFlagLoadsObjectsAndIndexes() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(columnarInMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(5000, 11);

        QueryOptions options = new QueryOptions();
        FlagsEnabled.forQueryOptions(options).add(DuckDBFlags.BULK_IMPORT);
        cars.update(List.of(), generated, options);

        assertEquals(generated.size(), cars.size());
        long expected = generated.stream().filter(car -> car.manufacturer().equals("BMW")).count();
        assertEquals(expected, cars.retrieve(equal(Car.MANUFACTURER, "BMW")).size());
    }

    @Test
    public void largeBatchesAreLoadedThroughTheAppender() {
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(inMemory());
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(5000, 12);

        cars.addAll(generated);

        assertEquals(5000, cars.size());
        Set<Car> retrieved = new HashSet<>();
        cars.retrieve(has(Car.CAR_ID)).forEach(retrieved::add);
        assertEquals(new HashSet<>(generated), retrieved);
    }

    // ---------- Object cache ----------

    @Test
    public void objectCacheReturnsConsistentResults() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.builder(Car.CAR_ID)
                .inMemory().objectCacheSize(100).build());
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        List<Car> generated = Cars.generate(50, 13);
        cars.addAll(generated);

        for (Car car : generated) {
            assertEquals(car, cars.retrieve(equal(Car.CAR_ID, car.carId())).uniqueResult());
            assertEquals(car, cars.retrieve(equal(Car.CAR_ID, car.carId())).uniqueResult());
        }

        Car replaced = new Car(generated.get(0).carId(), "Updated", "m", Car.Color.RED, 3, 1.0, "", null);
        cars.update(List.of(generated.get(0)), List.of(replaced));
        assertEquals(replaced, cars.retrieve(equal(Car.CAR_ID, replaced.carId())).uniqueResult());
    }
}
