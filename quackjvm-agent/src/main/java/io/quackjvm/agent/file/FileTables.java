package io.quackjvm.agent.file;

import io.quackjvm.core.duckdb.ColumnDef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Making {@link FileTable}s, and looking after the cache files they keep in a folder: how much room
 * they take, and taking them away.
 *
 * <p>Only files this library made are counted or removed - their names start {@code qft-} - and
 * never one in use: open by a table in this JVM, or locked by another process that has it open.</p>
 */
public final class FileTables {

    /** What every cache file's name starts with. */
    static final String PREFIX = "qft-";

    /** Cache files open in this JVM. A cache file is never opened twice, nor removed while open. */
    static final Set<Path> OPEN = ConcurrentHashMap.newKeySet();

    private FileTables() {
    }

    /** A table over a file the application parses: say how with the builder, then {@code open()}. */
    public static FileTable.Builder builder(Path file, List<ColumnDef> columns, RowSource source, RowParser parser) {
        return new FileTable.Builder(file, columns, source, parser);
    }

    /** How many bytes this library's cache files take in the folder. */
    public static long size(Path folder) {
        long total = 0;
        for (Cache cache : caches(folder)) {
            total += cache.bytes();
        }
        return total;
    }

    /**
     * Removes the cache files in the folder, except those of the source files named - absolute
     * paths - and those in use.
     *
     * @return how many bytes were freed
     */
    public static long clear(Path folder, Set<String> inUse) {
        long freed = 0;
        for (Cache cache : caches(folder)) {
            if (inUse.contains(cache.source()) || isInUse(cache.database())) {
                continue;
            }
            freed += cache.delete();
        }
        return freed;
    }

    /**
     * Removes cache files, the longest unused first, until the folder holds no more than the limit -
     * never one in use, so it may hold more.
     *
     * @return how many bytes were freed
     */
    public static long trim(Path folder, long limit) {
        List<Cache> caches = caches(folder);
        long total = 0;
        for (Cache cache : caches) {
            total += cache.bytes();
        }
        caches.sort(Comparator.comparing(Cache::used));
        long freed = 0;
        for (Cache cache : caches) {
            if (total - freed <= limit) {
                break;
            }
            if (isInUse(cache.database())) {
                continue;
            }
            freed += cache.delete();
        }
        return freed;
    }

    /**
     * Whether a cache file is open: by a table in this JVM, or by another process, which holds a
     * lock on it while it has it open.
     */
    static boolean isInUse(Path database) {
        if (OPEN.contains(database.toAbsolutePath().normalize())) {
            return true;
        }
        if (!Files.exists(database)) {
            return false;
        }
        // Safe only because no table in this JVM has it open: closing this channel would let go of
        // every lock this process holds on the file, DuckDB's included.
        try (FileChannel channel = FileChannel.open(database, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock(0, Long.MAX_VALUE, false);
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        }
        catch (OverlappingFileLockException e) {
            return true;
        }
        catch (IOException e) {
            return true;   // cannot tell: keep it
        }
    }

    // ---------- the files of one cache ----------

    /** One cache: the database, its write-ahead log, its temp folder and the file saying when it was used. */
    record Cache(Path database, String source, FileTime used) {

        Path wal() {
            return database.resolveSibling(database.getFileName() + ".wal");
        }

        Path marker() {
            return marker(database);
        }

        Path temp() {
            return database.resolveSibling(database.getFileName() + ".tmp");
        }

        static Path marker(Path database) {
            return database.resolveSibling(database.getFileName() + ".used");
        }

        long bytes() {
            return sizeOf(database) + sizeOf(wal()) + sizeOf(marker()) + sizeOf(temp());
        }

        long delete() {
            long bytes = bytes();
            deleteQuietly(temp());
            deleteQuietly(wal());
            deleteQuietly(database);
            deleteQuietly(marker());
            return bytes;
        }
    }

    /** Records that a cache was used now, by which source file. */
    static void touch(Path database, Path source) {
        Path marker = Cache.marker(database);
        try {
            Files.writeString(marker, source.toAbsolutePath().normalize().toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to mark " + database + " as used", e);
        }
    }

    static List<Cache> caches(Path folder) {
        List<Cache> caches = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return caches;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder, PREFIX + "*.duckdb")) {
            for (Path database : entries) {
                Path marker = Cache.marker(database);
                String source = "";
                FileTime used = FileTime.fromMillis(0);
                try {
                    source = Files.readString(marker, StandardCharsets.UTF_8).trim();
                    used = Files.getLastModifiedTime(marker);
                }
                catch (IOException noMarker) {
                    try {
                        used = Files.getLastModifiedTime(database);
                    }
                    catch (IOException gone) {
                        continue;
                    }
                }
                caches.add(new Cache(database.toAbsolutePath().normalize(), source, used));
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + folder, e);
        }
        return caches;
    }

    private static long sizeOf(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    return files.filter(Files::isRegularFile).mapToLong(FileTables::sizeOrZero).sum();
                }
            }
            return Files.exists(path) ? Files.size(path) : 0;
        }
        catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        }
        catch (IOException e) {
            return 0;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path inside : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(inside);
                    }
                }
            }
            else {
                Files.deleteIfExists(path);
            }
        }
        catch (NoSuchFileException gone) {
            // already gone
        }
        catch (IOException e) {
            // left for the next time
        }
    }
}
