package io.quackjvm.dashboard;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

/**
 * Writes what the dashboard samples to files, so that a run can be analysed afterwards - with
 * DuckDB, since the files are JSON Lines, which it reads directly:
 *
 * <pre>
 * SELECT ts, writesPerSecond, lockWaitShare, cpu FROM 'quackjvm-metrics/metrics-*.jsonl' ORDER BY ts;
 * </pre>
 *
 * <p>Five kinds of file, one of each per day (UTC), each line one record with a {@code ts}:</p>
 * <ul>
 *   <li>{@code metrics-DATE.jsonl} - every second, the headline numbers the page shows</li>
 *   <li>{@code statements-DATE.jsonl} - every ten seconds, one line per statement shape that ran</li>
 *   <li>{@code collections-DATE.jsonl} - every ten seconds, one line per collection that was used</li>
 *   <li>{@code findings-DATE.jsonl} - every ten seconds, one line per cause the diagnosis found</li>
 *   <li>{@code processes-DATE.jsonl} - when other programs hold a quarter of the machine or more,
 *       at most every five seconds, one line per program using a notable share of it</li>
 * </ul>
 *
 * <p>Files older than the retention period are deleted when a day's files are started, so the
 * directory does not grow without bound. A heavy load - many collections and statement shapes -
 * writes some tens of megabytes a day. Lines are flushed as they are written, so the files can be
 * queried while the application is still running.</p>
 *
 * <p>Not thread-safe: the dashboard's sampling thread is its only caller.</p>
 */
final class Recorder implements AutoCloseable {

    enum Kind {
        METRICS("metrics"), STATEMENTS("statements"), COLLECTIONS("collections"), FINDINGS("findings"),
        PROCESSES("processes");

        final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    private final Path directory;
    private final Duration retention;
    private final Map<Kind, BufferedWriter> writers = new EnumMap<>(Kind.class);
    private LocalDate day;

    Recorder(Path directory, Duration retention) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.retention = retention;
        Files.createDirectories(this.directory);
    }

    Path directory() {
        return directory;
    }

    /** Writes one line of the given kind, starting the day's files first if the date has moved on. */
    void write(Kind kind, Instant at, String jsonObject) throws IOException {
        LocalDate today = LocalDate.ofInstant(at, ZoneOffset.UTC);
        if (!today.equals(day)) {
            startDay(today);
        }
        BufferedWriter writer = writers.get(kind);
        if (writer == null) {
            writer = Files.newBufferedWriter(directory.resolve(kind.prefix + "-" + today + ".jsonl"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writers.put(kind, writer);
        }
        writer.write(jsonObject);
        writer.write('\n');
    }

    /** Pushes buffered lines to the files; called once per sample. */
    void flush() throws IOException {
        for (BufferedWriter writer : writers.values()) {
            writer.flush();
        }
    }

    private void startDay(LocalDate today) throws IOException {
        closeWriters();
        day = today;
        deleteExpired(today);
    }

    /** Deletes this recorder's own files older than the retention period, and nothing else. */
    private void deleteExpired(LocalDate today) throws IOException {
        LocalDate oldestKept = today.minusDays(Math.max(0, retention.toDays() - 1));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.jsonl")) {
            for (Path file : files) {
                LocalDate date = dateOf(file.getFileName().toString());
                if (date != null && date.isBefore(oldestKept)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** The date in one of our file names, or null for any file that is not ours. */
    static LocalDate dateOf(String fileName) {
        for (Kind kind : Kind.values()) {
            String prefix = kind.prefix + "-";
            if (fileName.startsWith(prefix) && fileName.endsWith(".jsonl")) {
                try {
                    return LocalDate.parse(fileName.substring(prefix.length(), fileName.length() - ".jsonl".length()));
                }
                catch (java.time.format.DateTimeParseException notOurs) {
                    return null;
                }
            }
        }
        return null;
    }

    private void closeWriters() throws IOException {
        IOException first = null;
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.close();
            }
            catch (IOException e) {
                first = first == null ? e : first;
            }
        }
        writers.clear();
        if (first != null) {
            throw first;
        }
    }

    @Override
    public void close() throws IOException {
        closeWriters();
    }
}
