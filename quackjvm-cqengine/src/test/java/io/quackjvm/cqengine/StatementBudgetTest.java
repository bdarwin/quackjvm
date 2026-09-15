package io.quackjvm.cqengine;

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.duckdb.SqlTrace;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.existsIn;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Performance as a budget on statements rather than a threshold on time.
 *
 * <p>Every expensive mistake found in this codebase was structural - an object written three times
 * instead of once, a statement prepared per call instead of once, a compound query evaluated branch
 * by branch, an {@code existsIn} asking the foreign collection about one object at a time. None of
 * them were subtle arithmetic; all of them showed up as extra statements.</p>
 *
 * <p>So these assert counts, which are exact and identical on every machine, rather than
 * milliseconds, which are neither. A timing threshold generous enough not to fail on a loaded CI
 * box is too generous to catch a 3x regression; a statement budget catches it exactly.</p>
 */
public class StatementBudgetTest {

    private DuckDBDatabase database;

    @Before
    public void enableTracing() {
        SqlTrace.setEnabled(true);
    }

    @After
    public void tearDown() {
        SqlTrace.setEnabled(false);
        SqlTrace.reset();
        if (database != null) {
            database.close();
        }
    }

    private IndexedCollection<Car> collection(String name, int indexes, int objects) {
        if (database == null) {
            database = DuckDBDatabase.builder().memoryLimit("256MB").build();
        }
        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .name(name).columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        if (indexes > 0) {
            cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        }
        if (indexes > 1) {
            cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        }
        if (objects > 0) {
            cars.addAll(Cars.generate(objects, 11));
        }
        return cars;
    }

    /** Runs the block with a clean trace and returns how many statements it executed. */
    private long statementsFor(Runnable block) {
        SqlTrace.reset();
        block.run();
        return SqlTrace.executionCount(null);
    }

    private String traceDump() {
        StringBuilder sb = new StringBuilder("\n");
        for (Map.Entry<String, Long> entry : SqlTrace.countsByStatement().entrySet()) {
            sb.append("    ").append(entry.getValue()).append(" x ").append(entry.getKey()).append('\n');
        }
        return sb.toString();
    }

    // ---------- Writes ----------

    /**
     * A single add is a delete of the key and an insert, per table. CQEngine hands the objects to
     * the identity index twice - once through the object store and again through the index engine -
     * and this catches the second write coming back.
     */
    @Test
    public void oneAddWritesTheObjectTableOnce() {
        IndexedCollection<Car> cars = collection("car", 0, 50);
        Car car = new Car(900_001, "Ford", "Focus", Car.Color.BLUE, 5, 15_000.0, "d", null);

        long statements = statementsFor(() -> cars.add(car));

        assertEquals("a one-object add should write the object table exactly once" + traceDump(),
                1, SqlTrace.executionCount("INSERT INTO \"cq_car\""));
        assertTrue("a one-object add should not need more than a delete and an insert" + traceDump(),
                statements <= 2);
    }

    /** Each index costs one more delete-and-insert pair, and nothing else. */
    @Test
    public void eachIndexAddsOneWritePerAdd() {
        IndexedCollection<Car> unindexed = collection("plain", 0, 20);
        IndexedCollection<Car> indexed = collection("indexed", 2, 20);

        long withoutIndexes = statementsFor(() ->
                unindexed.add(new Car(900_002, "Ford", "Ka", Car.Color.RED, 3, 9_000.0, "d", null)));
        long withTwoIndexes = statementsFor(() ->
                indexed.add(new Car(900_002, "Ford", "Ka", Car.Color.RED, 3, 9_000.0, "d", null)));

        assertTrue("two indexes should cost at most two more statements, not a multiple"
                        + traceDump(),
                withTwoIndexes <= withoutIndexes + 2);
    }

    /** A batch is one write per table, not one per object - the Appender path. */
    @Test
    public void aBatchIsWrittenInAFixedNumberOfStatements() {
        IndexedCollection<Car> cars = collection("car", 1, 0);
        List<Car> batch = new ArrayList<>(Cars.generate(500, 21));

        long statements = statementsFor(() -> cars.addAll(batch));

        assertTrue("500 objects must not cost statements proportional to 500, was " + statements
                        + traceDump(),
                statements < 40);
    }

    // ---------- Reads ----------

