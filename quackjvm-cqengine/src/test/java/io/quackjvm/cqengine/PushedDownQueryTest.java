package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.DeduplicationStrategy;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.ascending;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.deduplicate;
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
import static org.junit.Assert.assertTrue;

/**
 * Compound queries answered by one SQL statement, checked against an on-heap collection with
 * equivalent indexes. Anything the translator declines must still return the right answer through
 * CQEngine's own evaluation.
 */
public class PushedDownQueryTest {

    private DuckDBDatabase database;
    private IndexedCollection<Car> cars;
    private IndexedCollection<Car> heapCars;
    private List<Car> generated;

    @Before
    public void setUp() {
        database = DuckDBDatabase.inMemory();
        cars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        cars.addIndex(DuckDBIndex.onAttribute(Car.COLOR));
        cars.addIndex(DuckDBIndex.onAttribute(Car.MODEL));
        cars.addIndex(DuckDBIndex.onAttribute(Car.DOORS));
        cars.addIndex(DuckDBIndex.onAttribute(Car.FEATURES));
        cars.addIndex(DuckDBIndex.onAttribute(Car.REGISTERED));

        heapCars = new ConcurrentIndexedCollection<>();
        heapCars.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
        heapCars.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        heapCars.addIndex(HashIndex.onAttribute(Car.COLOR));
        heapCars.addIndex(HashIndex.onAttribute(Car.MODEL));
        heapCars.addIndex(HashIndex.onAttribute(Car.DOORS));
        heapCars.addIndex(HashIndex.onAttribute(Car.FEATURES));
        heapCars.addIndex(NavigableIndex.onAttribute(Car.REGISTERED));

        generated = Cars.generate(1_000, 61);
        cars.addAll(generated);
        heapCars.addAll(generated);
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private void assertMatchesHeap(Query<Car> query) {
        QueryOptions options = queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION));
        Set<Car> expected = new HashSet<>();
        int expectedSize;
        try (ResultSet<Car> results = heapCars.retrieve(query,
                queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION)))) {
            results.forEach(expected::add);
            expectedSize = results.size();
        }
        Set<Car> actual = new HashSet<>();
        try (ResultSet<Car> results = cars.retrieve(query, options)) {
            results.forEach(actual::add);
            assertEquals("results differ for: " + query, expected, actual);
            assertEquals("size() differs for: " + query, expectedSize, results.size());
            for (Car car : expected) {
                assertTrue("contains() should be true for a matching object", results.contains(car));
            }
        }
    }

    @Test
    public void andOfTwoIndexedAttributes() {
        assertMatchesHeap(and(equal(Car.COLOR, Car.Color.BLUE), lessThan(Car.PRICE, 25_000.0)));
    }

    @Test
    public void andOfThreeIndexedAttributes() {
        assertMatchesHeap(and(equal(Car.COLOR, Car.Color.BLUE), lessThan(Car.PRICE, 40_000.0),
                equal(Car.DOORS, 3)));
    }

    @Test
    public void orOfIndexedAttributes() {
        assertMatchesHeap(or(equal(Car.MANUFACTURER, "BMW"), equal(Car.COLOR, Car.Color.RED)));
    }

    @Test
    public void notOfAnIndexedAttribute() {
        assertMatchesHeap(not(equal(Car.MANUFACTURER, "Ford")));
    }

    @Test
    public void nestedAndOrNot() {
        assertMatchesHeap(and(
                or(equal(Car.MANUFACTURER, "Ford"), equal(Car.MANUFACTURER, "Tesla")),
                not(equal(Car.COLOR, Car.Color.BLACK)),
                between(Car.PRICE, 10_000.0, 45_000.0)));
    }

    @Test
    public void andWithAMultiValuedAttribute() {
        assertMatchesHeap(and(equal(Car.FEATURES, "hybrid"), lessThan(Car.PRICE, 30_000.0)));
    }

    @Test
    public void andWithStartsWithAndIn() {
        assertMatchesHeap(and(startsWith(Car.MODEL, "model1"),
                in(Car.MANUFACTURER, "Ford", "Honda", "BMW")));
    }

    @Test
    public void andWithHasAndNotHas() {
        assertMatchesHeap(and(has(Car.REGISTERED), greaterThan(Car.PRICE, 20_000.0)));
        assertMatchesHeap(and(not(has(Car.REGISTERED)), lessThan(Car.PRICE, 30_000.0)));
    }

    @Test
    public void andOnThePrimaryKey() {
        assertMatchesHeap(and(between(Car.CAR_ID, 100, 500), equal(Car.COLOR, Car.Color.GREEN)));
    }

    @Test
    public void queryOnAnUnindexedAttributeFallsBackAndIsStillCorrect() {
        // Car.DESCRIPTION has no index, so this cannot be translated.
        assertMatchesHeap(and(equal(Car.MANUFACTURER, "Ford"), equal(Car.DESCRIPTION, "fast")));
    }

    @Test
    public void orderingIsStillApplied() {
        Query<Car> query = and(equal(Car.COLOR, Car.Color.BLUE), lessThan(Car.PRICE, 40_000.0));
        List<Car> expected = new ArrayList<>();
        try (ResultSet<Car> results = heapCars.retrieve(query,
                queryOptions(orderBy(ascending(Car.PRICE), ascending(Car.CAR_ID))))) {
            results.forEach(expected::add);
        }
        expected.sort(Comparator.comparingDouble(Car::price).thenComparingInt(Car::carId));

        List<Car> actual = new ArrayList<>();
        try (ResultSet<Car> results = cars.retrieve(query,
                queryOptions(orderBy(ascending(Car.PRICE), ascending(Car.CAR_ID))))) {
            results.forEach(actual::add);
        }
        assertEquals(expected, actual);
        assertTrue(expected.size() > 1);
    }

    @Test
    public void simpleQueriesAreUnaffected() {
        assertMatchesHeap(equal(Car.MANUFACTURER, "Ford"));
        assertMatchesHeap(between(Car.PRICE, 10_000.0, 20_000.0));
        assertMatchesHeap(has(Car.REGISTERED));
    }

    @Test
    public void mutationsAreSeenByPushedDownQueries() {
        Query<Car> query = and(equal(Car.MANUFACTURER, "Porsche"), lessThan(Car.PRICE, 100_000.0));
        try (ResultSet<Car> results = cars.retrieve(query)) {
            assertEquals(0, results.size());
        }
        Car added = new Car(999_999, "Porsche", "911", Car.Color.RED, 3, 90_000.0, "fast", null);
        cars.add(added);
        try (ResultSet<Car> results = cars.retrieve(query)) {
            assertEquals(added, results.uniqueResult());
        }
        cars.remove(added);
        try (ResultSet<Car> results = cars.retrieve(query)) {
            assertEquals(0, results.size());
        }
    }
}
