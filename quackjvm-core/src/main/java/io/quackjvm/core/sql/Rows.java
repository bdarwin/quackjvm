package io.quackjvm.core.sql;

import io.quackjvm.core.arrow.ArrowBatch;
import io.quackjvm.core.arrow.ArrowObjectReader;
import io.quackjvm.core.arrow.ArrowResult;
import io.quackjvm.core.arrow.ArrowSupport;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The result of a query, read without rebuilding any domain objects.
 *
 * <p>Materialising objects is the dominant cost of reading from a columnar store, and most
 * questions do not need them. Summing a column over 200,000 matching rows took 105 ms when every
 * matching object was rebuilt first and 2.5 ms when DuckDB was asked for the sum instead - the same
 * answer, forty times faster, and no garbage. Projecting a single column of those rows rather than
 * whole objects was 8 ms against 105 ms.</p>
 *
 * <pre>
 * double total = database.query("SELECT sum(price) FROM car WHERE make = ?", "Ford")
 *                        .scalar(Double.class);
 *
 * List&lt;String&gt; makes = database.query("SELECT DISTINCT make FROM car").list(String.class);
 *
 * // a record whose components line up with the selected columns
 * record MakeStats(String make, long count, double averagePrice) {}
 * List&lt;MakeStats&gt; stats = database.query(
 *         "SELECT make, count(*), avg(price) FROM car GROUP BY 1").records(MakeStats.class);
 *
 * // DuckDB's own PIVOT, which has no equivalent in an object query engine
 * database.query("PIVOT car ON colour USING count(*) GROUP BY make").forEachRow(...);
 * </pre>
 *
 * <p>Each terminal method runs the query once and releases its resources, so a {@code Rows} is used
 * once. The exception is {@link #stream()}, which is lazy and must be closed.</p>
 */
public final class Rows {

    private final Connection connection;
    private final String sql;
    private final Object[] parameters;

    private Rows(Connection connection, String sql, Object[] parameters) {
        this.connection = connection;
        this.sql = sql;
        this.parameters = parameters;
    }

    /**
     * @param connection a connection each terminal method borrows and closes; pass a fresh one
     */
    public static Rows of(Connection connection, String sql, Object... parameters) {
        return new Rows(connection, sql, parameters);
    }

    /**
     * The single value of a one-row, one-column result - a count, a sum, an average.
     *
     * @throws IllegalStateException if the query returned no rows
     */
    public <T> T scalar(Class<T> type) {
        return scalarOptional(type).orElseThrow(() ->
                new IllegalStateException("Query returned no rows, so it has no scalar value: " + sql));
    }

    /** The single value of a one-row result, or empty if the query matched nothing. */
    public <T> Optional<T> scalarOptional(Class<T> type) {
        // A one-row result is not worth an Arrow export: setting up a columnar batch costs more
        // than reading one value.
        try (PreparedStatement statement = prepare()) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(DuckDBTypes.read(resultSet, 1, type));
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to run: " + sql, e);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    /** Every value of the first column, as the given type. */
    public <T> List<T> list(Class<T> type) {
        List<T> values = new ArrayList<>();
        forEachValue(type, values::add);
        return values;
    }

    /** Every value of the first column, handed to the consumer as it is read. */
    @SuppressWarnings("unchecked")
    public <T> void forEachValue(Class<T> type, java.util.function.Consumer<T> consumer) {
        if (ArrowSupport.isAvailable()) {
            readThroughArrow(batch -> {
                int rowCount = batch.getRowCount();
                for (int row = 0; row < rowCount; row++) {
                    consumer.accept((T) convert(batch.getValue(0, row), type));
                }
            });
            return;
        }
        readThroughJdbc(resultSet -> consumer.accept(DuckDBTypes.read(resultSet, 1, type)));
    }

    /**
     * Maps the selected columns onto a record, by position.
     *
     * <p>The record's components must line up with the columns the query selects, in order and in
     * type. This is the shape most aggregate and projection queries want: a small result described
     * by a small record, with no domain object anywhere.</p>
     */
    public <T> List<T> records(Class<T> recordType) {
        List<T> results = new ArrayList<>();
        forEachRecord(recordType, results::add);
        return results;
    }

    /** Maps each row onto a record and hands it to the consumer as it is read. */
    public <T> void forEachRecord(Class<T> recordType, java.util.function.Consumer<T> consumer) {
        ColumnarLayout<T> layout = ColumnarLayout.ofRecord(recordType);
        if (ArrowSupport.isAvailable()) {
            ArrowObjectReader<T> reader = new ArrowObjectReader<>(layout, 0);
            readThroughArrow(batch -> {
                int rowCount = batch.getRowCount();
                for (int row = 0; row < rowCount; row++) {
                    consumer.accept(reader.readObject(batch, row));
                }
            });
            return;
        }
        List<ColumnarLayout.Column<T, ?>> columns = layout.getColumns();
        readThroughJdbc(resultSet -> {
            Object[] values = new Object[columns.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = DuckDBTypes.read(resultSet, i + 1, columns.get(i).getType());
            }
            consumer.accept(layout.createObject(values));
        });
    }

    /** How many rows the query returns, without reading any of them. */
    public long count() {
        try {
            return Rows.of(connection, "SELECT count(*) FROM (" + sql + ")", parameters)
                    .scalar(Long.class);
        }
        catch (RuntimeException e) {
            throw new IllegalStateException("Failed to count the rows of: " + sql, e);
        }
    }

    /** Hands each row to the consumer as an untyped view. The view is only valid during the call. */
    public void forEachRow(java.util.function.Consumer<SqlRow> consumer) {
        try (Stream<SqlRow> stream = stream()) {
            stream.forEach(consumer);
        }
    }

    /** A lazy stream of untyped rows. Holds the connection, so close it. */
    public Stream<SqlRow> stream() {
        return SqlQuery.stream(connection, sql, parameters);
    }

    // ---------- Reading ----------

    private interface BatchConsumer {
        void accept(ArrowBatch batch);
    }

    private interface RowConsumer {
        void accept(ResultSet resultSet) throws SQLException;
    }

    private void readThroughArrow(BatchConsumer consumer) {
        try (ArrowResult result = ArrowResult.of(prepare(), ArrowResult.DEFAULT_BATCH_SIZE)) {
            for (ArrowBatch batch : result) {
                consumer.accept(batch);
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to run: " + sql, e);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    private void readThroughJdbc(RowConsumer consumer) {
        try (PreparedStatement statement = prepare()) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    consumer.accept(resultSet);
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to run: " + sql, e);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    private PreparedStatement prepare() throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        Sql.bindAll(statement, List.of(parameters), 1);
        return statement;
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        return DuckDBTypes.fromSqlValue(value, type);
    }
}
