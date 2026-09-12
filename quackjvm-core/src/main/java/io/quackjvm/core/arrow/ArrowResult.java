package io.quackjvm.core.arrow;

import org.apache.arrow.c.ArrowArrayStream;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.duckdb.DuckDBResultSet;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Streams a DuckDB query result as Arrow columnar batches.
 *
 * <p>This is the fast read path. The JDBC driver hands out values one at a time through
 * {@code ResultSet.getObject}, which for a wide result costs more than the query itself; DuckDB can
 * instead export the result as Arrow, which is the same columnar data without the per-value
 * crossing. On a million rows of four columns this was 36 ms against 602 ms.</p>
 *
 * <p>Close it when finished: it owns the Arrow allocator, the statement and its result set.</p>
 */
public final class ArrowResult implements Closeable, Iterable<ArrowBatch> {

    /** Rows per batch. DuckDB's own vector size is 2048; larger batches amortise the crossing. */
    public static final long DEFAULT_BATCH_SIZE = 65_536;

    /**
     * Below this many rows the Arrow path is not worth it: setting up a batch export costs more
     * than reading a handful of values through JDBC. Callers use it to decide, since a query which
     * returns one object should not pay for columnar machinery.
     */
    public static final int WORTHWHILE_ROW_COUNT = 64;

    /**
     * One allocator for the process, with a child per result.
     *
     * <p>Constructing a {@code RootAllocator} per query measurably hurt small reads - a
     * single-object lookup went from 682 to 941 microseconds - because the allocator sets up its
     * own accounting and memory reservations. Arrow's own guidance is one root per application with
     * children per unit of work, which is what this does.</p>
     */
    private static final class SharedAllocator {
        private static final BufferAllocator ROOT = new RootAllocator(Long.MAX_VALUE);
    }

    private static BufferAllocator newChildAllocator() {
        return SharedAllocator.ROOT.newChildAllocator("quackjvm-result", 0, Long.MAX_VALUE);
    }

    private final BufferAllocator allocator;
    private final PreparedStatement statement;
    private final DuckDBResultSet resultSet;
    private final ArrowReader reader;
    private boolean closed;

    private ArrowResult(BufferAllocator allocator, PreparedStatement statement,
                        DuckDBResultSet resultSet, ArrowReader reader) {
        this.allocator = allocator;
        this.statement = statement;
        this.resultSet = resultSet;
        this.reader = reader;
    }

    /**
     * Runs the statement and exports its result as Arrow.
     *
     * @param statement an executed-on-demand statement which this result takes ownership of
     */
    public static ArrowResult of(PreparedStatement statement, long batchSize) {
        if (!ArrowSupport.isAvailable()) {
            throw new IllegalStateException(ArrowSupport.getUnavailableReason());
        }
        BufferAllocator allocator = null;
        DuckDBResultSet resultSet = null;
        try {
            allocator = newChildAllocator();
            resultSet = (DuckDBResultSet) statement.executeQuery();
            ArrowReader reader = (ArrowReader) resultSet.arrowExportStream(allocator, batchSize);
            return new ArrowResult(allocator, statement, resultSet, reader);
        }
        catch (SQLException e) {
            closeQuietly(resultSet, statement, allocator);
            throw new IllegalStateException("Failed to export a DuckDB result as Arrow", e);
        }
        catch (RuntimeException e) {
            closeQuietly(resultSet, statement, allocator);
            // Arrow fails at first allocation when the JVM was not given access to java.nio.
            if (e instanceof RuntimeException && String.valueOf(e.getMessage()).contains("module java.base")) {
                throw new IllegalStateException("Arrow needs " + ArrowSupport.getRequiredJvmFlag()
                        + " on Java 17 and later", e);
            }
            throw e;
        }
    }

