package com.duckcq.bench;

import com.duckcq.DuckDBFlags;
import com.duckcq.testutil.Car;
import com.duckcq.testutil.Cars;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures what a collection actually costs the operating system, not just the Java heap.
 *
 * <p>DuckDB keeps its data in native memory, which {@code Runtime.totalMemory()} cannot see, so
 * this benchmark reports the resident set size of the whole process. Each configuration runs in a
 * freshly forked JVM with identical flags, because RSS is not comparable within one process once
 * a previous configuration has touched the heap.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp &lt;classpath&gt; com.duckcq.bench.MemoryBenchmark [rowCount] [maxHeap]
 * </pre>
 */
public class MemoryBenchmark {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--probe")) {
            probe(Storage.valueOf(args[1]), Integer.parseInt(args[2]),
                    args.length > 3 && !"-".equals(args[3]) ? args[3] : null);
            return;
        }
        int rowCount = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        String maxHeap = args.length > 1 ? args[1] : "4g";
        String memoryLimit = args.length > 2 ? args[2] : null;

        System.out.printf("Measuring %,d objects, each JVM forked with -Xmx%s%s%n%n", rowCount, maxHeap,
                memoryLimit == null ? "" : ", DuckDB memory_limit=" + memoryLimit);
        List<String> rows = new ArrayList<>();
        for (Storage storage : Storage.values()) {
            rows.add(fork(storage, rowCount, maxHeap, memoryLimit));
        }

        System.out.printf("%n%-24s %12s %12s %12s %12s %10s%n",
                "storage", "process RSS", "JVM heap", "off-heap", "DuckDB file", "load");
        System.out.println("-".repeat(90));
        for (String row : rows) {
            System.out.println(row);
        }
        System.out.println();
        System.out.println("process RSS = resident memory of the whole JVM process after loading and a full GC");
        System.out.println("off-heap    = process RSS minus JVM heap: where DuckDB actually keeps the data");
        System.out.println("DuckDB file = size of the database file, for the file-backed configurations");
    }

    /** Runs one configuration in a forked JVM and returns its formatted result row. */
    private static String fork(Storage storage, int rowCount, String maxHeap, String memoryLimit) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Xmx" + maxHeap,
                "-XX:+UseG1GC",
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                MemoryBenchmark.class.getName(), "--probe", storage.name(), String.valueOf(rowCount),
                memoryLimit == null ? "-" : memoryLimit));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RESULT ")) {
                    result = line.substring("RESULT ".length());
                }
                else if (!line.startsWith("WARNING")) {
                    System.out.println("  [" + storage + "] " + line);
                }
            }
        }
        int exitCode = process.waitFor();
        if (result == null) {
            return String.format("%-24s  failed (exit code %d - out of memory?)", storage, exitCode);
        }
        return result;
    }

    /** One measurement, in its own JVM. */
    private static void probe(Storage storage, int rowCount, String memoryLimit) {
        List<Car> cars = Cars.generate(rowCount, 42);

        long start = System.nanoTime();
        try (Storage.Fixture fixture = storage.create(true, memoryLimit)) {
            QueryOptions bulk = new QueryOptions();
            FlagsEnabled.forQueryOptions(bulk).add(DuckDBFlags.BULK_IMPORT);
            fixture.collection.update(List.of(), cars, bulk);
            double loadSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

            // Release the source list: we want to measure the collection, not the input data.
            cars = null;
            // Flush everything the bulk load buffered, so the reading is steady state rather than
            // whatever the loader happened to still be holding.
            if (fixture.persistence != null) {
                fixture.persistence.compact();
            }
            ProcessMemory.settle();

            // Absolute resident memory of the process: what this collection actually costs the
            // machine, including the JVM itself.
            long rss = ProcessMemory.residentBytes();
            long heap = ProcessMemory.heapBytes();
            long file = storage.isOnDisk() ? fileSize(fixture.file) : 0;
            long offHeap = Math.max(rss - heap, 0);

            System.out.printf("RESULT %-24s %12s %12s %12s %12s %8.1f s%n",
                    storage,
                    ProcessMemory.formatMb(rss),
                    ProcessMemory.formatMb(heap),
                    ProcessMemory.formatMb(offHeap),
                    file == 0 ? "-" : ProcessMemory.formatMb(file),
                    loadSeconds);
        }
    }

    private static long fileSize(File file) {
        if (file == null) {
            return 0;
        }
        File writeAheadLog = new File(file.getAbsolutePath() + ".wal");
        return file.length() + (writeAheadLog.exists() ? writeAheadLog.length() : 0);
    }
}
