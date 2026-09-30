package io.quackjvm.core.live;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.live.internal.ChunkWriters;
import org.duckdb.DuckDBDataChunkWriter;
import org.duckdb.DuckDBFunctions;
import org.duckdb.DuckDBTableFunction;
import org.duckdb.DuckDBTableFunctionBindInfo;
import org.duckdb.DuckDBTableFunctionBuilder;
import org.duckdb.DuckDBTableFunctionCallInfo;
import org.duckdb.DuckDBTableFunctionInitInfo;
import org.duckdb.DuckDBWritableVector;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The table function behind a {@link LiveTables} table: three callbacks over a Java {@code Iterable}.
 *
 * <p>{@code bind} declares the columns from the layout and tells the optimizer roughly how many rows
 * there are. {@code init} takes the projection DuckDB pushed down and starts an iterator.
 * {@code apply} fills a chunk at a time, reading only the columns the query asked for.</p>
 */
final class LiveTableFunction implements DuckDBTableFunction<LiveTableFunction.Bound,
        LiveTableFunction.Scan, Object> {

    /** Prefix for the function name, so that the view can have the plain name. */
    private static final String PREFIX = "quack_live_";

    /**
     * What each registered live table reads from, by the name of its function. A function is registered
     * with DuckDB once per name and cannot be unregistered in 1.5.5, so what it reads is looked up here
     * every time it binds - and taking a live table away removes the entry, so a query against it says
     * so rather than reading something stale.
     */
    private static final java.util.Map<String, Source> SOURCES = new java.util.concurrent.ConcurrentHashMap<>();

    /** Where a live table's rows come from, and how to turn one into columns. */
    record Source(Supplier<? extends Iterable<?>> rows, ColumnarLayout<?> layout, LiveTable table) {
    }

    /** What a query is reading: the rows, how to shred them, and which columns were asked for. */
    record Bound(String functionName, List<ColumnarLayout.Column<Object, ?>> columns,
                        List<ChunkWriters.ChunkWriter> writers, Iterable<?> rows, LiveTable table) {
    }

    /** Where one execution has got to. */
    static final class Scan {
        private Iterator<?> iterator;
        private int[] projection;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Bound bind(DuckDBTableFunctionBindInfo info) {
        String functionName;
        try (org.duckdb.DuckDBValue argument = info.getParameter(0)) {
            functionName = argument.getString();
        }
        Source source = SOURCES.get(functionName);
        if (source == null) {
            throw new IllegalStateException("There is no live table behind " + functionName
                    + " any more - it was unregistered.");
        }
        ColumnarLayout<Object> layout = (ColumnarLayout<Object>) source.layout();
        List<ColumnarLayout.Column<Object, ?>> columns = new ArrayList<>(layout.getColumns());
        List<ChunkWriters.ChunkWriter> writers = new ArrayList<>(columns.size());
        for (ColumnarLayout.Column<Object, ?> column : columns) {
            declareColumn(info, column.getName(), column.getType());
            writers.add(ChunkWriters.forType(column.getType()));
        }
        Iterable<?> rows = source.rows().get();
        if (rows == null) {
            throw new IllegalStateException("The supplier behind " + functionName + " returned nothing to read.");
        }
        if (rows instanceof Collection<?> collection) {
            // An estimate, so the optimizer can pick a build side; not a promise.
            info.setCardinality(collection.size(), false);
        }
        return new Bound(functionName, columns, writers, rows, source.table());
    }

    /**
     * Declares one result column as the type quackjvm would store that Java type as.
     *
     * <p>DuckDB's {@code addResultColumn(name, Class)} takes most Java types but not all: a
     * {@code Character}, an {@code Instant} and an enum are refused, although a table stores them
     * happily as VARCHAR, TIMESTAMP and INTEGER. Those are declared as what they become, so that a live
     * table and a stored table of the same objects have the same columns. A BigDecimal is declared
     * explicitly too, because the driver would give it scale 18 where quackjvm stores scale 10.</p>
     */
    private static void declareColumn(DuckDBTableFunctionBindInfo info, String name, Class<?> type) {
        if (type == java.math.BigDecimal.class) {
            try (org.duckdb.DuckDBLogicalType decimal =
                         org.duckdb.DuckDBLogicalType.decimal(38, io.quackjvm.core.duckdb.DuckDBTypes.DECIMAL_SCALE)) {
                info.addResultColumn(name, decimal);
                return;
            }
            catch (SQLException e) {
                throw new IllegalStateException("Failed to declare " + name + " as DECIMAL", e);
            }
        }
        Class<?> declared = type;
        if (type == Character.class) {
            declared = String.class;
        }
        else if (type == java.time.Instant.class) {
            declared = java.time.LocalDateTime.class;
        }
        else if (type.isEnum()) {
            declared = Integer.class;
        }
        info.addResultColumn(name, declared);
    }

    @Override
    public Scan init(DuckDBTableFunctionInitInfo info) {
        Bound bound = info.getBindData();
        Scan scan = new Scan();
        scan.iterator = bound.rows().iterator();
        int projected = (int) info.getColumnCount();
        scan.projection = new int[projected];
        for (int i = 0; i < projected; i++) {
            scan.projection[i] = (int) info.getColumnIndex(i);
        }
        // A Java Iterable cannot be split safely in general, so one thread reads it. What DuckDB does
        // with the rows afterwards is still parallel.
        info.setMaxThreads(1);
        return scan;
    }

    @Override
    public long apply(DuckDBTableFunctionCallInfo call, DuckDBDataChunkWriter out) {
        Bound bound = call.getBindData();
        Scan scan = call.getInitData();
        List<ColumnarLayout.Column<Object, ?>> columns = bound.columns();
        List<ChunkWriters.ChunkWriter> writers = bound.writers();
        int[] projection = scan.projection;
        long capacity = out.capacity();
        long written = 0;
        while (written < capacity && scan.iterator.hasNext()) {
            Object object = scan.iterator.next();
            for (int i = 0; i < projection.length; i++) {
                DuckDBWritableVector vector = out.vector(i);
                int column = projection[i];
                Object value = columns.get(column).getValue(object);
                if (value == null) {
                    vector.setNull(written);
                }
                else {
                    writers.get(column).write(vector, written, value);
                }
            }
            written++;
        }
        if (written > 0 && bound.table() != null) {
            bound.table().countRows(written);
        }
        return written;
    }

    // ---------- registration ----------

    /** Registers the function, the view over it, and what it reads. */
    static <O> LiveTable register(Connection connection, String name, Supplier<Iterable<O>> rows,
                                         ColumnarLayout<O> layout, boolean snapshot) {
        checkName(name);
        checkWritable(layout);
        String functionName = PREFIX + name.toLowerCase(Locale.ROOT);
        Supplier<Iterable<O>> source = rows;
        if (snapshot) {
            List<O> copy = new ArrayList<>();
            for (O object : rows.get()) {
                copy.add(object);
            }
            source = () -> copy;
        }
        LiveTable table = new LiveTable(connection, name, functionName, layout);
        SOURCES.put(functionName, new Source(source, layout, table));
        if (!isRegistered(connection, functionName)) {
            try (DuckDBTableFunctionBuilder builder = DuckDBFunctions.tableFunction()) {
                builder.withName(functionName)
                        .withParameter(String.class)
                        .withProjectionPushdown()
                        .withFunction(new LiveTableFunction())
                        .register(connection);
            }
            catch (SQLException e) {
                SOURCES.remove(functionName);
                throw new IllegalStateException("Failed to register a live table function for " + name, e);
            }
        }
        // The view is what lets SQL say "car" rather than "quack_live_car('quack_live_car')".
        Sql.execute(connection, "CREATE OR REPLACE VIEW " + Sql.quote(name) + " AS SELECT * FROM "
                + Sql.quote(functionName) + "('" + functionName + "')");
        return table;
    }

    /** Forgets what a live table read, so its name stops answering. */
    static void forget(String functionName) {
        SOURCES.remove(functionName);
    }

    private static boolean isRegistered(Connection connection, String functionName) {
        return Sql.queryLong(connection, "SELECT count(*) FROM duckdb_functions() WHERE function_name = '"
                + functionName + "'", List.of()) > 0;
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("A live table's name must be letters, digits and underscores,"
                    + " starting with a letter or underscore: " + name);
        }
    }

    /** Refuses a layout whose columns cannot be written into a chunk, before anything is registered. */
    private static void checkWritable(ColumnarLayout<?> layout) {
        for (ColumnarLayout.Column<?, ?> column : layout.getColumns()) {
            String unsupported = ChunkWriters.unsupportedReason(column.getType());
            if (unsupported != null) {
                throw new IllegalArgumentException("Column '" + column.getName() + "' of "
                        + layout.getObjectType().getSimpleName() + " cannot be read into SQL: " + unsupported);
            }
        }
    }
}
