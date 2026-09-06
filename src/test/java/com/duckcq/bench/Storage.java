package com.duckcq.bench;

import com.duckcq.index.DuckDBIndex;
import com.duckcq.layout.ColumnarLayout;
import com.duckcq.persistence.DuckDBPersistence;
import com.duckcq.testutil.Car;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;

import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.index.offheap.OffHeapIndex;
import com.googlecode.cqengine.index.sqlite.SQLitePersistence;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import com.googlecode.cqengine.persistence.offheap.OffHeapPersistence;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * The storage configurations the benchmarks compare, so that every benchmark measures the same
 * set of collections built the same way.
 */
public enum Storage {

    /** Stock CQEngine: objects and indexes on the Java heap. */
    ON_HEAP(false, false, false),
    /** Stock CQEngine's off-heap persistence: an in-memory SQLite database. */
    CQENGINE_SQLITE_MEMORY(Backend.SQLITE_MEMORY),
    /** Stock CQEngine's disk persistence: a SQLite database file. */
    CQENGINE_SQLITE_FILE(Backend.SQLITE_FILE),
    /** DuckDB in memory, objects serialized into one BLOB each. */
    DUCKDB_BLOB_MEMORY(true, false, false),
    /** DuckDB in memory, objects shredded into typed columns. */
    DUCKDB_COLUMNAR_MEMORY(true, true, false),
    /** DuckDB in a file, objects serialized into one BLOB each. */
    DUCKDB_BLOB_FILE(true, false, true),
    /** DuckDB in a file, objects shredded into typed columns. */
    DUCKDB_COLUMNAR_FILE(true, true, true),
    /** As above, plus DuckDB ART indexes on the index tables. */
    DUCKDB_COLUMNAR_FILE_ART(true, true, true, true);

    /** Set by benchmarks which want optimize() called after loading. */
    public static boolean optimizeAfterLoad;

    /**
     * Restricts every configuration to the attribute types CQEngine's own SQLite indexes accept,
     * so that a comparison against them is like for like. CQEngine cannot index an enum attribute
     * at all ("Type class ... not supported"), while this plugin stores enums as their ordinal, so
     * the colour index is dropped from all configurations when this is set.
     */
    public static boolean sqliteComparableIndexes;

    private enum Backend {HEAP, SQLITE_MEMORY, SQLITE_FILE, DUCKDB}

    private final Backend backend;
    private final boolean duckDB;
    private final boolean columnar;
    private final boolean onDisk;
    private final boolean artIndexes;

    Storage(Backend backend) {
        this.backend = backend;
        this.duckDB = false;
        this.columnar = false;
        this.onDisk = backend == Backend.SQLITE_FILE;
        this.artIndexes = false;
    }

    Storage(boolean duckDB, boolean columnar, boolean onDisk) {
        this(duckDB, columnar, onDisk, false);
    }

    Storage(boolean duckDB, boolean columnar, boolean onDisk, boolean artIndexes) {
        this.backend = duckDB ? Backend.DUCKDB : Backend.HEAP;
        this.duckDB = duckDB;
        this.columnar = columnar;
        this.onDisk = onDisk;
        this.artIndexes = artIndexes;
    }

    public boolean isDuckDB() {
        return duckDB;
    }

    public boolean isOnDisk() {
        return onDisk;
    }

    /** A collection plus the resources which have to be released when the benchmark is done. */
    public static final class Fixture implements AutoCloseable {
        public final IndexedCollection<Car> collection;
        /** Non-null only for the DuckDB configurations. */
        public final DuckDBPersistence<Car, Integer> persistence;
        /** CQEngine's own SQLite persistence, for the stock disk and off-heap configurations. */
        public final SQLitePersistence<Car, Integer> sqlitePersistence;
        public final File file;

        Fixture(IndexedCollection<Car> collection, DuckDBPersistence<Car, Integer> persistence,
                SQLitePersistence<Car, Integer> sqlitePersistence, File file) {
            this.collection = collection;
            this.persistence = persistence;
            this.sqlitePersistence = sqlitePersistence;
            this.file = file;
        }

        /** Bytes the backing database reports, or 0 for an on-heap collection. */
        public long storageBytes() {
            if (persistence != null) {
                return persistence.getBytesUsed();
            }
            return sqlitePersistence == null ? 0 : sqlitePersistence.getBytesUsed();
        }

        @Override
        public void close() {
            if (persistence != null) {
                persistence.close();
            }
            if (sqlitePersistence instanceof Closeable closeable) {
                try {
                    closeable.close();
                }
                catch (IOException ignored) {
                    // Nothing useful to do while tearing a benchmark down.
                }
            }
            if (file != null) {
                file.delete();
                new File(file.getAbsolutePath() + ".wal").delete();
            }
        }
    }