    @Test
    public void aPointLookupIsOneStatement() {
        IndexedCollection<Car> cars = collection("car", 1, 300);
        long statements = statementsFor(() -> {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.CAR_ID, 7))) {
                results.uniqueResult();
            }
        });
        assertEquals("a lookup by primary key should be a single statement" + traceDump(),
                1, statements);
    }

    /**
     * The retrieval that made this plugin usable: the matching keys are pushed into the object
     * lookup as a semi-join, rather than fetching objects one key at a time.
     */
    @Test
    public void anIndexedRetrievalDoesNotFetchObjectsOneAtATime() {
        IndexedCollection<Car> cars = collection("car", 1, 2_000);
        long matched;
        SqlTrace.reset();
        try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            matched = results.stream().count();
        }
        long statements = SqlTrace.executionCount(null);

        assertTrue("the fixture should match a decent number of objects", matched > 20);
        assertTrue("retrieving " + matched + " objects took " + statements
                        + " statements; it must not scale with the number matched" + traceDump(),
                statements <= 3);
    }

    /** A compound query goes to DuckDB whole, rather than a statement per branch. */
    @Test
    public void aCompoundQueryIsOneStatement() {
        IndexedCollection<Car> cars = collection("car", 2, 2_000);
        long statements = statementsFor(() -> {
            try (ResultSet<Car> results = cars.retrieve(and(
                    equal(Car.MANUFACTURER, "Ford"),
                    between(Car.PRICE, 0.0, 30_000.0)))) {
                results.stream().count();
            }
        });
        assertTrue("an and() of two indexed attributes should be one statement, was " + statements
                        + traceDump(),
                statements <= 2);
    }

    /** existsIn becomes a semi-join, not a probe of the foreign collection per object. */
    /**
     * The invariant is not a particular number of statements, it is that the number does not depend
     * on how many objects there are. So this measures it at two sizes rather than picking a
     * threshold: CQEngine's own evaluation of {@code existsIn} asks the foreign collection about
     * one object at a time, which at 1,000 objects was 2,003 statements against 4.
     */
    @Test
    public void existsInDoesNotScaleWithTheCollection() {
        long small = existsInStatements(250);
        long large = existsInStatements(1_000);

        assertEquals("existsIn took " + small + " statements over 250 objects and " + large
                        + " over 1,000; it must not scale with the collection",
                small, large);
        assertTrue("and it should be a small constant, was " + large, large <= 8);
    }

    private long existsInStatements(int objects) {
        database = DuckDBDatabase.builder().memoryLimit("256MB").build();
        try {
            IndexedCollection<Car> cars = collection("car", 1, objects);
            // The foreign query filters on PRICE, so PRICE must be indexed there to be
            // translatable - that is what the second index is for.
            IndexedCollection<Car> approved = collection("approved", 2, objects);
            return statementsFor(() -> {
                try (ResultSet<Car> results = cars.retrieve(
                        existsIn(approved, Car.CAR_ID, Car.CAR_ID, greaterThan(Car.PRICE, 0.0)))) {
                    results.stream().count();
                }
            });
        }
        finally {
            database.close();
            database = null;
        }
    }

    /**
     * And the other half of that: when the foreign query cannot be translated - here because the
     * attribute it filters on is not indexed in the foreign collection - CQEngine evaluates it one
     * object at a time. That is slow and it is also **correct**, which is the property worth
     * pinning down: the push-down is an optimisation, never a change in results.
     */
    @Test
    public void existsInOnAnUnindexedForeignAttributeStillGivesTheRightAnswer() {
        IndexedCollection<Car> cars = collection("car", 1, 300);
        IndexedCollection<Car> approved = collection("approved", 1, 300);   // PRICE not indexed

        List<Integer> viaExistsIn = new ArrayList<>();
        try (ResultSet<Car> results = cars.retrieve(
                existsIn(approved, Car.CAR_ID, Car.CAR_ID, greaterThan(Car.PRICE, 20_000.0)))) {
            results.forEach(car -> viaExistsIn.add(car.carId()));
        }
        List<Integer> expected = new ArrayList<>();
        try (ResultSet<Car> results = approved.retrieve(greaterThan(Car.PRICE, 20_000.0))) {
            results.forEach(car -> expected.add(car.carId()));
        }
        java.util.Collections.sort(viaExistsIn);
        java.util.Collections.sort(expected);
        assertEquals("falling back to per-object evaluation must not change the answer",
                expected, viaExistsIn);
    }

    /** Counting must not materialise the objects it is counting. */
    @Test
    public void countingIsOneStatementAndFetchesNoObjects() {
        IndexedCollection<Car> cars = collection("car", 1, 2_000);
        long statements = statementsFor(() -> {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                results.size();
            }
        });
        assertEquals("size() should be a single count statement" + traceDump(), 1, statements);
    }

    /**
     * Statements are prepared once per connection and reused.
     *
     * <p>Counting {@code prepareStatement} calls cannot show this - the call happens either way and
     * the cache answers it - so this asserts on the cache's own hit and miss counters. A miss costs
     * DuckDB about 200 microseconds, which on a single-object write was a fifth of the latency.</p>
     */
    @Test
    public void repeatingAQueryDoesNotRePrepareIt() {
        IndexedCollection<Car> cars = collection("car", 1, 500);
        for (int i = 0; i < 5; i++) {                 // warm: first use of each statement misses
            try (ResultSet<Car> results = cars.retrieve(equal(Car.CAR_ID, i))) {
                results.uniqueResult();
            }
        }
        long[] before = database.getStatementCacheStats();
        for (int i = 0; i < 20; i++) {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.CAR_ID, i))) {
                results.uniqueResult();
            }
        }
        long[] after = database.getStatementCacheStats();
        long hits = after[0] - before[0];
        long misses = after[1] - before[1];

        assertTrue("20 identical lookups should be served from cache, got " + hits
                + " hits and " + misses + " misses", hits >= 20);
        assertEquals("a warmed-up repeated query should not reach DuckDB's parser at all",
                0, misses);
    }
}
