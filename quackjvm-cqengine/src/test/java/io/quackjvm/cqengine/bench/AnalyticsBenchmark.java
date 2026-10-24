package io.quackjvm.cqengine.bench;

import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import static com.googlecode.cqengine.query.QueryFactory.all;
import static com.googlecode.cqengine.query.QueryFactory.equal;

/**
 * The database's questions rather than the heap's: aggregates over a million objects, on-heap
 * CQEngine against the same objects in DuckDB. Then what a one-row lookup costs in DuckDB, and
 * why an index does not help it.
 *
 * <pre>
 * java -cp &lt;classpath&gt; io.quackjvm.cqengine.bench.AnalyticsBenchmark [rowCount] [memoryLimit]
 * </pre>
 *
 * Every figure is the median of repeated runs after a warm-up.
 */
public class AnalyticsBenchmark {

    public static void main(String[] args) throws Exception {
        int rowCount = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        String memoryLimit = args.length > 1 ? args[1] : "256MB";
        List<Car> cars = Cars.generate(rowCount, 42);

        IndexedCollection<Car> heap = new ConcurrentIndexedCollection<>();
        heap.addIndex(HashIndex.onAttribute(Car.CAR_ID));
        heap.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
        heap.addIndex(NavigableIndex.onAttribute(Car.PRICE));
        heap.addIndex(HashIndex.onAttribute(Car.MODEL));
        heap.addAll(cars);

        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit(memoryLimit).build()) {
            IndexedCollection<Car> duck = database.collection(Car.CAR_ID).name("car")
                    .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
            duck.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
            duck.addIndex(DuckDBIndex.onAttribute(Car.PRICE));
            duck.addIndex(DuckDBIndex.onAttribute(Car.MODEL));
            duck.addAll(cars);
            String car = database.table(duck);

            System.out.printf("%,d cars, %,d of them Ford. DuckDB memory_limit=%s.%n%n", rowCount,
                    cars.stream().filter(c -> c.manufacturer().equals("Ford")).count(), memoryLimit);
            System.out.printf("  %-42s %12s %12s%n", "", "on-heap", "DuckDB");

            row("sum a column over the Ford matches",
                    () -> {
                        double total = 0;
                        try (ResultSet<Car> fords = heap.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                            for (Car c : fords) {
                                total += c.price();
                            }
                        }
                        return total;
                    },
                    () -> database.query("SELECT sum(price) FROM " + car + " WHERE manufacturer = ?",
                            "Ford").scalar(Double.class));

            row("the same, materialising the objects first", null, () -> {
                double total = 0;
                try (ResultSet<Car> fords = duck.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                    for (Car c : fords) {
                        total += c.price();
                    }
                }
                return total;
            });

            row("group by make: count and average price",
                    () -> {
                        Map<String, double[]> groups = new HashMap<>();
                        try (ResultSet<Car> everything = heap.retrieve(all(Car.class))) {
                            for (Car c : everything) {
                                double[] g = groups.computeIfAbsent(c.manufacturer(), k -> new double[2]);
                                g[0]++;
                                g[1] += c.price();
                            }
                        }
                        groups.replaceAll((k, g) -> new double[]{g[0], g[1] / g[0]});
                        return groups;
                    },
                    () -> database.query("SELECT manufacturer, count(*), avg(price) FROM " + car
                            + " GROUP BY 1").stream().count());

            row("pivot: makes across years", null, () -> database.query(
                    "PIVOT " + car + " ON year(registered) USING count(*) GROUP BY manufacturer")
                    .stream().count());

            row("median price", null, () -> database.query(
                    "SELECT median(price) FROM " + car).scalar(Double.class));

            row("approximate distinct models", null, () -> database.query(
                    "SELECT approx_count_distinct(model) FROM " + car).scalar(Long.class));
        }

        lookups(rowCount, memoryLimit);
    }

    /** One row by key, in plain DuckDB: with a primary key, without one, with an ART index. */
    private static void lookups(int rowCount, String memoryLimit) throws Exception {
        System.out.printf("%nFetching one row by key from %,d rows, plain JDBC:%n%n", rowCount);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("SET memory_limit = '" + memoryLimit + "'");
            String columns = "id INTEGER%s, manufacturer VARCHAR, model VARCHAR, color INTEGER,"
                    + " doors INTEGER, price DOUBLE, description VARCHAR, registered TIMESTAMP";
            String rows = " SELECT range::INTEGER, 'make' || (range % 5), 'model' || (range % 50),"
                    + " range % 5, 3 + 2 * (range % 2), (range % 50000) / 1.0, 'word' || (range % 97),"
                    + " TIMESTAMP '2000-01-01' + to_days((range % 9000)::INTEGER) FROM range(" + rowCount + ")";
            statement.execute("CREATE TABLE with_key (" + String.format(columns, " PRIMARY KEY") + ")");
            statement.execute("INSERT INTO with_key" + rows);
            statement.execute("CREATE TABLE no_key (" + String.format(columns, "") + ")");
            statement.execute("INSERT INTO no_key" + rows);
            statement.execute("CREATE TABLE with_art (" + String.format(columns, "") + ")");
            statement.execute("INSERT INTO with_art" + rows);
            statement.execute("CREATE INDEX with_art_id ON with_art (id)");
            statement.execute("CHECKPOINT");

            lookup(connection, "table with a PRIMARY KEY", "SELECT * FROM with_key WHERE id = ?", rowCount);
            lookup(connection, "table with no key at all", "SELECT * FROM no_key WHERE id = ?", rowCount);
            lookup(connection, "table with an explicit ART index", "SELECT * FROM with_art WHERE id = ?", rowCount);
            lookup(connection, "SELECT 1, touching no table", "SELECT 1 WHERE ? >= 0", rowCount);
        }
    }

    private static void lookup(Connection connection, String label, String sql, int rowCount) throws Exception {
        Random random = new Random(7);
        try (PreparedStatement prepared = connection.prepareStatement(sql)) {
            double[] micros = new double[3_000];
            for (int i = -1_000; i < micros.length; i++) {
                prepared.setInt(1, random.nextInt(rowCount));
                long start = System.nanoTime();
                try (java.sql.ResultSet rows = prepared.executeQuery()) {
                    while (rows.next()) {
                        rows.getObject(1);
                    }
                }
                if (i >= 0) {
                    micros[i] = (System.nanoTime() - start) / 1_000.0;
                }
            }
            System.out.printf("  %-36s %9s%n", label, format(median(micros) / 1_000.0));
        }
    }

    private static void row(String label, Supplier<Object> onHeap, Supplier<Object> duckDB) {
        String heapTime = onHeap == null ? "-" : format(time(onHeap));
        System.out.printf("  %-42s %12s %12s%n", label, heapTime, format(time(duckDB)));
    }

    /** Median milliseconds over 30 runs, after 10 warm-up runs. */
    private static double time(Supplier<Object> workload) {
        double[] millis = new double[30];
        Object sink = null;
        for (int i = -10; i < millis.length; i++) {
            long start = System.nanoTime();
            sink = workload.get();
            if (i >= 0) {
                millis[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
        }
        if (sink == null) {
            throw new IllegalStateException("no result");
        }
        return median(millis);
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String format(double millis) {
        return millis < 1 ? String.format("%.0f us", millis * 1_000) : String.format("%.1f ms", millis);
    }
}