    public Fixture create() {
        return create(true, null);
    }

    public Fixture create(boolean withIndexes) {
        return create(withIndexes, null);
    }

    /**
     * @param memoryLimit DuckDB's {@code memory_limit} setting, e.g. "256MB", or null for its
     *                    default (80% of system RAM). A file-backed database respects this limit
     *                    by evicting buffers; an in-memory one spills to temporary files.
     */
    public Fixture create(boolean withIndexes, String memoryLimit) {
        if (backend == Backend.SQLITE_MEMORY || backend == Backend.SQLITE_FILE) {
            return createStockSQLite(withIndexes);
        }
        if (!duckDB) {
            IndexedCollection<Car> collection = new ConcurrentIndexedCollection<>();
            if (withIndexes) {
                // DuckDB persistence indexes the primary key inherently, so the on-heap baseline
                // gets an equivalent index to keep the comparison fair.
                collection.addIndex(HashIndex.onAttribute(Car.CAR_ID));
                collection.addIndex(HashIndex.onAttribute(Car.MANUFACTURER));
                collection.addIndex(NavigableIndex.onAttribute(Car.PRICE));
                if (!sqliteComparableIndexes) {
                    collection.addIndex(HashIndex.onAttribute(Car.COLOR));
                }
                collection.addIndex(HashIndex.onAttribute(Car.MODEL));
            }
            return new Fixture(collection, null, null, null);
        }

        File file = onDisk ? createTempFile() : null;
        DuckDBPersistence.Builder<Car, Integer> builder = DuckDBPersistence.builder(Car.CAR_ID);
        if (file != null) {
            builder.file(file);
        }
        else {
            builder.inMemory();
        }
        if (columnar) {
            builder.columnarLayout(ColumnarLayout.ofRecord(Car.class));
        }
        if (memoryLimit != null) {
            builder.property("memory_limit", memoryLimit);
        }
        DuckDBPersistence<Car, Integer> persistence = builder.build();
        IndexedCollection<Car> collection = new ConcurrentIndexedCollection<>(persistence);
        if (withIndexes) {
            collection.addIndex(index(Car.MANUFACTURER));
            collection.addIndex(index(Car.PRICE));
            if (!sqliteComparableIndexes) {
                collection.addIndex(index(Car.COLOR));
            }
            collection.addIndex(index(Car.MODEL));
        }
        return new Fixture(collection, persistence, null, file);
    }

    /** Stock CQEngine backed by SQLite, either in memory (off-heap) or in a file (disk). */
    private Fixture createStockSQLite(boolean withIndexes) {
        File file = backend == Backend.SQLITE_FILE ? createTempFile() : null;
        SQLitePersistence<Car, Integer> persistence = file != null
                ? DiskPersistence.onPrimaryKeyInFile(Car.CAR_ID, file)
                : OffHeapPersistence.onPrimaryKey(Car.CAR_ID);
        IndexedCollection<Car> collection = new ConcurrentIndexedCollection<>(persistence);
        if (withIndexes) {
            // CQEngine's SQLite indexes cannot store an enum, so there is no colour index here.
            collection.addIndex(stockIndex(Car.MANUFACTURER, file != null));
            collection.addIndex(stockIndex(Car.PRICE, file != null));
            collection.addIndex(stockIndex(Car.MODEL, file != null));
        }
        return new Fixture(collection, null, persistence, file);
    }

    @SuppressWarnings("unchecked")
    private static <A extends Comparable<A>> com.googlecode.cqengine.index.Index<Car> stockIndex(
            Attribute<Car, A> attribute, boolean onDisk) {
        return (com.googlecode.cqengine.index.Index<Car>) (onDisk
                ? DiskIndex.onAttribute(attribute)
                : OffHeapIndex.onAttribute(attribute));
    }

    private <A extends Comparable<A>> DuckDBIndex<A, Car, ? extends Comparable<?>> index(
            Attribute<Car, A> attribute) {
        return artIndexes
                ? DuckDBIndex.onAttributeWithArtIndex(attribute)
                : DuckDBIndex.onAttribute(attribute);
    }

    private static File createTempFile() {
        try {
            File file = File.createTempFile("cqbench_", ".duckdb");
            if (!file.delete()) {
                throw new IllegalStateException("could not clear " + file);
            }
            file.deleteOnExit();
            return file;
        }
        catch (IOException e) {
            throw new IllegalStateException("could not create a temp file", e);
        }
    }
}
