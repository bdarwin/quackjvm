package io.quackjvm.cqengine.bench;

import io.quackjvm.cqengine.DuckDBFlags;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.has;

/**
 * Throughput of materialising many whole objects - the workload where moving the collection out of
 * the heap costs the most, because every object has to be rebuilt from the database.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx4g", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
public class ScanBenchmark {

    @Param({"ON_HEAP", "DUCKDB_BLOB_MEMORY", "DUCKDB_COLUMNAR_MEMORY", "DUCKDB_BLOB_FILE", "DUCKDB_COLUMNAR_FILE"})
    public Storage storage;

    @Param({"200000"})
    public int rowCount;

    private Storage.Fixture fixture;

    @Setup(Level.Trial)
    public void setUp() {
        fixture = storage.create();
        QueryOptions bulk = new QueryOptions();
        FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
        fixture.collection.update(List.of(), Cars.generate(rowCount, 42), bulk);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.close();
    }

    /** Materialise every object matching an indexed attribute: about a fifth of the collection. */
    @Benchmark
    public int materialiseIndexedSubset(Blackhole blackhole) {
        try (ResultSet<Car> results = fixture.collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }

    /** Iterate the entire collection. */
    @Benchmark
    public int iterateEverything(Blackhole blackhole) {
        try (ResultSet<Car> results = fixture.collection.retrieve(has(Car.CAR_ID))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }

    /**
     * A full scan filtered on an unindexed attribute, which CQEngine answers by streaming every
     * object out of storage and testing it on the heap.
     */
    @Benchmark
    public int unindexedFilter(Blackhole blackhole) {
        try (ResultSet<Car> results = fixture.collection.retrieve(equal(Car.DOORS, 3))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }
}
