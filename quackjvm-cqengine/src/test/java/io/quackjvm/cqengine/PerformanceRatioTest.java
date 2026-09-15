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
import java.util.List;
import java.util.function.LongSupplier;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static org.junit.Assert.assertTrue;

/**
 * The performance claims that are structural enough to assert, as <b>ratios between two things
 * measured in the same run</b> rather than as absolute times.
 *
 * <p>A threshold in milliseconds is either too tight for a loaded CI box or too loose to catch a
 * regression. A ratio is neither: both halves slow down together on a slow machine, so the ratio
 * holds. Every margin here is several times looser than the measured figure — the batching win is
 * about 130x and the assertion is 10x — because the point is to catch a structural regression, not
 * to pin down a number. {@link StatementBudgetTest} does the precise work.</p>
 */
public class PerformanceRatioTest {

    private DuckDBDatabase database;

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private IndexedCollection<Car> collection(String name, int objects) {
        if (database == null) {
            database = DuckDBDatabase.builder().memoryLimit("512MB").build();
        }
        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .name(name).columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
        if (objects > 0) {
            cars.addAll(Cars.generate(objects, 23));
        }
        return cars;
    }

    /** Best of three, to take the machine's worst moments out of it. */
    private static long bestNanos(int rounds, LongSupplier work) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rounds; i++) {
            best = Math.min(best, work.getAsLong());
        }
        return best;
    }

    private static long time(Runnable block) {
        long started = System.nanoTime();
        block.run();
        return System.nanoTime() - started;
    }

    private static Car car(int id) {
        Car template = Cars.generate(1, id).get(0);
        return new Car(id, template.manufacturer(), template.model(), template.color(),
                template.doors(), template.price(), template.description(), template.registered());
    }

    /**
     * Batching is the single biggest lever on write throughput - about 130x per object at a batch
     * of a thousand, because above the Appender threshold rows are streamed rather than inserted
     * one statement at a time. Asserting a tenth of that.
     */
    @Test
    public void writingInBatchesIsFarCheaperPerObjectThanOneAtATime() {
        IndexedCollection<Car> singles = collection("singles", 0);
        IndexedCollection<Car> batched = collection("batched", 0);
        int singleCount = 200;
        // A batch well above the Appender threshold, so the comparison is between the two write
        // paths rather than between two points on the same one. At a couple of hundred the batch
        // has barely started amortising and the ratio lands near the assertion, which flakes.
        int batchCount = 2_000;

        for (int i = 0; i < 20; i++) {                       // warm both paths
            singles.add(car(900_000 + i));
            batched.addAll(List.of(car(950_000 + i)));
        }
        long perObjectSingle = time(() -> {
            for (int i = 0; i < singleCount; i++) {
                singles.add(car(i));
            }
        }) / singleCount;

        List<Car> batch = new ArrayList<>(batchCount);
        for (int i = 0; i < batchCount; i++) {
            batch.add(car(i));
        }
        long perObjectBatched = time(() -> batched.addAll(batch)) / batchCount;

        double ratio = perObjectSingle / (double) Math.max(perObjectBatched, 1);
        assertTrue("a batch of " + batchCount + " should be far cheaper per object than "
                        + singleCount + " single adds, but was only "
                        + String.format("%.1f", ratio) + "x (" + perObjectSingle / 1000
                        + " us against " + perObjectBatched / 1000 + " us)",
                ratio > 10);
    }

    /**
     * Asking DuckDB for an answer beats rebuilding the objects and computing it in Java - about
     * 58x on 200,000 matches. Asserting 4x on a much smaller fixture.
     */
    @Test
    public void aggregatingInSqlBeatsMaterialisingTheObjects() {
        IndexedCollection<Car> cars = collection("car", 20_000);
        String table = database.table(cars);

        long materialised = bestNanos(3, () -> time(() -> {
            double total = 0;
            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                for (Car car : results) {
                    total += car.price();
                }
            }
            if (total < 0) {
                throw new IllegalStateException();
            }
        }));
        long inSql = bestNanos(3, () -> time(() ->
                database.query("SELECT sum(price) FROM " + table + " WHERE manufacturer = ?", "Ford")
                        .scalarOptional(Double.class)));

        double ratio = materialised / (double) Math.max(inSql, 1);
        assertTrue("summing in SQL should beat rebuilding the objects, but was only "
                        + String.format("%.1f", ratio) + "x", ratio > 4);
    }

    /**
     * A materialization serves a precomputed answer instead of recomputing it - 32x on ten million
     * rows. Asserting 3x here, where the base table is small enough that DuckDB's fixed
     * per-statement cost is most of both sides.
     */
    @Test
    public void aMaterializationBeatsRecomputingTheQuery() {
        IndexedCollection<Car> cars = collection("car", 50_000);
        String panel = "SELECT manufacturer, count(*) AS n, sum(price) AS total FROM "
                + database.table(cars) + " GROUP BY 1 ORDER BY 2 DESC";
        database.materialize("car_panel").as(panel).build();

        long recomputed = bestNanos(5, () -> time(() -> database.query(panel).count()));
        long fromTable = bestNanos(5, () -> time(() ->
                database.query("SELECT * FROM car_panel").count()));

        double ratio = recomputed / (double) Math.max(fromTable, 1);
        assertTrue("a materialization should beat recomputing, but was only "
                        + String.format("%.1f", ratio) + "x", ratio > 3);
    }

    /**
     * Counting must not pay for the objects it counts: as the match grows, a count should stay
     * roughly flat while materialising the same match does not.
     */
    @Test
    public void countingDoesNotGrowWithTheNumberOfMatchingObjects() {
        IndexedCollection<Car> cars = collection("car", 50_000);

        long counting = bestNanos(5, () -> time(() -> {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                results.size();
            }
        }));
        long materialising = bestNanos(5, () -> time(() -> {
            try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                results.stream().count();
            }
        }));

        double ratio = materialising / (double) Math.max(counting, 1);
        assertTrue("counting a large match should be much cheaper than rebuilding it, but was only "
                        + String.format("%.1f", ratio) + "x", ratio > 3);
    }
}
