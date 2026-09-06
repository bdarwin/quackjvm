package com.duckcq.bench;

import com.duckcq.DuckDBFlags;
import com.duckcq.testutil.Car;
import com.duckcq.testutil.Cars;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.IntSupplier;

import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.has;

/**
 * How query latency scales with the size of the collection.
 *
 * <p>Answers the question a single-size benchmark cannot: which operations have a fixed cost,
 * which grow with the data, and how fast. Each (storage, size) pair runs in its own JVM.</p>
 *
 * <pre>
 * java -cp &lt;classpath&gt; com.duckcq.bench.ScalingBenchmark [maxHeap] [memoryLimit] [size,size,...]
 * </pre>
 */
public class ScalingBenchmark {

    private static final List<String> OPERATIONS =
            List.of("pk lookup", "count only", "narrow range", "equal ~2%", "iterate all");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--probe")) {
            Storage.optimizeAfterLoad = args.length > 4 && Boolean.parseBoolean(args[4]);
            probe(Storage.valueOf(args[1]), Integer.parseInt(args[2]), "-".equals(args[3]) ? null : args[3]);
            return;
        }
        if (args.length > 0 && args[0].equals("--optimized")) {
            runOptimizedComparison(args.length > 1 ? args[1] : "12g", args.length > 2 ? args[2] : "1GB");
            return;
        }
        String maxHeap = args.length > 0 ? args[0] : "12g";
        String memoryLimit = args.length > 1 ? args[1] : "1GB";
        List<Integer> sizes = new ArrayList<>();
        if (args.length > 2) {
            for (String size : args[2].split(",")) {
                sizes.add(Integer.parseInt(size.trim()));
            }
        }
        else {
            sizes = List.of(125_000, 250_000, 500_000, 1_000_000, 2_000_000, 4_000_000);
        }

        List<Storage> storages = List.of(Storage.ON_HEAP, Storage.DUCKDB_BLOB_FILE,
                Storage.DUCKDB_COLUMNAR_FILE, Storage.DUCKDB_COLUMNAR_FILE_ART);

        // results[operation][storage][size]
        Map<String, Map<Storage, Map<Integer, String>>> results = new LinkedHashMap<>();
        for (String operation : OPERATIONS) {
            results.put(operation, new LinkedHashMap<>());
        }

        for (Storage storage : storages) {
            for (int size : sizes) {
                System.out.printf("  measuring %s at %,d ... ", storage, size);
                System.out.flush();
                Map<String, String> measured = fork(storage, size, maxHeap, memoryLimit);
                System.out.println(measured.isEmpty() ? "FAILED" : "done");
                for (String operation : OPERATIONS) {
                    results.get(operation)
                            .computeIfAbsent(storage, s -> new LinkedHashMap<>())
                            .put(size, measured.getOrDefault(operation, "-"));
                }
            }
        }

        System.out.println();
        for (String operation : OPERATIONS) {
            System.out.println(operation.toUpperCase());
            printTable(results.get(operation), storages, sizes);
            System.out.println();
        }
    }

    private static void printTable(Map<Storage, Map<Integer, String>> rows, List<Storage> storages,
                                   List<Integer> sizes) {
        int nameWidth = 24;
        StringBuilder header = new StringBuilder(String.format("%-" + nameWidth + "s", "storage"));
        for (int size : sizes) {
            header.append(String.format("%12s", formatSize(size)));
        }
        System.out.println(header);
        System.out.println("-".repeat(nameWidth + 12 * sizes.size()));
        for (Storage storage : storages) {
            StringBuilder line = new StringBuilder(String.format("%-" + nameWidth + "s", label(storage)));
            Map<Integer, String> values = rows.getOrDefault(storage, Map.of());
            for (int size : sizes) {
                line.append(String.format("%12s", values.getOrDefault(size, "-")));
            }
            System.out.println(line);
        }
    }

    private static String formatSize(int size) {
        return size >= 1_000_000 ? (size / 1_000_000) + "M" : (size / 1000) + "k";
    }

    /**
     * Compares each DuckDB configuration with and without optimize() having been called.
     * Each (storage, size, optimized) combination is loaded once and every operation measured
     * from that one process.
     */
    private static void runOptimizedComparison(String maxHeap, String memoryLimit) throws Exception {
        List<Integer> sizes = List.of(500_000, 1_000_000, 2_000_000, 4_000_000);
        List<Storage> storages = List.of(Storage.DUCKDB_BLOB_FILE, Storage.DUCKDB_COLUMNAR_FILE);

        // rows keyed by "<label>|<optimized>", each holding size -> operation -> value
        Map<String, Map<Integer, Map<String, String>>> measured = new LinkedHashMap<>();
        for (Storage storage : storages) {
            for (boolean optimized : new boolean[]{false, true}) {
                String rowKey = label(storage) + (optimized ? " + optimize()" : "");
                Map<Integer, Map<String, String>> bySize = new LinkedHashMap<>();
                for (int size : sizes) {
                    System.out.printf("  measuring %s at %,d ... ", rowKey, size);
                    System.out.flush();
                    Map<String, String> result = fork(storage, size, maxHeap, memoryLimit, optimized);
                    System.out.println(result.isEmpty() ? "FAILED" : "done");
                    bySize.put(size, result);
                }
                measured.put(rowKey, bySize);
            }
        }

        System.out.println();
        for (String operation : OPERATIONS) {
            System.out.println(operation.toUpperCase());
            System.out.printf("%-34s", "storage");
            for (int size : sizes) {
                System.out.printf("%12s", formatSize(size));
            }
            System.out.println();
            System.out.println("-".repeat(34 + 12 * sizes.size()));
            measured.forEach((rowKey, bySize) -> {
                System.out.printf("%-34s", rowKey);
                for (int size : sizes) {
                    System.out.printf("%12s", bySize.getOrDefault(size, Map.of()).getOrDefault(operation, "-"));
                }
                System.out.println();
            });
            System.out.println();
        }
    }

    private static Map<String, String> fork(Storage storage, int rowCount, String maxHeap, String memoryLimit)
            throws Exception {
        return fork(storage, rowCount, maxHeap, memoryLimit, false);
    }

    private static Map<String, String> fork(Storage storage, int rowCount, String maxHeap, String memoryLimit,
                                            boolean optimize) throws Exception {
        List<String> command = List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Xmx" + maxHeap, "-XX:+UseG1GC", "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                ScalingBenchmark.class.getName(), "--probe", storage.name(), String.valueOf(rowCount),
                memoryLimit == null ? "-" : memoryLimit, String.valueOf(optimize));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Map<String, String> result = new LinkedHashMap<>();
        List<String> other = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("FIELD ")) {
                    String[] parts = line.substring("FIELD ".length()).split("=", 2);
                    result.put(parts[0], parts[1]);
                }
                else if (!line.startsWith("WARNING")) {
                    other.add(line);
                }
            }
        }
        if (process.waitFor() != 0) {
            other.stream().limit(4).forEach(l -> System.out.println("    " + l));
            return Map.of();
        }
        return result;
    }

    private static void probe(Storage storage, int rowCount, String memoryLimit) {
        List<Car> cars = Cars.generate(rowCount, 42);
        Map<String, String> fields = new LinkedHashMap<>();
        try (Storage.Fixture fixture = storage.create(true, memoryLimit)) {
            IndexedCollection<Car> collection = fixture.collection;
            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
            collection.update(List.of(), cars, bulk);
            cars = null;
            if (fixture.persistence != null) {
                if (Storage.optimizeAfterLoad) {
                    fixture.persistence.optimize();
                }
                fixture.persistence.compact();
            }

            SplittableRandom random = new SplittableRandom(7);
            int rows = rowCount;
            // Scale the iteration counts down as the collection grows, to keep the run bounded.
            int heavyRuns = rowCount >= 2_000_000 ? 2 : 3;

            fields.put("pk lookup", micros(time(100, 300, () -> {
                try (ResultSet<Car> results = collection.retrieve(equal(Car.CAR_ID, random.nextInt(rows)))) {
                    return results.uniqueResult() == null ? 0 : 1;
                }
            })));
            fields.put("count only", micros(time(20, 60, () -> {
                try (ResultSet<Car> results = collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                    return results.size();
                }
            })));
            fields.put("narrow range", micros(time(10, 40, () -> {
                double lower = random.nextDouble() * 40_000;
                return count(collection.retrieve(between(Car.PRICE, lower, lower + 2.5)));
            })));
            fields.put("equal ~2%", micros(time(1, heavyRuns, () ->
                    count(collection.retrieve(equal(Car.MODEL, "model" + random.nextInt(50)))))));
            fields.put("iterate all", micros(time(1, heavyRuns, () ->
                    count(collection.retrieve(has(Car.CAR_ID))))));
        }
        fields.forEach((key, value) -> System.out.println("FIELD " + key + "=" + value));
    }

    private static int count(ResultSet<Car> results) {
        try (ResultSet<Car> closeable = results) {
            int count = 0;
            for (Car ignored : closeable) {
                count++;
            }
            return count;
        }
    }

    private static double time(int warmup, int measured, IntSupplier workload) {
        int checksum = 0;
        for (int i = 0; i < warmup; i++) {
            checksum += workload.getAsInt();
        }
        long start = System.nanoTime();
        for (int i = 0; i < measured; i++) {
            checksum += workload.getAsInt();
        }
        double micros = (System.nanoTime() - start) / 1000.0 / measured;
        if (checksum < 0) {
            throw new IllegalStateException("unreachable");
        }
        return micros;
    }

    private static String micros(double value) {
        if (value >= 1_000_000) return String.format("%,.2f s", value / 1_000_000);
        if (value >= 1_000) return String.format("%,.1f ms", value / 1000);
        if (value >= 10) return String.format("%.0f us", value);
        return String.format("%.2f us", value);
    }

    private static String label(Storage storage) {
        return switch (storage) {
            case ON_HEAP -> "CQEngine on-heap";
            case DUCKDB_BLOB_MEMORY -> "DuckDB mem, BLOB";
            case DUCKDB_COLUMNAR_MEMORY -> "DuckDB mem, columnar";
            case DUCKDB_BLOB_FILE -> "DuckDB file, BLOB";
            case DUCKDB_COLUMNAR_FILE -> "DuckDB file, columnar";
            case DUCKDB_COLUMNAR_FILE_ART -> "DuckDB file, col + ART";
            case CQENGINE_SQLITE_MEMORY -> "CQEngine SQLite mem";
            case CQENGINE_SQLITE_FILE -> "CQEngine SQLite file";
        };
    }
}
