/*
 * CQEngine: adding DuckDBIndex, and running equal, between and compound and() queries against it.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineIndexes.java
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBIndexedCollection;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static com.googlecode.cqengine.query.QueryFactory.not;
import static com.googlecode.cqengine.query.QueryFactory.or;

public class CqEngineIndexes {

    public enum Colour {RED, GREEN, BLUE, BLACK, WHITE}

    public record Car(int carId, String manufacturer, Colour colour, int doors, double price) {

        static final SimpleAttribute<Car, Integer> CAR_ID =
                new SimpleAttribute<>(Car.class, Integer.class, "carId") {
                    public Integer getValue(Car car, QueryOptions options) {
                        return car.carId();
                    }
                };
        static final Attribute<Car, String> MANUFACTURER =
                new SimpleAttribute<>(Car.class, String.class, "manufacturer") {
                    public String getValue(Car car, QueryOptions options) {
                        return car.manufacturer();
                    }
                };
        // CQEngine's own SQLite indexes reject enum attributes; DuckDB stores them as the ordinal,
        // so they index like any other value.
        static final Attribute<Car, Colour> COLOUR =
                new SimpleAttribute<>(Car.class, Colour.class, "colour") {
                    public Colour getValue(Car car, QueryOptions options) {
                        return car.colour();
                    }
                };
        static final Attribute<Car, Integer> DOORS =
                new SimpleAttribute<>(Car.class, Integer.class, "doors") {
                    public Integer getValue(Car car, QueryOptions options) {
                        return car.doors();
                    }
                };
        static final Attribute<Car, Double> PRICE =
                new SimpleAttribute<>(Car.class, Double.class, "price") {
                    public Double getValue(Car car, QueryOptions options) {
                        return car.price();
                    }
                };
    }

    public static void main(String[] args) {
        try (DuckDBPersistence<Car, Integer> persistence = DuckDBPersistence.builder(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .memoryLimit("256MB")
                .build()) {

            // A plain ConcurrentIndexedCollection over this persistence works, but CQEngine would
            // then intersect the branches of an and() in Java, retrieving objects only to discard
            // them. DuckDBIndexedCollection sends the whole expression to DuckDB as one statement.
            IndexedCollection<Car> cars = new DuckDBIndexedCollection<>(persistence);

            // Indexes are stored in DuckDB alongside the objects, as (objectKey, value) tables.
            // Add them before loading: an index added afterwards is built by rescanning everything.
            cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            cars.addIndex(DuckDBIndex.onAttribute(Car.COLOUR));
            cars.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
            // DOORS is left unindexed, to show that an unindexed attribute still answers correctly.

            cars.addAll(cars(100_000));
            System.out.printf("Loaded %,d cars, indexed on manufacturer, colour and price.%n%n",
                    cars.size());

            run(cars, "equal on an indexed String", equal(Car.MANUFACTURER, "Honda"));
            run(cars, "equal on an indexed enum", equal(Car.COLOUR, Colour.BLUE));
            run(cars, "between on an indexed double",
                    between(Car.PRICE, 20_000.0, 20_500.0));
            run(cars, "equal on an unindexed int", equal(Car.DOORS, 5));

            // A compound query is translated into a single SQL statement rather than being
            // intersected object by object in Java, so adding conditions narrows the work instead
            // of adding to it.
            run(cars, "and of three conditions",
                    and(equal(Car.MANUFACTURER, "Honda"),
                            equal(Car.COLOUR, Colour.BLUE),
                            between(Car.PRICE, 20_000.0, 25_000.0)));
            run(cars, "or, and not",
                    and(or(equal(Car.MANUFACTURER, "Honda"), equal(Car.MANUFACTURER, "BMW")),
                            not(equal(Car.COLOUR, Colour.RED)),
                            greaterThan(Car.PRICE, 40_000.0)));
        }
    }

    private static void run(IndexedCollection<Car> cars, String label, Query<Car> query) {
        long startedAt = System.nanoTime();
        int matches;
        Car sample;
        // The result set holds a database connection until closed.
        try (ResultSet<Car> results = cars.retrieve(query)) {
            matches = results.size();
            sample = matches == 0 ? null : results.iterator().next();
        }
        System.out.printf("%-28s %,7d matches in %,6d us%n", label, matches,
                (System.nanoTime() - startedAt) / 1000);
        System.out.printf("%-28s %s%n", "  query", query);
        System.out.printf("%-28s %s%n%n", "  one of them", sample);
    }

    private static List<Car> cars(int objectCount) {
        String[] manufacturers = {"Ford", "Honda", "Toyota", "BMW", "Kia"};
        Colour[] colours = Colour.values();
        List<Car> cars = new ArrayList<>(objectCount);
        for (int i = 0; i < objectCount; i++) {
            cars.add(new Car(i, manufacturers[i % manufacturers.length], colours[(i / 5) % colours.length],
                    3 + (i % 3), 5_000.0 + (i % 45_000)));
        }
        return cars;
    }
}

/*
 * Output (Apple Silicon, JDK 25):
 *
 * Loaded 100,000 cars, indexed on manufacturer, colour and price.
 *
 * equal on an indexed String    20,000 matches in 11,864 us
 *   query                      equal("manufacturer", "Honda")
 *   one of them                Car[carId=581, manufacturer=Honda, colour=GREEN, doors=5, price=5581.0]
 *
 * equal on an indexed enum      20,000 matches in  5,312 us
 *   query                      equal("colour", BLUE)
 *   one of them                Car[carId=312, manufacturer=Toyota, colour=BLUE, doors=3, price=5312.0]
 *
 * between on an indexed double   1,002 matches in  4,902 us
 *   query                      between("price", 20000.0, 20500.0)
 *   one of them                Car[carId=15063, manufacturer=BMW, colour=BLUE, doors=3, price=20063.0]
 *
 * equal on an unindexed int     33,333 matches in 86,568 us
 *   query                      equal("doors", 5)
 *   one of them                Car[carId=122, manufacturer=Toyota, colour=WHITE, doors=5, price=5122.0]
 *
 * and of three conditions          400 matches in  9,358 us
 *   query                      and(equal("manufacturer", "Honda"), equal("colour", BLUE), between("price", 20000.0, 25000.0))
 *   one of them                Car[carId=15836, manufacturer=Honda, colour=BLUE, doors=5, price=20836.0]
 *
 * or, and not                    6,400 matches in 20,976 us
 *   query                      and(or(equal("manufacturer", "Honda"), equal("manufacturer", "BMW")), not(equal("colour", RED)), greaterThan("price", 40000.0))
 *   one of them                Car[carId=35181, manufacturer=Honda, colour=GREEN, doors=3, price=40181.0]
 *
 * The unindexed attribute is the slowest here because every object has to be rebuilt from its
 * columns; the timings include warm-up and are not a benchmark.
 */
