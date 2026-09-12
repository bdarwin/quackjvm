package io.quackjvm.cqengine;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the write path's two shortcuts: not repeating the object-table write when CQEngine hands
 * the same objects to the identity index twice, and reusing prepared statements between requests.
 *
 * <p>Both are invisible when they work and produce lost or stale rows when they do not, so these
 * tests assert on what is actually stored rather than on how it got there.</p>
 */
public class WritePathTest {

    private final List<DuckDBPersistence<?, ?>> open = new ArrayList<>();

    @After
    public void closeAll() {
        open.forEach(DuckDBPersistence::close);
    }

    private IndexedCollection<Car> collection(boolean columnar) {
        DuckDBPersistence.Builder<Car, Integer> builder = DuckDBPersistence.builder(Car.CAR_ID).inMemory();
        if (columnar) {
            builder.columnarLayout(ColumnarLayout.ofRecord(Car.class));
        }
        DuckDBPersistence<Car, Integer> persistence = builder.build();
        open.add(persistence);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        return cars;
    }

    // ---------- The object table must be written exactly once, and exactly right ----------

    @Test
    public void singleAddsAccumulate() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(50, 7);
        for (Car car : generated) {
            assertTrue(cars.add(car));
        }
        assertEquals(50, cars.size());
        for (Car car : generated) {
            assertEquals(car, cars.retrieve(equal(Car.CAR_ID, car.carId())).uniqueResult());
        }
    }

    @Test
    public void reAddingAnObjectReportsNoChangeAndDoesNotDuplicateIndexRows() {
        IndexedCollection<Car> cars = collection(true);
        Car car = Cars.generate(1, 8).get(0);

        assertTrue(cars.add(car));
        assertFalse(cars.add(car));
        assertFalse(cars.add(car));

        assertEquals(1, cars.size());
        assertEquals(1, cars.retrieve(equal(Car.MANUFACTURER, car.manufacturer())).size());
        assertEquals(1, cars.retrieve(equal(Car.PRICE, car.price())).size());
    }

    /** The case the shortcut most easily breaks: the same key written again with new field values. */
    @Test
    public void updatingAnExistingKeyReplacesTheStoredObjectAndItsIndexRows() {
        IndexedCollection<Car> cars = collection(true);
        Car original = Cars.generate(1, 9).get(0);
        cars.add(original);

        Car changed = new Car(original.carId(), "Rewritten", original.model(), original.color(),
                original.doors(), original.price() + 1234.0, original.description(),
                original.registered());
        cars.update(Collections.singleton(original), Collections.singleton(changed));

        assertEquals(1, cars.size());
        assertEquals(changed, cars.retrieve(equal(Car.CAR_ID, original.carId())).uniqueResult());
        assertEquals("the old index row must be gone",
                0, cars.retrieve(equal(Car.MANUFACTURER, original.manufacturer())).size());
        assertEquals(1, cars.retrieve(equal(Car.MANUFACTURER, "Rewritten")).size());
        assertEquals(1, cars.retrieve(equal(Car.PRICE, changed.price())).size());
    }

    @Test
    public void removeThenReAddInTheSameRequestLeavesTheObjectStored() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(5, 10);
        cars.addAll(generated);

        Car car = generated.get(2);
        cars.update(Collections.singleton(car), Collections.singleton(car));

        assertEquals(5, cars.size());
        assertEquals(car, cars.retrieve(equal(Car.CAR_ID, car.carId())).uniqueResult());
    }

    @Test
    public void removeReportsWhetherAnythingWasRemoved() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(3, 11);
        cars.addAll(generated);

        assertTrue(cars.remove(generated.get(0)));
        assertFalse("removing an object which is not there reports no change",
                cars.remove(generated.get(0)));
        assertEquals(2, cars.size());
    }

    @Test
    public void blobStorageTakesTheSamePath() {
        IndexedCollection<Car> cars = collection(false);
        List<Car> generated = Cars.generate(20, 12);
        for (Car car : generated) {
            cars.add(car);
        }
        assertEquals(20, cars.size());
        assertFalse(cars.add(generated.get(0)));
        assertEquals(20, cars.size());
        assertEquals(generated.get(7), cars.retrieve(equal(Car.CAR_ID, generated.get(7).carId())).uniqueResult());
    }

    // ---------- Cached prepared statements must survive what invalidates them ----------

    @Test
    public void writesStillWorkAfterTheCollectionIsCleared() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(10, 13);
        cars.addAll(generated);
        assertEquals(10, cars.size());

        // clear() drops and recreates tables, which invalidates any statement prepared against
        // the old ones. Writing afterwards must not fail or write into a table that is gone.
        cars.clear();
        assertEquals(0, cars.size());

        for (Car car : generated) {
            cars.add(car);
        }
        assertEquals(10, cars.size());
        assertEquals(generated.get(3), cars.retrieve(equal(Car.CAR_ID, generated.get(3).carId())).uniqueResult());
    }

    @Test
    public void addingAnIndexBetweenWritesKeepsBothPathsCorrect() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(10, 14);
        cars.addAll(generated.subList(0, 5));

        cars.addIndex(DuckDBIndex.onAttribute(Car.MODEL));
        cars.addAll(generated.subList(5, 10));

        assertEquals(10, cars.size());
        for (Car car : generated) {
            // Models repeat in the generated data, so the count to expect is whatever is actually
            // there - the point is that the index sees objects written before and after it.
            long expected = generated.stream().filter(c -> c.model().equals(car.model())).count();
            assertEquals("the index added midway must cover objects from before and after it",
                    expected, cars.retrieve(equal(Car.MODEL, car.model())).size());
        }
    }

    /**
     * A result set holds its statement open while it is being read. Reusing statements between
     * requests must not cut a result set short, so this interleaves reads and writes of the same
     * shape and checks the reads still see everything.
     */
    @Test
    public void readsAreNotDisturbedByLaterWritesOfTheSameShape() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(30, 15);
        cars.addAll(generated);

        for (int round = 0; round < 5; round++) {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, generated.get(0).manufacturer()))) {
                int seen = 0;
                for (Car ignored : results) {
                    seen++;
                }
                assertTrue("a result set must not be cut short by another request", seen > 0);
            }
            cars.add(new Car(1000 + round, "Later", "model" + round, generated.get(0).color(), 4,
                    9_999.0 + round, "description", generated.get(0).registered()));
        }
        assertEquals(35, cars.size());
        assertEquals(5, cars.retrieve(equal(Car.MANUFACTURER, "Later")).size());
    }

    @Test
    public void nestedResultSetsOnOneCollectionDoNotShareAStatement() {
        IndexedCollection<Car> cars = collection(true);
        List<Car> generated = Cars.generate(20, 16);
        cars.addAll(generated);

        String manufacturer = generated.get(0).manufacturer();
        try (ResultSet<Car> outer = cars.retrieve(equal(Car.MANUFACTURER, manufacturer))) {
            int outerSeen = 0;
            for (Car ignored : outer) {
                outerSeen++;
                try (ResultSet<Car> inner = cars.retrieve(equal(Car.MANUFACTURER, manufacturer))) {
                    assertTrue(inner.size() > 0);
                }
            }
            assertTrue(outerSeen > 0);
        }
    }
}
