package io.quackjvm.cqengine.bench;

import io.quackjvm.cqengine.DuckDBFlags;
import io.quackjvm.cqengine.persistence.DuckDBBulkWriter;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Write costs: adding objects one at a time, and loading them in bulk.
 *
 * <p>Single adds are where a database-backed collection is weakest - each one is a transaction
 * against DuckDB - and bulk loading is where it is competitive, because the Appender path moves
 * whole batches at once.</p>
 */
public class WriteBenchmark {

    /** One object at a time, each in its own CQEngine request. */
    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Fork(value = 1, jvmArgs = {"-Xmx4g", "--enable-native-access=ALL-UNNAMED"})
    @Warmup(iterations = 2, time = 2)
    @Measurement(iterations = 3, time = 2)
    public static class SingleAdd {

        @Param({"ON_HEAP", "DUCKDB_BLOB_MEMORY", "DUCKDB_COLUMNAR_MEMORY", "DUCKDB_BLOB_FILE", "DUCKDB_COLUMNAR_FILE"})
        public Storage storage;

        private Storage.Fixture fixture;
        private Car template;
        private int nextId;

        @Setup(Level.Trial)
        public void setUp() {
            fixture = storage.create();
            List<Car> seed = Cars.generate(10_000, 42);
            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
            fixture.collection.update(List.of(), seed, bulk);
            template = seed.get(0);
            nextId = 1_000_000;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            fixture.close();
        }

        @Benchmark
        public boolean addOne() {
            // A fresh primary key each time, so no add is a no-op replacement.
            Car car = new Car(nextId++, template.manufacturer(), template.model(), template.color(),
                    template.doors(), template.price(), template.description(), template.registered());
            return fixture.collection.add(car);
        }
    }

    /**
     * Streaming objects through a {@link io.quackjvm.cqengine.persistence.DuckDBBulkWriter}, which keeps a
     * DuckDB Appender open per table instead of going through CQEngine's per-request machinery.
     * Compare against {@link BulkLoad}, which loads the same objects with {@code addAll}.
     */
    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 1, jvmArgs = {"-Xmx4g", "--enable-native-access=ALL-UNNAMED"})
    @Warmup(iterations = 3)
    @Measurement(iterations = 5)
    public static class StreamingWrite {

        // The bulk writer belongs to the persistence, so there is no on-heap equivalent.
        @Param({"DUCKDB_BLOB_MEMORY", "DUCKDB_COLUMNAR_MEMORY", "DUCKDB_BLOB_FILE", "DUCKDB_COLUMNAR_FILE"})
        public Storage storage;

        @Param({"100000"})
        public int rowCount;

        private List<Car> cars;
        private Storage.Fixture fixture;

        @Setup(Level.Trial)
        public void generate() {
            cars = Cars.generate(rowCount, 42);
        }

        @Setup(Level.Iteration)
        public void freshCollection() {
            fixture = storage.create();
        }

        @TearDown(Level.Iteration)
        public void closeCollection() {
            fixture.close();
        }

        @Benchmark
        public long stream() {
            try (DuckDBBulkWriter<Car> writer = fixture.persistence.bulkWriter()) {
                for (Car car : cars) {
                    writer.add(car);
                }
                return writer.getObjectsWritten();
            }
        }
    }

    /** A whole batch in one request, which is how a database-backed collection wants to be loaded. */
    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 1, jvmArgs = {"-Xmx4g", "--enable-native-access=ALL-UNNAMED"})
    @Warmup(iterations = 3)
    @Measurement(iterations = 5)
    public static class BulkLoad {

        @Param({"ON_HEAP", "DUCKDB_BLOB_MEMORY", "DUCKDB_COLUMNAR_MEMORY", "DUCKDB_BLOB_FILE", "DUCKDB_COLUMNAR_FILE"})
        public Storage storage;

        @Param({"100000"})
        public int rowCount;

        /** Whether to tell the persistence the objects are new, letting it skip the idempotency step. */
        @Param({"true", "false"})
        public boolean bulkImportFlag;

        private List<Car> cars;
        private Storage.Fixture fixture;

        @Setup(Level.Trial)
        public void generate() {
            cars = Cars.generate(rowCount, 42);
        }

        @Setup(Level.Iteration)
        public void freshCollection() {
            fixture = storage.create();
        }

        @TearDown(Level.Iteration)
        public void closeCollection() {
            fixture.close();
        }

        @Benchmark
        public int load() {
            QueryOptions options = new QueryOptions();
            if (bulkImportFlag) {
                FlagsEnabled.forQueryOptions(options).add(DuckDBFlags.BULK_IMPORT);
            }
            fixture.collection.update(List.of(), cars, options);
            return cars.size();
        }
    }
}
