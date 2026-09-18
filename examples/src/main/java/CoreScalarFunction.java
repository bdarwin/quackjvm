/*
 * Java functions callable from SQL - scalar UDFs, added to DuckDB's Java client in 1.5.
 *
 * A scalar function takes one row's values and returns one value, like upper() or round(). Writing
 * one in Java lets SQL call logic that lives in your codebase - a business rule, a lookup, a parser -
 * instead of re-implementing it in SQL or pulling the rows out to apply it.
 *
 * Shows the ways to write one, and what each costs per row against the same work done by a built-in
 * SQL expression. Measured on 2,000,000 rows, 10 cores:
 *
 *                                 1 thread     10 threads
 *   built-in SQL expression       1.4 ns/row   0.4 ns/row   parallelises across cores
 *   Java, withDoubleFunction      6.0 ns/row   6.7 ns/row   did not get faster with more threads
 *   Java, vectorised              5.9 ns/row   6.4 ns/row   nor did this
 *   Java, Function<Double,...>   34.8 ns/row  10.2 ns/row   boxing; slowest, but does scale
 *
 * So the call into Java costs about 4x a built-in expression on one thread, and about 15x on a
 * multi-core machine, because the built-in expression spreads over the cores and the primitive Java
 * forms, here, did not. In absolute terms it is still cheap - two million rows in about 12 ms - but
 * it is not free, and over a billion rows it will not use your cores. If the logic can be written as
 * SQL, write it as SQL; reach for a Java UDF for logic that already lives in Java.
 *
 * A hand-written vectorised function buys nothing over withDoubleFunction, which the builder already
 * runs a chunk at a time.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreScalarFunction.java
 */

import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBFunctions;
import org.duckdb.DuckDBReadableVector;
import org.duckdb.DuckDBScalarFunctionBuilder;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

public class CoreScalarFunction {

    /** Logic that already exists in Java, and that you would rather not rewrite as SQL. */
    static final Map<String, String> REGION_OF_COUNTRY = Map.of(
            "FR", "EMEA", "DE", "EMEA", "IE", "EMEA",
            "US", "AMER", "CA", "AMER", "BR", "LATAM",
            "JP", "APAC", "IN", "APAC");

    static String regionOf(String country) {
        return REGION_OF_COUNTRY.getOrDefault(country, "OTHER");
    }

    static final int ROWS = 2_000_000;

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

            execute(connection, "CREATE TABLE sale AS SELECT i AS id,"
                    + " ['FR','DE','IE','US','CA','BR','JP','IN','ZZ'][i % 9 + 1] AS country,"
                    + " CAST(10.0 + (i % 1000) AS DOUBLE) AS price FROM range(" + ROWS + ") t(i)");

            // 1. The simplest form: a lambda from the Java type to the Java type. DuckDB maps String
            //    to VARCHAR. NULL in gives NULL out without calling the lambda.
            try (DuckDBScalarFunctionBuilder builder = DuckDBFunctions.scalarFunction()) {
                builder.withName("region_of")
                        .withParameter(String.class)
                        .withReturnType(String.class)
                        .withNullInNullOut()
                        .withFunction((String country) -> regionOf(country))
                        .register(connection);
            }

            System.out.println("1. A Java lookup, called from GROUP BY:");
            print(connection, "SELECT region_of(country) AS region, count(*) AS sales,"
                    + " CAST(sum(price) AS DECIMAL(18,2)) AS revenue FROM sale GROUP BY 1 ORDER BY 1");

            // 2. A primitive form: no boxing on the way in or out.
            try (DuckDBScalarFunctionBuilder builder = DuckDBFunctions.scalarFunction()) {
                builder.withName("with_vat_primitive")
                        .withParameter(Double.class)
                        .withReturnType(Double.class)
                        .withDoubleFunction(price -> price * 1.2)
                        .register(connection);
            }

            // 3. The same arithmetic, boxed - Function<Double, Double>.
            try (DuckDBScalarFunctionBuilder builder = DuckDBFunctions.scalarFunction()) {
                builder.withName("with_vat_boxed")
                        .withParameter(Double.class)
                        .withReturnType(Double.class)
                        .withFunction((Double price) -> price * 1.2)
                        .register(connection);
            }

