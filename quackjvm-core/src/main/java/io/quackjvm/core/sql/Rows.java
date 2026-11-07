package io.quackjvm.core.sql;

import io.quackjvm.core.arrow.ArrowBatch;
import io.quackjvm.core.arrow.ArrowObjectReader;
import io.quackjvm.core.arrow.ArrowResult;
import io.quackjvm.core.arrow.ArrowSupport;
import io.quackjvm.core.duckdb.DuckDBTypes;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.internal.Deadline;
import io.quackjvm.core.internal.ReadCheck;
import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * <h2>It reads</h2>
 *
 * <p>A statement that returns no rows - {@code DROP}, {@code COPY ... TO}, {@code ATTACH},
 * {@code SET} - is refused before it runs, as is more than one statement; run those with
 * {@code execute}. Anything that returns rows runs, {@code INSERT ... RETURNING} included, so this
 * guards against mistakes in your own SQL. For SQL you did not write, use {@code GuardedQuery} in
 * quackjvm-agent.</p>
 *
 * <h2>Timeouts</h2>
 *
 * <pre>
 * db.query(sql).timeout(Duration.ofSeconds(5)).list(String.class);   // QueryTimedOut after 5 s
 * </pre>
 */
public final class Rows {

    private final Connection connection;
    private final String sql;
    private final Object[] parameters;
    private final Duration timeout;

    private Rows(Connection connection, String sql, Object[] parameters, Duration timeout) {
        this.connection = connection;
        this.sql = sql;
        this.parameters = parameters;
        this.timeout = timeout;
    }

    /**
     * @param connection a connection each terminal method borrows and closes; pass a fresh one
     */
    public static Rows of(Connection connection, String sql, Object... parameters) {
        return new Rows(connection, sql, parameters, null);
    }

    /**
     * Cancels the query if it is still running - or still being read - after this long, and throws
     * {@link QueryTimedOut}. The connection is usable afterwards. Without it, a query runs for as
     * long as it takes.
     */
    public Rows timeout(Duration timeout) {
        return new Rows(connection, sql, parameters, Deadline.check(timeout));
    }

    /**
     * The single value of a one-row, one-column result - a count, a sum, an average. Null when that
     * value is NULL, such as the sum of no rows.
     *
     * @throws IllegalStateException if the query returned no rows
     */
    @SuppressWarnings("unchecked")
    public <T> T scalar(Class<T> type) {
        Object[] value = firstValue(type);
        if (value == null) {
            throw new IllegalStateException("Query returned no rows, so it has no scalar value: " + sql);
        }
        return (T) value[0];
    }

    /** The single value of a one-row result, or empty if the query matched nothing or the value is NULL. */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> scalarOptional(Class<T> type) {
        Object[] value = firstValue(type);
        return value == null ? Optional.empty() : Optional.ofNullable((T) value[0]);
    }

