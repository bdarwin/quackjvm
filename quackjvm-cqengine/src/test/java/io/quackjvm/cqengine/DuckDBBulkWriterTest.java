package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBBulkWriter;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static com.googlecode.cqengine.query.QueryFactory.has;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DuckDBBulkWriterTest {

    private final List<DuckDBPersistence<?, ?>> openPersistences = new ArrayList<>();

    @After
    public void closeAll() {
        for (DuckDBPersistence<?, ?> persistence : openPersistences) {
            persistence.close();
        }
    }

    private <O, A extends Comparable<A>> DuckDBPersistence<O, A> track(DuckDBPersistence<O, A> persistence) {
        openPersistences.add(persistence);
        return persistence;
    }

    @Test
    public void writtenObjectsAreRetrievableAndIndexed() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        List<Car> generated = Cars.generate(5_000, 31);

        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            for (Car car : generated) {
                writer.add(car);
            }
            assertEquals(generated.size(), writer.getObjectsWritten());
        }

        assertEquals(generated.size(), cars.size());

        Set<Car> retrieved = new HashSet<>();
        try (ResultSet<Car> results = cars.retrieve(has(Car.CAR_ID))) {
            results.forEach(retrieved::add);
        }
        assertEquals(new HashSet<>(generated), retrieved);

        long expectedFords = generated.stream().filter(car -> car.manufacturer().equals("Ford")).count();
        try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            assertEquals(expectedFords, results.size());
        }
        long expensive = generated.stream().filter(car -> car.price() > 40_000).count();
        try (ResultSet<Car> results = cars.retrieve(greaterThan(Car.PRICE, 40_000.0))) {
            assertEquals(expensive, results.size());
        }
    }

    @Test
    public void columnarLayoutIsSupported() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.builder(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .build());
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        List<Car> generated = Cars.generate(2_000, 32);

        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            writer.addAll(generated);
        }

        assertEquals(generated.size(), cars.size());
        Car first = generated.get(0);
        try (ResultSet<Car> results = cars.retrieve(equal(Car.CAR_ID, first.carId()))) {
            assertEquals(first, results.uniqueResult());
        }
    }

    @Test
    public void multiValuedAttributesGetOneRowPerValue() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.FEATURES));

        Car car = new Car(1, "Ford", "focus", Car.Color.RED, 5, 100.0, "fast cheap hybrid", null);
        Car other = new Car(2, "Honda", "civic", Car.Color.BLUE, 3, 200.0, "cheap", null);
        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            writer.add(car);
            writer.add(other);
        }

        try (ResultSet<Car> results = cars.retrieve(equal(Car.FEATURES, "cheap"))) {
            assertEquals(2, results.size());
        }
        try (ResultSet<Car> results = cars.retrieve(equal(Car.FEATURES, "hybrid"))) {
            assertEquals(car, results.uniqueResult());
        }
    }

    @Test
    public void flushMakesDataVisibleWithoutClosingTheWriter() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            writer.addAll(Cars.generate(100, 33));
            writer.flush();
            assertEquals(100, cars.size());

            writer.addAll(Cars.generate(50, 34).stream()
                    .map(car -> new Car(car.carId() + 1000, car.manufacturer(), car.model(), car.color(),
                            car.doors(), car.price(), car.description(), car.registered()))
                    .toList());
            writer.flush();
            assertEquals(150, cars.size());
        }
    }

    @Test
    public void writingAnObjectWhichAlreadyExistsIsReported() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        List<Car> generated = Cars.generate(10, 35);
        cars.addAll(generated);

        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            writer.add(generated.get(0));
            writer.flush();
            fail("expected a primary key violation for an object already in the collection");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage() + " / " + expected.getCause(),
                    (expected.getMessage() + expected.getCause()).toLowerCase().contains("key"));
        }
    }

    @Test
    public void theCollectionStillWorksNormallyAfterAWriterIsClosed() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        List<Car> generated = Cars.generate(200, 36);
        try (DuckDBBulkWriter<Car> writer = persistence.bulkWriter()) {
            writer.addAll(generated);
        }

        Car extra = new Car(99_999, "Porsche", "911", Car.Color.RED, 3, 90_000.0, "fast", null);
        assertTrue(cars.add(extra));
        assertEquals(201, cars.size());
        try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Porsche"))) {
            assertEquals(extra, results.uniqueResult());
        }
        assertTrue(cars.remove(extra));
        assertEquals(200, cars.size());
        try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Porsche"))) {
            assertEquals(0, results.size());
        }
    }

    @Test
    public void usingAClosedWriterIsRejected() {
        DuckDBPersistence<Car, Integer> persistence = track(DuckDBPersistence.onPrimaryKey(Car.CAR_ID));
        new ConcurrentIndexedCollection<>(persistence);

        DuckDBBulkWriter<Car> writer = persistence.bulkWriter();
        writer.add(Cars.generate(1, 37).get(0));
        writer.close();
        try {
            writer.add(Cars.generate(1, 38).get(0));
            fail("expected the closed writer to be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }
}