            // 4. Vectorised: one call per chunk of up to 2,048 rows, reading and writing DuckDB's
            //    vectors directly. More code, and - measured below - not faster than
            //    withDoubleFunction, which the builder already runs a chunk at a time. Reach for this
            //    form when you need the whole chunk at once, not for speed.
            try (DuckDBScalarFunctionBuilder builder = DuckDBFunctions.scalarFunction()) {
                builder.withName("with_vat_vectorised")
                        .withParameter(Double.class)
                        .withReturnType(Double.class)
                        .withVectorizedFunction((input, output) -> {
                            DuckDBReadableVector prices = input.vector(0);
                            long rows = input.rowCount();
                            for (long row = 0; row < rows; row++) {
                                if (prices.isNull(row)) {
                                    output.setNull(row);
                                }
                                else {
                                    output.setDouble(row, prices.getDouble(row) * 1.2);
                                }
                            }
                        })
                        .register(connection);
            }

            System.out.println();
            System.out.printf("2. What a call into Java costs, summing price * 1.2 over %,d rows:%n", ROWS);
            double sql = time(connection, "SELECT sum(price * 1.2) FROM sale");
            report("built-in SQL expression", sql, sql);
            report("Java, vectorised (one call per chunk)", time(connection,
                    "SELECT sum(with_vat_vectorised(price)) FROM sale"), sql);
            report("Java, withDoubleFunction", time(connection,
                    "SELECT sum(with_vat_primitive(price)) FROM sale"), sql);
            report("Java, Function<Double, Double>", time(connection,
                    "SELECT sum(with_vat_boxed(price)) FROM sale"), sql);

            System.out.println();
            System.out.println("3. All four agree:");
            print(connection, "SELECT CAST(sum(price * 1.2) AS DECIMAL(18,2)) AS in_sql,"
                    + " CAST(sum(with_vat_vectorised(price)) AS DECIMAL(18,2)) AS vectorised,"
                    + " CAST(sum(with_vat_primitive(price)) AS DECIMAL(18,2)) AS primitive,"
                    + " CAST(sum(with_vat_boxed(price)) AS DECIMAL(18,2)) AS boxed FROM sale");
        }
    }

    /** Best of three, so the first run's warm-up does not count against any one form. */
    private static double time(DuckDBConnection connection, String sql) throws SQLException {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long startedAt = System.nanoTime();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
            }
            best = Math.min(best, (System.nanoTime() - startedAt) / 1e6);
        }
        return best;
    }

    private static void report(String label, double millis, double baseline) {
        System.out.printf("   %-40s %8.1f ms  %6.1f ns/row  %5.1fx%n",
                label, millis, millis * 1e6 / ROWS, millis / baseline);
    }

    private static void execute(DuckDBConnection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void print(DuckDBConnection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                StringBuilder line = new StringBuilder("   ");
                for (int i = 1; i <= columns; i++) {
                    line.append(rows.getMetaData().getColumnLabel(i)).append('=')
                            .append(rows.getString(i)).append(i < columns ? "  " : "");
                }
                System.out.println(line);
            }
        }
    }
}


/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 1. A Java lookup, called from GROUP BY:
 *    region=AMER  sales=444444  revenue=226443552.00
 *    region=APAC  sales=444444  revenue=226444884.00
 *    region=EMEA  sales=666668  revenue=339666680.00
 *    region=LATAM  sales=222222  revenue=113222109.00
 *    region=OTHER  sales=222222  revenue=113222775.00
 *
 * 2. What a call into Java costs, summing price * 1.2 over 2,000,000 rows:
 *    built-in SQL expression                       0.7 ms     0.4 ns/row    1.0x
 *    Java, vectorised (one call per chunk)        12.1 ms     6.0 ns/row   16.2x
 *    Java, withDoubleFunction                     12.0 ms     6.0 ns/row   16.0x
 *    Java, Function<Double, Double>               20.3 ms    10.2 ns/row   27.2x
 *
 * 3. All four agree:
 *    in_sql=1222800000.00  vectorised=1222800000.00  primitive=1222800000.00  boxed=1222800000.00
 */
