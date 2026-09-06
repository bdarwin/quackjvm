package com.duckcq.bench;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the resident set size of the current process.
 *
 * <p>The JVM heap is the wrong yardstick for this plugin: DuckDB holds its data in native memory
 * that {@code Runtime.totalMemory()} never sees. RSS counts both, so it is the number which
 * actually answers "how much memory does this collection cost me".</p>
 */
public final class ProcessMemory {

    private ProcessMemory() {
    }

    /** @return resident set size of this process in bytes, or 0 if it cannot be determined. */
    public static long residentBytes() {
        long fromProcFs = readLinuxVmRss();
        return fromProcFs > 0 ? fromProcFs : readPsRss();
    }

    public static long heapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Runs a full GC a few times so that heap readings are not dominated by garbage. */
    public static void settle() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long readLinuxVmRss() {
        Path status = Path.of("/proc/self/status");
        if (!Files.exists(status)) {
            return 0;
        }
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]) * 1024;
                }
            }
        }
        catch (Exception e) {
            return 0;
        }
        return 0;
    }

    private static long readPsRss() {
        try {
            long pid = ProcessHandle.current().pid();
            Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            process.waitFor();
            return output == null ? 0 : Long.parseLong(output.trim()) * 1024;
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static String formatMb(long bytes) {
        return bytes <= 0 ? "-" : String.format("%,d MB", bytes / (1024 * 1024));
    }

    /** Where JMH-style forked measurements get their JVM arguments from. */
    public static List<String> jvmArgs(String maxHeap) {
        return List.of("-Xmx" + maxHeap, "--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC");
    }
}
