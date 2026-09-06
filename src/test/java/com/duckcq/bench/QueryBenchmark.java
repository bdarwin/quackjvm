package com.duckcq.bench;

import com.duckcq.DuckDBFlags;
import com.duckcq.testutil.Car;
import com.duckcq.testutil.Cars;
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
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.lessThan;

/**
 * Query latency for each storage configuration: the operations an application performs inside a
 * request, where the fixed per-query cost of going through a database matters most.
 *
 * <p>Run all of them:</p>
 * <pre>
 * mvn test-compile
 * java -cp &lt;classpath&gt; org.openjdk.jmh.Main 'QueryBenchmark'
 * java -cp &lt;classpath&gt; org.openjdk.jmh.Main 'QueryBenchmark.pointLookup' -p storage=ON_HEAP
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx4g", "--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class QueryBenchmark {

    @Param({"ON_HEAP", "DUCKDB_BLOB_MEMORY", "DUCKDB_COLUMNAR_MEMORY", "DUCKDB_BLOB_FILE", "DUCKDB_COLUMNAR_FILE"})
    public Storage storage;

    @Param({"200000"})
    public int rowCount;

    private Storage.Fixture fixture;
    private SplittableRandom random;

    @Setup(Level.Trial)
    public void setUp() {
        fixture = storage.create();
        QueryOptions bulk = new QueryOptions();
        FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
        fixture.collection.update(List.of(), Cars.generate(rowCount, 42), bulk);
        random = new SplittableRandom(1);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.close();
    }

    /** Fetch one object by primary key. */
    @Benchmark
    public Car pointLookup() {
        try (ResultSet<Car> results = fixture.collection.retrieve(equal(Car.CAR_ID, random.nextInt(rowCount)))) {
            return results.uniqueResult();
        }
    }

    /** Equality on an indexed attribute with 50 distinct values: ~2% of the collection. */
    @Benchmark
    public int selectiveEqual(Blackhole blackhole) {
        try (ResultSet<Car> results = fixture.collection.retrieve(
                equal(Car.MODEL, "model" + random.nextInt(50)))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }

    /** A narrow range matching a few dozen objects: the shape of a typical lookup query. */
    @Benchmark
    public int narrowRange(Blackhole blackhole) {
        double lower = random.nextDouble() * 40_000;
        try (ResultSet<Car> results = fixture.collection.retrieve(between(Car.PRICE, lower, lower + 2.5))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }

    /** Two indexed attributes intersected, then materialised. */
    @Benchmark
    public int compoundQuery(Blackhole blackhole) {
        try (ResultSet<Car> results = fixture.collection.retrieve(
                and(equal(Car.COLOR, Car.Color.BLUE), lessThan(Car.PRICE, 5_000.0)))) {
            int count = 0;
            for (Car car : results) {
                blackhole.consume(car);
                count++;
            }
            return count;
        }
    }

    /** Count matching objects without materialising any of them. */
    @Benchmark
    public int countOnly() {
        try (ResultSet<Car> results = fixture.collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            return results.size();
        }
    }

    /** Size of the whole collection. */
    @Benchmark
    public int collectionSize() {
        return fixture.collection.size();
    }
}
