/*
 * A Java method called from SQL, without writing a callback by hand.
 *
 * Udfs.register says which types go in and out, decides what happens to nulls, and registers the
 * function. One, two or three arguments; a primitive form that does not box.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx2g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreUdfs.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import io.quackjvm.core.udf.Udf;
import io.quackjvm.core.udf.Udfs;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class CoreUdfs {

    static final Map<String, String> REGION_OF = Map.of(
            "FR", "EMEA", "DE", "EMEA", "IE", "EMEA",
            "US", "AMER", "CA", "AMER", "BR", "LATAM",
            "JP", "APAC", "IN", "APAC");

    /** The kind of method that belongs in Java rather than in SQL: a lookup nobody wants to maintain twice. */
    static String regionOf(String country) {
        return REGION_OF.getOrDefault(country, "OTHER");
    }

    static final int ROWS = 2_000_000;

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            Sql.execute(connection, "CREATE TABLE sale AS SELECT i AS id,"
                    + " ['FR','DE','IE','US','CA','BR','JP','IN','ZZ'][i % 9 + 1] AS country,"
                    + " CAST(10.0 + (i % 1000) AS DOUBLE) AS price FROM range(" + ROWS + ") t(i)");

            Udf region = Udfs.register(connection, "region_of", String.class, String.class, CoreUdfs::regionOf);
            System.out.println("A Java lookup, called from GROUP BY: " + region);
            System.out.println("-----------------------------------");
            print(connection, "SELECT region_of(country) AS region, count(*) AS sales,"
                    + " CAST(sum(price) AS DECIMAL(18,2)) AS revenue FROM sale GROUP BY 1 ORDER BY 1");

            System.out.println("\nOne, two and three arguments");
            System.out.println("----------------------------");
            Udfs.register(connection, "with_tax", Double.class, Double.class, Double.class,
                    (price, rate) -> price * (1 + rate));
            Udfs.register(connection, "clamp", Double.class, Double.class, Double.class, Double.class,
                    (value, low, high) -> Math.min(Math.max(value, low), high));
            System.out.println("   with_tax(100.0, 0.2)        = " + one(connection, "SELECT with_tax(100.0, 0.2)"));
            System.out.println("   clamp(150.0, 0.0, 100.0)    = "
                    + one(connection, "SELECT clamp(150.0, 0.0, 100.0)"));

            System.out.println("\nNulls");
            System.out.println("-----");
            AtomicLong calls = new AtomicLong();
            Udfs.register(connection, "shout", String.class, String.class, text -> {
                calls.incrementAndGet();
                return text.toUpperCase();
            });
            Udfs.registerNullable(connection, "shout_or_unknown", String.class, String.class,
                    text -> text == null ? "UNKNOWN" : text.toUpperCase());
            System.out.println("   register:         shout(NULL)           = "
                    + one(connection, "SELECT shout(NULL::VARCHAR)") + "   (the method was called "
                    + calls.get() + " times)");
            System.out.println("   registerNullable: shout_or_unknown(NULL) = "
                    + one(connection, "SELECT shout_or_unknown(NULL::VARCHAR)"));
            Udfs.register(connection, "even_only", Integer.class, Integer.class,
                    number -> number % 2 == 0 ? number : null);
            System.out.println("   a method may return null: even_only(5)   = "
                    + one(connection, "SELECT even_only(5)"));

            System.out.println("\nWhat a call into Java costs, summing price * 1.2 over " + String.format("%,d", ROWS)
                    + " rows");
            System.out.println("--------------------------------------------------------------------");
            Udfs.registerDouble(connection, "vat_primitive", (double price) -> price * 1.2);
            Udfs.register(connection, "vat_boxed", Double.class, Double.class, price -> price * 1.2);
            double inSql = median(connection, "SELECT sum(price * 1.2) FROM sale");
            double primitive = median(connection, "SELECT sum(vat_primitive(price)) FROM sale");
            double boxed = median(connection, "SELECT sum(vat_boxed(price)) FROM sale");
            row("built-in SQL expression", inSql, inSql);
            row("Udfs.registerDouble (no boxing)", primitive, inSql);
            row("Udfs.register (boxed)", boxed, inSql);

            System.out.println("\nAnd they agree:");
            System.out.println("   " + one(connection, "SELECT CAST(sum(price * 1.2) AS DECIMAL(18,2)) AS in_sql,"
                    + " CAST(sum(vat_primitive(price)) AS DECIMAL(18,2)) AS primitive,"
                    + " CAST(sum(vat_boxed(price)) AS DECIMAL(18,2)) AS boxed FROM sale"));

            System.out.println("\nWhat cannot cross");
            System.out.println("-----------------");
            try {
                Udfs.register(connection, "bad", String.class, java.util.UUID.class, Object::toString);
            }
            catch (IllegalArgumentException expected) {
                System.out.println("   " + expected.getMessage());
            }
        }
    }

    static void row(String what, double ms, double baseline) {
        System.out.printf("   %-34s %6.1f ms  %5.1f ns/row  %5.1fx%n", what, ms, ms * 1e6 / ROWS, ms / baseline);
    }

    static String one(DuckDBConnection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        Rows.of(connection.duplicate(), sql).forEachRow((SqlRow row) -> {
            StringBuilder line = new StringBuilder();
            for (String column : row.getColumnNames()) {
                line.append(line.length() == 0 ? "" : "  ").append(column).append('=').append(row.get(column));
            }
            values.add(row.getColumnNames().size() == 1 ? String.valueOf(row.get(row.getColumnNames().get(0)))
                    : line.toString());
        });
        return values.isEmpty() ? "(no rows)" : values.get(0);
    }

    static double median(DuckDBConnection connection, String sql) {
        for (int i = 0; i < 3; i++) {
            Sql.queryLong(connection, "SELECT CAST((" + inner(sql) + ") AS BIGINT)", List.of());
        }
        long[] timings = new long[7];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            Sql.queryLong(connection, "SELECT CAST((" + inner(sql) + ") AS BIGINT)", List.of());
            timings[i] = System.nanoTime() - started;
        }
        Arrays.sort(timings);
        return timings[timings.length / 2] / 1e6;
    }

    /** "SELECT x FROM t" as "SELECT x FROM t", wrapped so it can be cast in one expression. */
    static String inner(String sql) {
        return sql;
    }

    static void print(DuckDBConnection connection, String sql) throws Exception {
        List<String> lines = new ArrayList<>();
        Rows.of(connection.duplicate(), sql).forEachRow((SqlRow row) -> {
            StringBuilder line = new StringBuilder("   ");
            for (String column : row.getColumnNames()) {
                line.append(String.format("%-16s", column + "=" + row.get(column)));
            }
            lines.add(line.toString());
        });
        lines.forEach(System.out::println);
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * A Java lookup, called from GROUP BY: region_of(String) -> String
 * -----------------------------------
 *    region=AMER     sales=444444    revenue=226443552.00
 *    region=APAC     sales=444444    revenue=226444884.00
 *    region=EMEA     sales=666668    revenue=339666680.00
 *    region=LATAM    sales=222222    revenue=113222109.00
 *    region=OTHER    sales=222222    revenue=113222775.00
 *
 * One, two and three arguments
 * ----------------------------
 *    with_tax(100.0, 0.2)        = 120.0
 *    clamp(150.0, 0.0, 100.0)    = 100.0
 *
 * Nulls
 * -----
 *    register:         shout(NULL)           = null   (the method was called 0 times)
 *    registerNullable: shout_or_unknown(NULL) = UNKNOWN
 *    a method may return null: even_only(5)   = null
 *
 * What a call into Java costs, summing price * 1.2 over 2,000,000 rows
 * --------------------------------------------------------------------
 *    built-in SQL expression               0.9 ms    0.5 ns/row    1.0x
 *    Udfs.registerDouble (no boxing)      14.1 ms    7.1 ns/row   15.0x
 *    Udfs.register (boxed)                24.1 ms   12.0 ns/row   25.6x
 *
 * And they agree:
 *    in_sql=1222800000.00  primitive=1222800000.00  boxed=1222800000.00
 *
 * What cannot cross
 * -----------------
 *    A function cannot take a UUID: DuckDB's Java vectors have no accessor for it in 1.5.5 - use a String instead
 *
 * The wrapper costs nothing over the raw callback API: the numbers here are the same as
 * CoreScalarFunction's, which writes the callbacks by hand - 6-7 ns a row unboxed, 10-12 boxed.
 *
 * What that buys: a call into Java is fifteen to twenty-five times a built-in SQL expression, so a
 * UDF is for what SQL cannot say - a lookup table that lives in Java, a rule nobody wants to maintain
 * twice - rather than for arithmetic. region_of above is the shape that pays for itself; vat_boxed is
 * the shape that does not.
 */
