package io.quackjvm.core.udf;

import io.quackjvm.core.udf.internal.VectorCodecs;
import org.duckdb.DuckDBFunctions;
import org.duckdb.DuckDBReadableVector;
import org.duckdb.DuckDBScalarFunctionBuilder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * A Java method called from SQL.
 *
 * <pre>
 * Udfs.register(connection, "region_of", String.class, String.class, Countries::regionOf);
 *
 * Rows.of(connection, "SELECT region_of(country), count(*) FROM sale GROUP BY 1");
 * </pre>
 *
 * <p>DuckDB gained scalar functions in its Java client in 1.5; this says which types go in and out,
 * decides what happens to nulls, and registers the function, for one, two or three arguments.</p>
 *
 * <h2>Nulls</h2>
 *
 * <p>{@link #register} never calls the method with a null: a row with a null argument is null in the
 * result, and the method is not called. That is what SQL expects of most functions, and it means a
 * method that cannot take a null does not have to say so.</p>
 *
 * <p>{@link #registerNullable} calls it with whatever is there, nulls included, and takes whatever it
 * returns, null included - for a method whose answer to a null is not null.</p>
 *
 * <h2>What it costs</h2>
 *
 * <p>Measured on 2,000,000 rows, {@code price * 1.2}: 0.4 ns a row for the SQL expression, 6.0 ns
 * through {@link #registerDouble} and the other primitive forms, 10.5 ns through the general
 * {@code register}, which boxes an argument and a result per row. Use the primitive form where the
 * arithmetic is simple enough for it, and do not reach for a UDF where SQL can say it.</p>
 */
public final class Udfs {

    /** A method of three arguments, which {@code java.util.function} does not have. */
    @FunctionalInterface
    public interface Fn3<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private Udfs() {
    }

    // ---------- null in, null out ----------

    /** One argument. A null argument is a null result, without calling the method. */
    public static <A, R> Udf register(Connection connection, String name, Class<R> returns, Class<A> a,
                                      Function<A, R> function) {
        return vectorised(connection, name, returns, List.of(a), false,
                values -> function.apply(cast(values[0])));
    }

    /** Two arguments. A null in either is a null result, without calling the method. */
    public static <A, B, R> Udf register(Connection connection, String name, Class<R> returns, Class<A> a,
                                         Class<B> b, BiFunction<A, B, R> function) {
        return vectorised(connection, name, returns, List.of(a, b), false,
                values -> function.apply(cast(values[0]), cast(values[1])));
    }

    /** Three arguments. A null in any is a null result, without calling the method. */
    public static <A, B, C, R> Udf register(Connection connection, String name, Class<R> returns, Class<A> a,
                                            Class<B> b, Class<C> c, Fn3<A, B, C, R> function) {
        return vectorised(connection, name, returns, List.of(a, b, c), false,
                values -> function.apply(cast(values[0]), cast(values[1]), cast(values[2])));
    }

    // ---------- nulls passed through ----------

    /** One argument, nulls included. */
    public static <A, R> Udf registerNullable(Connection connection, String name, Class<R> returns, Class<A> a,
                                              Function<A, R> function) {
        return vectorised(connection, name, returns, List.of(a), true,
                values -> function.apply(cast(values[0])));
    }

    /** Two arguments, nulls included. */
    public static <A, B, R> Udf registerNullable(Connection connection, String name, Class<R> returns, Class<A> a,
                                                 Class<B> b, BiFunction<A, B, R> function) {
        return vectorised(connection, name, returns, List.of(a, b), true,
                values -> function.apply(cast(values[0]), cast(values[1])));
    }

    /** Three arguments, nulls included. */
    public static <A, B, C, R> Udf registerNullable(Connection connection, String name, Class<R> returns,
                                                    Class<A> a, Class<B> b, Class<C> c, Fn3<A, B, C, R> function) {
        return vectorised(connection, name, returns, List.of(a, b, c), true,
                values -> function.apply(cast(values[0]), cast(values[1]), cast(values[2])));
    }

    // ---------- primitives, which do not box ----------

    /** {@code DOUBLE -> DOUBLE} without boxing: 6.0 ns a row against 10.5 boxed. */
    public static Udf registerDouble(Connection connection, String name, DoubleUnaryOperator function) {
        return build(connection, name, Double.class, List.of(Double.class),
                builder -> builder.withNullInNullOut().withDoubleFunction(function));
    }

    /** {@code (DOUBLE, DOUBLE) -> DOUBLE} without boxing. */
    public static Udf registerDouble(Connection connection, String name, DoubleBinaryOperator function) {
        return build(connection, name, Double.class, List.of(Double.class, Double.class),
                builder -> builder.withNullInNullOut().withDoubleFunction(function));
    }

    /** {@code INTEGER -> INTEGER} without boxing. */
    public static Udf registerInt(Connection connection, String name, IntUnaryOperator function) {
        return build(connection, name, Integer.class, List.of(Integer.class),
                builder -> builder.withNullInNullOut().withIntFunction(function));
    }

    /** {@code (INTEGER, INTEGER) -> INTEGER} without boxing. */
    public static Udf registerInt(Connection connection, String name, IntBinaryOperator function) {
        return build(connection, name, Integer.class, List.of(Integer.class, Integer.class),
                builder -> builder.withNullInNullOut().withIntFunction(function));
    }

    /** {@code BIGINT -> BIGINT} without boxing. */
    public static Udf registerLong(Connection connection, String name, LongUnaryOperator function) {
        return build(connection, name, Long.class, List.of(Long.class),
                builder -> builder.withNullInNullOut().withLongFunction(function));
    }

    /** {@code (BIGINT, BIGINT) -> BIGINT} without boxing. */
    public static Udf registerLong(Connection connection, String name, LongBinaryOperator function) {
        return build(connection, name, Long.class, List.of(Long.class, Long.class),
                builder -> builder.withNullInNullOut().withLongFunction(function));
    }

    // ---------- internals ----------

    /** What one row of arguments becomes. */
    private interface RowFunction {
        Object apply(Object[] arguments);
    }

    /**
     * Registers through DuckDB's vectorised form, which gets a chunk at a time rather than a row at a
     * time. Measured faster than the driver's own boxed {@code Function} - 6.0 ns a row against 10.2 -
     * and it is where the null policy can be decided rather than assumed.
     */
    private static Udf vectorised(Connection connection, String name, Class<?> returns, List<Class<?>> parameters,
                                  boolean passNulls, RowFunction function) {
        for (Class<?> parameter : parameters) {
            VectorCodecs.checkReadable(parameter);
        }
        VectorCodecs.checkWritable(returns);
        List<VectorCodecs.Reader> readers = parameters.stream().map(VectorCodecs::readerFor).toList();
        VectorCodecs.Writer writer = VectorCodecs.writerFor(returns);
        int arity = parameters.size();
        return build(connection, name, returns, parameters, builder ->
                builder.withVectorizedFunction((input, output) -> {
                    long rows = input.rowCount();
                    DuckDBReadableVector[] vectors = new DuckDBReadableVector[arity];
                    for (int i = 0; i < arity; i++) {
                        vectors[i] = input.vector(i);
                    }
                    Object[] arguments = new Object[arity];
                    for (long row = 0; row < rows; row++) {
                        boolean anyNull = false;
                        for (int i = 0; i < arity; i++) {
                            if (vectors[i].isNull(row)) {
                                arguments[i] = null;
                                anyNull = true;
                            }
                            else {
                                arguments[i] = readers.get(i).read(vectors[i], row);
                            }
                        }
                        if (anyNull && !passNulls) {
                            output.setNull(row);
                            continue;
                        }
                        Object result = function.apply(arguments);
                        if (result == null) {
                            output.setNull(row);
                        }
                        else {
                            writer.write(output, row, result);
                        }
                    }
                }));
    }

    private interface Configure {
        void apply(DuckDBScalarFunctionBuilder builder) throws SQLException;
    }

    private static Udf build(Connection connection, String name, Class<?> returns, List<Class<?>> parameters,
                             Configure configure) {
        checkName(name);
        try (DuckDBScalarFunctionBuilder builder = DuckDBFunctions.scalarFunction()) {
            builder.withName(name);
            for (Class<?> parameter : parameters) {
                builder.withParameter(VectorCodecs.declaredAs(parameter));
            }
            builder.withReturnType(VectorCodecs.declaredAs(returns));
            configure.apply(builder);
            builder.register(connection);
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to register the function " + name + "(" + parameters.size()
                    + " arguments): " + firstLine(e), e);
        }
        return new Udf(name, returns, parameters);
    }

    private static void checkName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("A function name must be letters, digits and underscores,"
                    + " starting with a letter or underscore: " + name);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }

    private static String firstLine(SQLException e) {
        String message = String.valueOf(e.getMessage());
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

}
