/*
 * CQEngine: the drop-in swap. The same collection, the same indexes and exactly the same query
 * code, first on the Java heap and then in DuckDB - only the constructor argument differs.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx2g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineSwap.java [objectCount]
 */

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;

import java.util.ArrayList;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;

public class CqEngineSwap {

    public record Car(int carId, String manufacturer, String model, int doors, double price) {

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
        static final Attribute<Car, Double> PRICE =
                new SimpleAttribute<>(Car.class, Double.class, "price") {
                    public Double getValue(Car car, QueryOptions options) {
                        return car.price();
                    }
                };
    }

    /** The query code below never changes between the two storage choices - that is the point. */
    private static final Query<Car> QUERY =
            and(equal(Car.MANUFACTURER, "Ford"), between(Car.PRICE, 20_000.0, 21_000.0));

    public static void main(String[] args) {
        int objectCount = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        System.out.printf("Storing %,d cars two ways.%n%n", objectCount);

        long baselineHeap = usedHeapBytes();

        // ---------- 1. Stock CQEngine, on the Java heap ----------
        IndexedCollection<Car> onHeap = new ConcurrentIndexedCollection<>();
        onHeap.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        onHeap.addAll(cars(objectCount));
        long onHeapBytes = usedHeapBytes() - baselineHeap;
        report("on-heap ConcurrentIndexedCollection", onHeap, onHeapBytes, -1);
        onHeap = null;

        // ---------- 2. The same thing, stored in DuckDB ----------
        // onPrimaryKey serializes each object into a BLOB, which needs no mapping code. Supplying
        // a ColumnarLayout instead shreds objects into typed columns, which compresses far better
        // and is what lets SQL and joins see the fields.
        try (DuckDBPersistence<Car, Integer> persistence = DuckDBPersistence.builder(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .memoryLimit("256MB")
                .build()) {

            IndexedCollection<Car> inDuckDB = new ConcurrentIndexedCollection<>(persistence);
            inDuckDB.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
            inDuckDB.addAll(cars(objectCount));

            long inDuckDBBytes = usedHeapBytes() - baselineHeap;
            report("DuckDBPersistence", inDuckDB, inDuckDBBytes, persistence.getBytesUsed());
        }

        System.out.println();
        System.out.println("Java heap is what -Xmx sizes and the garbage collector walks. DuckDB's own");
        System.out.println("bytes are native memory, so the two numbers are not added together, and a");
        System.out.println("fair comparison of the whole process needs resident set size, not the heap.");
    }

    /**
     * Runs the shared query and prints what the collection cost. A ResultSet over a persisted
     * collection holds a database connection, so it is always closed.
     */
    private static void report(String label, IndexedCollection<Car> cars, long heapBytes, long databaseBytes) {
        long startedAt = System.nanoTime();
        int matches;
        Car first;
        try (ResultSet<Car> results = cars.retrieve(QUERY)) {
            matches = results.size();
            first = matches == 0 ? null : results.iterator().next();
        }
        long elapsedMicros = (System.nanoTime() - startedAt) / 1000;

        System.out.println(label);
        System.out.printf("  objects stored     %,d%n", cars.size());
        System.out.printf("  query matched      %,d in %,d us, first: %s%n", matches, elapsedMicros, first);
        System.out.printf("  Java heap used     %,d MB%n", heapBytes / 1024 / 1024);
        if (databaseBytes >= 0) {
            System.out.printf("  DuckDB bytes used  %,d MB (native memory, not the heap)%n",
                    databaseBytes / 1024 / 1024);
        }
        System.out.println();
    }

    private static List<Car> cars(int objectCount) {
        String[] manufacturers = {"Ford", "Honda", "Toyota", "BMW"};
        String[] models = {"Focus", "Civic", "Prius", "M3"};
        List<Car> cars = new ArrayList<>(objectCount);
        for (int i = 0; i < objectCount; i++) {
            cars.add(new Car(i, manufacturers[i % 4], models[(i / 4) % 4], 3 + (i % 3),
                    5_000.0 + (i % 40_000)));
        }
        return cars;
    }

    /** A crude but repeatable reading: collect, then ask what is left. */
    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}

/*
 * Output (Apple Silicon, JDK 25, -Xmx2g, default 200,000 objects):
 *
 * Storing 200,000 cars two ways.
 *
 * on-heap ConcurrentIndexedCollection
 *   objects stored     200,000
 *   query matched      1,255 in 6,863 us, first: Car[carId=55000, manufacturer=Ford, model=Prius, doors=4, price=20000.0]
 *   Java heap used     33 MB
 *
 * DuckDBPersistence
 *   objects stored     200,000
 *   query matched      1,255 in 33,986 us, first: Car[carId=135804, manufacturer=Ford, model=M3, doors=3, price=20804.0]
 *   Java heap used     0 MB
 *   DuckDB bytes used  17 MB (native memory, not the heap)
 *
 * Both timings include one-off warm-up; they are not a benchmark. The two "first" cars differ
 * because neither collection promises an order unless the query asks for one.
 */