    /** The first row's first value, in an array of one so a NULL can be told from no row; null for no row. */
    private Object[] firstValue(Class<?> type) {
        // A one-row result is not worth an Arrow export: setting up a columnar batch costs more
        // than reading one value.
        Deadline deadline = null;
        try (Statement statement = open()) {
            deadline = Deadline.start(statement, timeout);
            try (Deadline running = deadline; ResultSet resultSet = run(statement)) {
                if (!resultSet.next()) {
                    return null;
                }
                Object value = DuckDBTypes.read(resultSet, 1, type);
                Sql.finishReading(resultSet);
                return new Object[] {value};
            }
        }
        catch (SQLException | RuntimeException e) {
            throw failed(e, deadline);
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
        boolean nested = layout.getColumns().stream().anyMatch(column -> column.getNested() != null);
        // Arrow reads columns of single values; a list, a struct or a map comes back through JDBC,
        // which hands them over whole.
        if (ArrowSupport.isAvailable() && !nested) {
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
                ColumnarLayout.Column<T, ?> column = columns.get(i);
                values[i] = column.getNested() == null
                        ? DuckDBTypes.read(resultSet, i + 1, column.getType())
                        : column.getNested().fromSql(resultSet.getObject(i + 1));
            }
            consumer.accept(layout.createObject(values));
        });
    }

    /** How many rows the query returns, without reading any of them. */
    public long count() {
        Deadline deadline = null;
        try {
            ReadCheck.beforePreparing(sql);
            PreparedStatement statement;
            try {
                statement = connection.prepareStatement("SELECT count(*) FROM (" + sql + ")");
            }
            catch (SQLException e) {
                // A statement that is not a query does not fit inside the count, so nothing ran.
                // Only now is it prepared alone, to say so: a count that works costs one prepare.
                refuseIfNotAQuery();
                throw e;
            }
            try (PreparedStatement counting = statement) {
                Sql.bindAll(counting, Arrays.asList(parameters), 1);
                deadline = Deadline.start(counting, timeout);
                try (Deadline running = deadline; ResultSet resultSet = counting.executeQuery()) {
                    resultSet.next();
                    return resultSet.getLong(1);
                }
            }
        }
        catch (QueryTimedOut | ReadCheck.Refused e) {
            throw e;
        }
        catch (SQLException | RuntimeException e) {
            if (deadline != null && deadline.hasExpired()) {
                throw new QueryTimedOut(timeout, e);
            }
            throw new IllegalStateException("Failed to count the rows of: " + sql + "\n" + reason(e), e);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    /** Prepares the statement alone - which does not run one statement - and refuses it if it is not a query. */
    private void refuseIfNotAQuery() {
        try (PreparedStatement alone = connection.prepareStatement(sql)) {
            ReadCheck.afterPreparing(alone, sql);
        }
        catch (SQLException notEvenThat) {
            // It does not parse on its own either; the count's own error says why.
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
        return SqlQuery.stream(connection, sql, timeout, parameters);
    }

    // ---------- Reading ----------

    private interface BatchConsumer {
        void accept(ArrowBatch batch);
    }

    private interface RowConsumer {
        void accept(ResultSet resultSet) throws SQLException;
    }

    private void readThroughArrow(BatchConsumer consumer) {
        Deadline deadline = null;
        try {
            Statement statement = open();
            deadline = Deadline.start(statement, timeout);
            try (Deadline running = deadline;
                 ArrowResult result = unprepared()
                         ? ArrowResult.of(statement, sql, ArrowResult.DEFAULT_BATCH_SIZE)
                         : ArrowResult.of((PreparedStatement) statement, ArrowResult.DEFAULT_BATCH_SIZE)) {
                for (ArrowBatch batch : result) {
                    consumer.accept(batch);
                }
            }
        }
        catch (SQLException | RuntimeException e) {
            throw failed(e, deadline);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    private void readThroughJdbc(RowConsumer consumer) {
        Deadline deadline = null;
        try (Statement statement = open()) {
            deadline = Deadline.start(statement, timeout);
            try (Deadline running = deadline; ResultSet resultSet = run(statement)) {
                while (resultSet.next()) {
                    consumer.accept(resultSet);
                }
            }
        }
        catch (SQLException | RuntimeException e) {
            throw failed(e, deadline);
        }
        finally {
            Sql.closeQuietly(connection);
        }
    }

    /**
     * Whether this runs on a plain statement rather than a prepared one: a {@code SELECT} with no
     * parameters, which needs no preparing to know it reads. DuckDB runs some queries far slower
     * prepared - an ASOF join returning many rows with no {@code ORDER BY} - and none measured faster.
     */
    private boolean unprepared() {
        return ReadCheck.runsUnprepared(sql, parameters);
    }

    /** The statement to run, not yet run - and refused if running it would not just read. */
    private Statement open() throws SQLException {
        if (unprepared()) {
            // One statement, which starts with SELECT: it returns rows, so there is nothing to refuse.
            ReadCheck.beforePreparing(sql);
            return connection.createStatement();
        }
        return prepare();
    }

    /** Runs a statement from {@link #open()}. */
    private ResultSet run(Statement statement) throws SQLException {
        return unprepared() ? statement.executeQuery(sql) : ((PreparedStatement) statement).executeQuery();
    }

    /** The statement, prepared and bound but not run - and refused if running it would not just read. */
    private PreparedStatement prepare() throws SQLException {
        ReadCheck.beforePreparing(sql);
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            ReadCheck.afterPreparing(statement, sql);
            Sql.bindAll(statement, Arrays.asList(parameters), 1);
        }
        catch (SQLException | RuntimeException e) {
            Sql.closeQuietly(statement);
            throw e;
        }
        return statement;
    }

    /**
     * What to throw: a timeout if the deadline cancelled the query; otherwise an unchecked exception
     * as it was thrown - the caller's own, from a consumer, included - and a SQLException wrapped.
     */
    private RuntimeException failed(Exception e, Deadline deadline) {
        if (deadline != null && deadline.hasExpired()) {
            return new QueryTimedOut(timeout, e);
        }
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Failed to run: " + sql + "\n" + reason(e), e);
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        return DuckDBTypes.fromSqlValue(value, type);
    }

    /** DuckDB's first line of why, which is what says what went wrong. */
    static String reason(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.split("\n")[0];
    }
}
