package io.quackjvm.cqengine;

import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.IndexedCollection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Reading answers out of a collection without rebuilding any objects. Every expectation is computed
 * independently in plain Java from the same data.
 */
public class RowsTest {

    /** Components line up with the columns the aggregate query selects. */
    public record MakeStats(String manufacturer, long cars, double averagePrice) {
    }

    private DuckDBDatabase database;
    private IndexedCollection<Car> cars;
    private List<Car> generated;

    @Before
    public void setUp() {
        database = DuckDBDatabase.inMemory();
        cars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        generated = Cars.generate(2_000, 71);
        cars.addAll(generated);
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void scalarAggregateMatchesTheSameSumInJava() {
        double expected = generated.stream()
                .filter(car -> car.manufacturer().equals("Ford"))
                .mapToDouble(Car::price).sum();
        double actual = database.query("SELECT sum(price) FROM cq_car WHERE manufacturer = ?", "Ford")
                .scalar(Double.class);
        assertEquals(expected, actual, 0.001);

        long expectedCount = generated.stream().filter(car -> car.doors() == 3).count();
        assertEquals(expectedCount,
                (long) database.query("SELECT count(*) FROM cq_car WHERE doors = ?", 3).scalar(Long.class));
    }

    @Test
    public void scalarOfAnEmptyResultIsEmptyRatherThanAnError() {
        Optional<Double> none = database.query(
                "SELECT sum(price) FROM cq_car WHERE manufacturer = ?", "Nonexistent")
                .scalarOptional(Double.class);
        // sum() of no rows is SQL NULL, which arrives as an absent value rather than zero.
        assertTrue(none.isEmpty());

        Optional<Long> noRows = database.query(
                "SELECT manufacturer FROM cq_car WHERE 1 = 0").scalarOptional(Long.class);
        assertTrue(noRows.isEmpty());
    }

    @Test
    public void scalarOnAQueryWithNoRowsIsRejectedClearly() {
        try {
            database.query("SELECT manufacturer FROM cq_car WHERE 1 = 0").scalar(String.class);
            fail("expected scalar() on an empty result to be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no rows"));
        }
    }

    @Test
    public void listReadsASingleColumn() {
        List<String> expected = generated.stream().map(Car::manufacturer).distinct().sorted().toList();
        List<String> actual = database.query(
                "SELECT DISTINCT manufacturer FROM cq_car ORDER BY 1").list(String.class);
        assertEquals(expected, actual);

        List<Double> prices = database.query(
                "SELECT price FROM cq_car WHERE manufacturer = ? ORDER BY price", "Ford").list(Double.class);
        List<Double> expectedPrices = generated.stream()
                .filter(car -> car.manufacturer().equals("Ford"))
                .map(Car::price).sorted().toList();
        assertEquals(expectedPrices, prices);
    }

    @Test
    public void recordsMapColumnsByPosition() {
        Map<String, List<Car>> byMake = new TreeMap<>();
        generated.forEach(car -> byMake.computeIfAbsent(car.manufacturer(), k -> new ArrayList<>()).add(car));

        List<MakeStats> stats = database.query(
                "SELECT manufacturer, count(*), avg(price) FROM cq_car GROUP BY 1 ORDER BY 1")
                .records(MakeStats.class);

        assertEquals(byMake.size(), stats.size());
        stats.sort(Comparator.comparing(MakeStats::manufacturer));
        int i = 0;
        for (Map.Entry<String, List<Car>> entry : byMake.entrySet()) {
            MakeStats actual = stats.get(i++);
            assertEquals(entry.getKey(), actual.manufacturer());
            assertEquals(entry.getValue().size(), actual.cars());
            assertEquals(entry.getValue().stream().mapToDouble(Car::price).average().orElseThrow(),
                    actual.averagePrice(), 0.001);
        }
    }

    @Test
    public void countReportsRowsWithoutReadingThem() {
        long expected = generated.stream().filter(car -> car.manufacturer().equals("Honda")).count();
        assertEquals(expected, database.query(
                "SELECT * FROM cq_car WHERE manufacturer = ?", "Honda").count());
    }

    @Test
    public void nullsInAColumnComeBackAsNulls() {
        // Every seventh generated car has no registration date.
        long expectedNulls = generated.stream().filter(car -> car.registered() == null).count();
        assertTrue(expectedNulls > 0);
        long actualNulls = database.query("SELECT count(*) FROM cq_car WHERE registered IS NULL")
                .scalar(Long.class);
        assertEquals(expectedNulls, actualNulls);

        List<java.util.Date> dates = database.query(
                "SELECT registered FROM cq_car ORDER BY carId").list(java.util.Date.class);
        assertEquals(generated.size(), dates.size());
        assertEquals(expectedNulls, dates.stream().filter(java.util.Objects::isNull).count());
    }

    @Test
    public void duckDbPivotWorks() {
        // A pivot has no equivalent in an object query engine; this is the point of raw SQL access.
        List<io.quackjvm.core.sql.SqlRow> pivoted = new ArrayList<>();
        database.query("PIVOT cq_car ON color USING count(*) GROUP BY manufacturer")
                .forEachRow(row -> pivoted.add(row));
        long distinctMakes = generated.stream().map(Car::manufacturer).distinct().count();
        assertEquals(distinctMakes, pivoted.size());
    }

    @Test
    public void aWrongColumnNameIsReportedWithTheSchema() {
        try {
            database.query("SELECT nosuchcolumn FROM cq_car").list(String.class);
            fail("expected an unknown column to be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("nosuchcolumn"));
        }
    }

    @Test
    public void theCollectionIsUnaffectedByProjections() {
        database.query("SELECT sum(price) FROM cq_car").scalar(Double.class);
        database.query("SELECT manufacturer FROM cq_car").list(String.class);
        assertEquals(generated.size(), cars.size());
        assertFalse(cars.isEmpty());
    }
}
