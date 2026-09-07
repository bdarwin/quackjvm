package io.quackjvm.cqengine.bench;

import io.quackjvm.cqengine.DuckDBFlags;
import io.quackjvm.cqengine.persistence.DuckDBBulkWriter;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.io.BufferedReader;
import java.io.File;
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
 * The whole comparison in one run: memory, storage and speed, for stock CQEngine and for each
 * DuckDB configuration.
 *
 * <p>Each configuration is measured in its own forked JVM, because resident memory is not
 * comparable within a single process once a previous configuration has touched the heap.</p>
 *
 * <pre>
 * java -cp &lt;classpath&gt; io.quackjvm.cqengine.bench.Comparison [rowCount] [maxHeap] [duckdbMemoryLimit]
 * </pre>
 */
public class Comparison {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--probe")) {
            // Every configuration indexes the same attributes, so the comparison is like for like.
            Storage.sqliteComparableIndexes = true;
            probe(Storage.valueOf(args[1]), Integer.parseInt(args[2]),
                    "-".equals(args[3]) ? null : args[3]);
            return;
        }
        int rowCount = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        String maxHeap = args.length > 1 ? args[1] : "4g";
        String memoryLimit = args.length > 2 ? args[2] : "256MB";

        System.out.printf("%,d objects of 8 fields, indexes on 3 attributes.%n", rowCount);
        System.out.printf("Each configuration in its own JVM: -Xmx%s, DuckDB memory_limit=%s.%n%n",
                maxHeap, memoryLimit);

        List<Map<String, String>> results = new ArrayList<>();
        for (Storage storage : Storage.values()) {
            System.out.print("  measuring " + storage + " ... ");
            System.out.flush();
            Map<String, String> result = fork(storage, rowCount, maxHeap, memoryLimit);
            results.add(result);
            System.out.println(result.isEmpty() ? "FAILED" : "done");
        }

        List<String> columns = List.of("storage", "process RSS", "JVM heap", "on disk", "load",
                "pk lookup", "narrow range", "count only", "equal ~2%", "iterate all",
                "add() one at a time", "bulkWriter");

        System.out.println();
        System.out.println("TABLE 1 - IN MEMORY: stock CQEngine vs DuckDB");
        printTable(select(results, "CQEngine on-heap", "CQEngine SQLite memory",
                "DuckDB memory, BLOB", "DuckDB memory, columnar"), columns);

        System.out.println();
        System.out.println("TABLE 2 - ON DISK: CQEngine's SQLite persistence vs DuckDB file persistence");
        // On-heap CQEngine leads both tables: it is the baseline being replaced, and the one
        // number every other row should be read against.
        printTable(select(results, "CQEngine on-heap", "CQEngine SQLite file",
                "DuckDB file, BLOB", "DuckDB file, columnar", "DuckDB file, col + ART"), columns);

        System.out.println();
        System.out.println("  process RSS          resident memory of the whole process, after loading and a full GC");
        System.out.println("  JVM heap             live heap after a full GC - what the garbage collector walks");
        System.out.println("  on disk              size the backing database reports for itself");
        System.out.println("  load                 addAll() of every object, with the BULK_IMPORT flag");
        System.out.println("  pk lookup            retrieve(equal(CAR_ID, x)) returning one object");
        System.out.println("  narrow range         between(PRICE, x, x+2.5) - a few dozen objects");
        System.out.println("  count only           size() of an indexed equality, nothing materialised");
        System.out.println("  equal ~2%            equal(MODEL, x), materialising every match");
        System.out.println("  iterate all          iterating the entire collection");
        System.out.println("  add() one at a time  a single add() per request");
        System.out.println("  bulkWriter           streaming through DuckDBBulkWriter");
        System.out.println();
        System.out.println("  All configurations index the same 3 attributes. CQEngine's SQLite indexes");
        System.out.println("  cannot store an enum, so the enum attribute is left unindexed everywhere.");
        System.out.println("  CQEngine on-heap appears in both tables as the baseline being replaced:");
        System.out.println("  it is stock CQEngine, new ConcurrentIndexedCollection<>(), with objects and");
        System.out.println("  indexes held as Java objects in the JVM heap. The SQLite rows are CQEngine's");
        System.out.println("  own OffHeapPersistence and DiskPersistence.");
    }

    private static List<Map<String, String>> select(List<Map<String, String>> results, String... labels) {
        List<Map<String, String>> selected = new ArrayList<>();
        for (String label : labels) {
            results.stream()
                    .filter(row -> label.equals(row.get("storage")))
                    .findFirst()
                    .ifPresent(selected::add);
        }
        return selected;
    }

    // ---------- Table rendering ----------

    private static void printTable(List<Map<String, String>> rows, List<String> columns) {
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
            for (Map<String, String> row : rows) {
                widths[i] = Math.max(widths[i], row.getOrDefault(columns.get(i), "-").length());
            }
        }
        StringBuilder header = new StringBuilder();
        StringBuilder rule = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            header.append(pad(columns.get(i), widths[i], i == 0)).append("  ");
            rule.append("-".repeat(widths[i])).append("  ");
        }
        System.out.println(header);
        System.out.println(rule);
        for (Map<String, String> row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                line.append(pad(row.getOrDefault(columns.get(i), "-"), widths[i], i == 0)).append("  ");
            }
            System.out.println(line);
        }
    }

    private static String pad(String value, int width, boolean leftAlign) {
        return leftAlign ? String.format("%-" + width + "s", value) : String.format("%" + width + "s", value);
    }

    // ---------- Forking ----------

    private static Map<String, String> fork(Storage storage, int rowCount, String maxHeap, String memoryLimit)
            throws Exception {
        List<String> command = List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Xmx" + maxHeap, "-XX:+UseG1GC", "--enable-native-access=ALL-UNNAMED",
                // CQEngine's own Kryo serializer reflects into java.util internals, so its SQLite
                // persistence cannot serialize anything on Java 17+ without this.
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                Comparison.class.getName(), "--probe", storage.name(), String.valueOf(rowCount),
                memoryLimit == null ? "-" : memoryLimit);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Map<String, String> result = new LinkedHashMap<>();
        List<String> otherOutput = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("FIELD ")) {
                    String[] parts = line.substring("FIELD ".length()).split("=", 2);
                    result.put(parts[0], parts[1]);
                }
                else if (!line.startsWith("WARNING")) {
                    otherOutput.add(line);
                }
            }
        }
        if (process.waitFor() != 0) {
            otherOutput.forEach(l -> System.out.println("    " + l));
            return Map.of("storage", storage.name() + " (failed)");
        }
        return result;
    }

    // ---------- One configuration, in its own JVM ----------

    private static void probe(Storage storage, int rowCount, String memoryLimit) {
        List<Car> cars = Cars.generate(rowCount, 42);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("storage", label(storage));

        long start = System.nanoTime();
        try (Storage.Fixture fixture = storage.create(true, memoryLimit)) {
            IndexedCollection<Car> collection = fixture.collection;
            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
            collection.update(List.of(), cars, bulk);
            double loadSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            fields.put("load", String.format("%.1f s", loadSeconds));
            fields.put("addAll batch", micros(loadSeconds * 1_000_000 / rowCount));

            // Measure memory before anything else touches the collection.
            cars = null;
            if (fixture.persistence != null) {
                fixture.persistence.compact();
            }
            ProcessMemory.settle();
            long heap = ProcessMemory.heapBytes();
            fields.put("process RSS", ProcessMemory.formatMb(ProcessMemory.residentBytes()));
            fields.put("JVM heap", ProcessMemory.formatMb(heap));
            fields.put("on disk", storage.isOnDisk() ? ProcessMemory.formatMb(fixture.storageBytes()) : "-");

            SplittableRandom random = new SplittableRandom(7);
            int rows = rowCount;

            fields.put("pk lookup", micros(time(200, 500, () -> {
                try (ResultSet<Car> results = collection.retrieve(equal(Car.CAR_ID, random.nextInt(rows)))) {
                    return results.uniqueResult() == null ? 0 : 1;
                }
            })));
            fields.put("narrow range", micros(time(20, 100, () -> {
                double lower = random.nextDouble() * 40_000;
                return count(collection.retrieve(between(Car.PRICE, lower, lower + 2.5)));
            })));
            fields.put("count only", micros(time(20, 100, () -> {
                try (ResultSet<Car> results = collection.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                    return results.size();
                }
            })));
            fields.put("equal ~2%", micros(time(2, 5, () ->
                    count(collection.retrieve(equal(Car.MODEL, "model" + random.nextInt(50)))))));
            fields.put("iterate all", micros(time(1, 3, () ->
                    count(collection.retrieve(has(Car.CAR_ID))))));

            // Writes last: they change the collection.
            Car template = Cars.generate(1, 99).get(0);
            int[] nextId = {rowCount * 2};
            fields.put("add() one at a time", micros(time(50, 200, () -> {
                Car car = withId(template, nextId[0]++);
                return collection.add(car) ? 1 : 0;
            })));

            if (fixture.persistence != null) {
                List<Car> streamed = new ArrayList<>(100_000);
                for (int i = 0; i < 100_000; i++) {
                    streamed.add(withId(template, rowCount * 4 + i));
                }
                long writerStart = System.nanoTime();
                try (DuckDBBulkWriter<Car> writer = fixture.persistence.bulkWriter()) {
                    for (Car car : streamed) {
                        writer.add(car);
                    }
                }
                fields.put("bulkWriter", micros((System.nanoTime() - writerStart) / 1000.0 / streamed.size()));
            }
            else {
                fields.put("bulkWriter", "-");
            }
        }
        fields.forEach((key, value) -> System.out.println("FIELD " + key + "=" + value));
    }

    private static Car withId(Car template, int id) {
        return new Car(id, template.manufacturer(), template.model(), template.color(), template.doors(),
                template.price(), template.description(), template.registered());
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

    /** Runs the workload, discarding the warmup, and returns the mean microseconds per operation. */
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
        if (value >= 1_000_000) return String.format("%,.1f s", value / 1_000_000);
        if (value >= 1_000) return String.format("%,.1f ms", value / 1000);
        if (value >= 10) return String.format("%.0f us", value);
        return String.format("%.2f us", value);
    }

    private static String label(Storage storage) {
        return switch (storage) {
            case ON_HEAP -> "CQEngine on-heap";   // stock CQEngine, objects in the JVM heap
            case DUCKDB_BLOB_MEMORY -> "DuckDB memory, BLOB";
            case DUCKDB_COLUMNAR_MEMORY -> "DuckDB memory, columnar";
            case DUCKDB_BLOB_FILE -> "DuckDB file, BLOB";
            case DUCKDB_COLUMNAR_FILE -> "DuckDB file, columnar";
            case DUCKDB_COLUMNAR_FILE_ART -> "DuckDB file, col + ART";
            case CQENGINE_SQLITE_MEMORY -> "CQEngine SQLite memory";
            case CQENGINE_SQLITE_FILE -> "CQEngine SQLite file";
        };
    }

    private static long fileSize(File file) {
        if (file == null) {
            return 0;
        }
        File writeAheadLog = new File(file.getAbsolutePath() + ".wal");
        return file.length() + (writeAheadLog.exists() ? writeAheadLog.length() : 0);
    }
}
