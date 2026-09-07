package io.quackjvm.cqengine.bench;

import io.quackjvm.cqengine.DuckDBFlags;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.has;

/**
 * Compares memory, disk and query latency for the same collection held three ways:
 * on the Java heap, in DuckDB as serialized BLOBs, and in DuckDB shredded into columns.
 *
 * <p>Run with: {@code mvn test-compile && java -cp <classpath> io.quackjvm.cqengine.bench.StorageBenchmark [rowCount]}</p>
 */
public class StorageBenchmark {

    private static final int WARMUP_QUERIES = 20;
    private static final int MEASURED_QUERIES = 200;

    public static void main(String[] args) throws Exception {
        int rowCount = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        System.out.printf("Generating %,d cars...%n", rowCount);
        List<Car> cars = Cars.generate(rowCount, 42);

        List<Result> results = new ArrayList<>();
        results.add(measureOnHeap(cars));
        results.add(measureDuckDB(cars, "DuckDB (BLOB)", false, false));
        results.add(measureDuckDB(cars, "DuckDB (columnar)", true, false));
        results.add(measureDuckDB(cars, "DuckDB (col + ART)", true, true));

        System.out.println();
        System.out.printf("%-20s %10s %10s %10s %13s %13s %13s %13s %13s%n",
                "storage", "load", "heap", "on disk", "pk lookup", "equal 200k", "range 40k", "range ~50", "count only");
        System.out.println("-".repeat(130));
        for (Result result : results) {
            System.out.printf("%-20s %8.1f s %10s %10s %10.3f ms %10.1f ms %10.1f ms %10.3f ms %10.3f ms%n",
                    result.name, result.loadSeconds, mb(result.heapBytes),
                    result.diskBytes == 0 ? "-" : mb(result.diskBytes),
                    result.pointLookupMillis / 20, result.equalQueryMillis, result.rangeQueryMillis,
                    result.selectiveQueryMillis, result.countMillis);
        }
        System.out.println();
        System.out.println("pk lookup = one retrieve(equal(CAR_ID, ...)) returning a single object");
        System.out.println("heap      = retained heap after load and a full GC");
        System.out.println("on disk   = size of the DuckDB database file after a checkpoint");
    }

    private static String mb(long bytes) {
        return String.format("%,d MB", bytes / (1024 * 1024));
    }

    private record Result(String name, double loadSeconds, long heapBytes, long diskBytes,
                          double pointLookupMillis, double equalQueryMillis, double rangeQueryMillis,
                          double selectiveQueryMillis, double countMillis) {
    }

    private static Result measureOnHeap(List<Car> cars) {
        System.out.println("\n== on-heap ==");
        long baseline = usedHeap();
        IndexedCollection<Car> collection = new ConcurrentIndexedCollection<>();
        // DuckDB persistence indexes the primary key for free, so the on-heap baseline gets an
        // equivalent index to keep the point-lookup comparison fair.
        collection.addIndex(HashIndex.onAttribute(Car.CAR_ID));
        collection.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
        collection.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        collection.addIndex(HashIndex.onAttribute(Car.COLOR));

        long start = System.nanoTime();
        collection.addAll(cars);
        double loadSeconds = seconds(start);
        long heap = usedHeap() - baseline;

        return new Result("on-heap", loadSeconds, heap, 0,
                measure(() -> pointLookups(collection, cars)),
                measure(() -> equalQuery(collection)),
                measure(() -> rangeQuery(collection)),
                measure(() -> selectiveQuery(collection)),
                measure(() -> countOnly(collection)));
    }

    private static Result measureDuckDB(List<Car> cars, String name, boolean columnar, boolean artIndexes) throws Exception {
        System.out.println("\n== " + name + " ==");
        File file = File.createTempFile("bench_", ".duckdb");
        if (!file.delete()) {
            throw new IllegalStateException("could not clear " + file);
        }
        file.deleteOnExit();

        DuckDBPersistence.Builder<Car, Integer> builder = DuckDBPersistence.builder(Car.CAR_ID).file(file);
        if (columnar) {
            builder.columnarLayout(ColumnarLayout.ofRecord(Car.class));
        }
        try (DuckDBPersistence<Car, Integer> persistence = builder.build()) {
            long baseline = usedHeap();
            IndexedCollection<Car> collection = new ConcurrentIndexedCollection<>(persistence);
            collection.addIndex(index(Car.MANUFACTURER, artIndexes));
            collection.addIndex(index(Car.PRICE, artIndexes));
            collection.addIndex(index(Car.COLOR, artIndexes));

            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);

            long start = System.nanoTime();
            collection.update(List.of(), cars, bulk);
            double loadSeconds = seconds(start);

            long disk = persistence.getBytesUsed();
            long heap = usedHeap() - baseline;

            return new Result(name, loadSeconds, Math.max(heap, 0), disk,
                    measure(() -> pointLookups(collection, cars)),
                    measure(() -> equalQuery(collection)),
                    measure(() -> rangeQuery(collection)),
                    measure(() -> selectiveQuery(collection)),
                    measure(() -> countOnly(collection)));
        }
    }

    private static <A extends Comparable<A>> DuckDBIndex<A, Car, ? extends Comparable<?>> index(
            com.googlecode.cqengine.attribute.Attribute<Car, A> attribute, boolean artIndexes) {
        return artIndexes ? DuckDBIndex.onAttributeWithArtIndex(attribute) : DuckDBIndex.onAttribute(attribute);
    }

    // ---------- Workloads ----------

    private static int pointLookups(IndexedCollection<Car> collection, List<Car> cars) {
        int found = 0;
        for (int i = 0; i < 20; i++) {
            int carId = (int) ((System.nanoTime() + i * 7919L) % cars.size());
            try (ResultSet<Car> results = collection.retrieve(equal(Car.CAR_ID, carId))) {
                if (results.uniqueResult() != null) found++;
            }
        }
        return found;
    }

    private static int equalQuery(IndexedCollection<Car> collection) {
        try (ResultSet<Car> results = collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            int count = 0;
            for (Car ignored : results) {
                count++;
            }
            return count;
        }
    }

    private static int rangeQuery(IndexedCollection<Car> collection) {
        try (ResultSet<Car> results = collection.retrieve(between(Car.PRICE, 10_000.0, 12_000.0))) {
            int count = 0;
            for (Car ignored : results) {
                count++;
            }
            return count;
        }
    }

    /** A narrow range which matches only a few dozen objects - the common shape of an OLTP query. */
    private static int selectiveQuery(IndexedCollection<Car> collection) {
        try (ResultSet<Car> results = collection.retrieve(between(Car.PRICE, 10_000.0, 10_002.5))) {
            int count = 0;
            for (Car ignored : results) {
                count++;
            }
            return count;
        }
    }

    private static int countOnly(IndexedCollection<Car> collection) {
        try (ResultSet<Car> results = collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            return results.size();
        }
    }

    // ---------- Timing ----------

    private static double measure(Supplier<Integer> workload) {
        int checksum = 0;
        for (int i = 0; i < WARMUP_QUERIES; i++) {
            checksum += workload.get();
        }
        int iterations = Math.max(1, MEASURED_QUERIES / 10);
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            checksum += workload.get();
        }
        double millis = (System.nanoTime() - start) / 1_000_000.0 / iterations;
        if (checksum < 0) {
            throw new IllegalStateException("unreachable");
        }
        return millis;
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static long usedHeap() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(80);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