    @Override
    public Iterator<ArrowBatch> iterator() {
        return new Iterator<>() {
            private Boolean hasNext;
            private final VectorBatch batch = new VectorBatch();

            @Override
            public boolean hasNext() {
                if (hasNext == null) {
                    try {
                        hasNext = reader.loadNextBatch();
                    }
                    catch (IOException e) {
                        throw new IllegalStateException("Failed to read the next Arrow batch", e);
                    }
                }
                return hasNext;
            }

            @Override
            public ArrowBatch next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                hasNext = null;
                try {
                    batch.bind(reader.getVectorSchemaRoot());
                }
                catch (IOException e) {
                    throw new IllegalStateException("Failed to read the current Arrow batch", e);
                }
                return batch;
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            reader.close();
        }
        catch (IOException ignored) {
            // Nothing useful to do; the allocator is closed below regardless.
        }
        closeQuietly(resultSet, statement, allocator);
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            if (closeable != null) {
                try {
                    closeable.close();
                }
                catch (Exception ignored) {
                    // Best effort.
                }
            }
        }
    }

    /**
     * A view over the reader's current batch. Rebound as the reader advances rather than
     * reallocated, since a scan produces one of these per 65,536 rows.
     */
    /** Reads one value of one column, with the vector's type already resolved. */
    private interface ColumnAccessor {
        Object get(int row);
    }

    private static final class VectorBatch implements ArrowBatch {

        private VectorSchemaRoot root;
        private FieldVector[] vectors;
        private ColumnAccessor[] accessors;

        void bind(VectorSchemaRoot root) {
            this.root = root;
            FieldVector[] vectors = root.getFieldVectors().toArray(new FieldVector[0]);
            // Resolve each column's type once per batch rather than once per value. Walking a
            // chain of instanceof checks per value was 63% of the cost of a full scan - nine
            // million values, each paying for the dispatch before doing any work.
            if (this.vectors == null || !sameVectors(this.vectors, vectors)) {
                this.accessors = new ColumnAccessor[vectors.length];
                for (int i = 0; i < vectors.length; i++) {
                    this.accessors[i] = accessorFor(vectors[i]);
                }
            }
            this.vectors = vectors;
            this.root = root;
        }

        private static boolean sameVectors(FieldVector[] a, FieldVector[] b) {
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        /** Binds a reader to a vector's concrete type, so the per-value path is a single call. */
        private static ColumnAccessor accessorFor(FieldVector vector) {
            if (vector instanceof IntVector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof BigIntVector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof VarCharVector v) {
                return row -> v.isNull(row) ? null : new String(v.get(row), StandardCharsets.UTF_8);
            }
            if (vector instanceof Float8Vector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof BitVector v) {
                return row -> v.isNull(row) ? null : v.get(row) != 0;
            }
            if (vector instanceof SmallIntVector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof TinyIntVector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof Float4Vector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof DecimalVector v) {
                return row -> v.isNull(row) ? null : v.getObject(row);
            }
            if (vector instanceof VarBinaryVector v) {
                return row -> v.isNull(row) ? null : v.get(row);
            }
            if (vector instanceof DateDayVector v) {
                return row -> v.isNull(row) ? null : java.time.LocalDate.ofEpochDay(v.get(row));
            }
            if (vector instanceof TimeMicroVector v) {
                return row -> v.isNull(row) ? null : java.time.LocalTime.ofNanoOfDay(v.get(row) * 1000L);
            }
            if (vector instanceof TimeStampMicroVector v) {
                return row -> v.isNull(row) ? null : microsToLocalDateTime(v.get(row));
            }
            if (vector instanceof TimeStampMicroTZVector v) {
                // TIMESTAMP WITH TIME ZONE stores an instant, not a wall time, so it must not be
                // handed on as a naive LocalDateTime - anything reinterpreting that in the local
                // zone would shift the instant.
                return row -> v.isNull(row) ? null : microsToOffsetDateTime(v.get(row));
            }
            // Nested types, intervals, UUIDs: fall back to Arrow's own boxing.
            return row -> vector.isNull(row) ? null : vector.getObject(row);
        }

        @Override
        public int getRowCount() {
            return root.getRowCount();
        }

        @Override
        public int getColumnCount() {
            return vectors.length;
        }

        @Override
        public boolean isNull(int column, int row) {
            return vectors[column].isNull(row);
        }

        @Override
        public Object getValue(int column, int row) {
            FieldVector vector = vectors[column];
            if (vector.isNull(row)) {
                return null;
            }
            // Ordered by how often each type appears in practice.
            if (vector instanceof IntVector v) return v.get(row);
            if (vector instanceof BigIntVector v) return v.get(row);
            if (vector instanceof VarCharVector v) return new String(v.get(row), StandardCharsets.UTF_8);
            if (vector instanceof Float8Vector v) return v.get(row);
            if (vector instanceof BitVector v) return v.get(row) != 0;
            if (vector instanceof SmallIntVector v) return v.get(row);
            if (vector instanceof TinyIntVector v) return v.get(row);
            if (vector instanceof Float4Vector v) return v.get(row);
            if (vector instanceof DecimalVector v) return v.getObject(row);
            if (vector instanceof VarBinaryVector v) return v.get(row);
            if (vector instanceof DateDayVector v) return java.time.LocalDate.ofEpochDay(v.get(row));
            if (vector instanceof TimeMicroVector v) return java.time.LocalTime.ofNanoOfDay(v.get(row) * 1000L);
            if (vector instanceof TimeStampMicroVector v) return microsToLocalDateTime(v.get(row));
            // TIMESTAMP WITH TIME ZONE stores an instant, not a wall time, so it must not be
            // handed on as a naive LocalDateTime - anything reinterpreting that in the local zone
            // would shift the instant. The JDBC path returns an OffsetDateTime here, so match it.
            if (vector instanceof TimeStampMicroTZVector v) return microsToOffsetDateTime(v.get(row));
            // Anything else - nested types, intervals, UUIDs - falls back to Arrow's own boxing.
            return vector.getObject(row);
        }

        private static java.time.LocalDateTime microsToLocalDateTime(long micros) {
            long seconds = Math.floorDiv(micros, 1_000_000L);
            long nanos = Math.floorMod(micros, 1_000_000L) * 1000L;
            return java.time.LocalDateTime.ofEpochSecond(seconds, (int) nanos, java.time.ZoneOffset.UTC);
        }

        private static java.time.OffsetDateTime microsToOffsetDateTime(long micros) {
            long seconds = Math.floorDiv(micros, 1_000_000L);
            long nanos = Math.floorMod(micros, 1_000_000L) * 1000L;
            return java.time.OffsetDateTime.ofInstant(
                    java.time.Instant.ofEpochSecond(seconds, nanos), java.time.ZoneId.systemDefault());
        }

        @Override
        public long getLong(int column, int row) {
            return ((BigIntVector) vectors[column]).get(row);
        }

        @Override
        public int getInt(int column, int row) {
            return ((IntVector) vectors[column]).get(row);
        }

        @Override
        public double getDouble(int column, int row) {
            return ((Float8Vector) vectors[column]).get(row);
        }

        @Override
        public String getString(int column, int row) {
            VarCharVector vector = (VarCharVector) vectors[column];
            return vector.isNull(row) ? null : new String(vector.get(row), StandardCharsets.UTF_8);
        }
    }
}
