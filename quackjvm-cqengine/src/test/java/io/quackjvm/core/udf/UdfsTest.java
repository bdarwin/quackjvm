package io.quackjvm.core.udf;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UdfsTest {

    public enum Grade { LOW, HIGH }

    private DuckDBConnection connection;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        Sql.execute(connection, "CREATE TABLE sale (id INTEGER, country VARCHAR, price DOUBLE)");
        Sql.execute(connection, "INSERT INTO sale SELECT i, ['FR','DE','US'][i % 3 + 1], 10.0 + (i % 100)"
                + " FROM range(1000) t(i)");
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    /** The one value a query returns, or null - scalar() treats a null as no value at all. */
    private <T> T one(String sql, Class<T> type) throws Exception {
        return Rows.of(connection.duplicate(), sql).scalarOptional(type).orElse(null);
    }

    @Test
    public void aMethodOfOneArgumentIsCallableFromSql() throws Exception {
        Udf udf = Udfs.register(connection, "shout", String.class, String.class, String::toUpperCase);

        assertEquals("shout(String) -> String", udf.toString());
        assertEquals("FR", one("SELECT shout(country) FROM sale WHERE id = 0", String.class));
        assertEquals(1000, Sql.queryLong(connection, "SELECT count(shout(country)) FROM sale", List.of()));
    }

    @Test
    public void aMethodOfTwoArguments() throws Exception {
        Udfs.register(connection, "with_tax", Double.class, Double.class, Double.class,
                (price, rate) -> price * (1 + rate));

        assertEquals(12.0, one("SELECT with_tax(10.0, 0.2)", Double.class), 1e-9);
    }

    @Test
    public void aMethodOfThreeArguments() throws Exception {
        Udfs.register(connection, "between_", Boolean.class, Double.class, Double.class, Double.class,
                (value, low, high) -> value >= low && value <= high);

        assertEquals(Boolean.TRUE, one("SELECT between_(5.0, 1.0, 10.0)", Boolean.class));
        assertEquals(Boolean.FALSE, one("SELECT between_(50.0, 1.0, 10.0)", Boolean.class));
    }

    @Test
    public void argumentsOfDifferentTypesInOneFunction() throws Exception {
        Udfs.register(connection, "describe_", String.class, String.class, Integer.class, Boolean.class,
                (text, number, flag) -> text + "/" + number + "/" + flag);

        assertEquals("a/2/true", one("SELECT describe_('a', 2, true)", String.class));
    }

    @Test
    public void aNullArgumentIsANullResultAndTheMethodIsNotCalled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Udfs.register(connection, "shout", String.class, String.class, text -> {
            calls.incrementAndGet();
            return text.toUpperCase();
        });

        assertNull(one("SELECT shout(NULL::VARCHAR)", String.class));
        assertEquals(0, calls.get());
        assertEquals("A", one("SELECT shout('a')", String.class));
        assertEquals(1, calls.get());
    }

    @Test
    public void aNullableFunctionSeesTheNullAndCanAnswerIt() throws Exception {
        Udfs.registerNullable(connection, "or_unknown", String.class, String.class,
                text -> text == null ? "unknown" : text);

        assertEquals("unknown", one("SELECT or_unknown(NULL::VARCHAR)", String.class));
        assertEquals("here", one("SELECT or_unknown('here')", String.class));
    }

    @Test
    public void aFunctionMayReturnNull() throws Exception {
        Udfs.register(connection, "even_only", Integer.class, Integer.class,
                number -> number % 2 == 0 ? number : null);

        assertEquals(Integer.valueOf(4), one("SELECT even_only(4)", Integer.class));
        assertNull(one("SELECT even_only(5)", Integer.class));
    }

    @Test
    public void aNullInAnyArgumentIsANullResult() throws Exception {
        Udfs.register(connection, "add_", Double.class, Double.class, Double.class, Double::sum);

        assertNull(one("SELECT add_(1.0, NULL::DOUBLE)", Double.class));
        assertNull(one("SELECT add_(NULL::DOUBLE, 1.0)", Double.class));
        assertEquals(3.0, one("SELECT add_(1.0, 2.0)", Double.class), 1e-9);
    }

    @Test
    public void everyTypeThatCanCrossTheBoundary() throws Exception {
        Udfs.register(connection, "id_string", String.class, String.class, value -> value);
        Udfs.register(connection, "id_bool", Boolean.class, Boolean.class, value -> value);
        Udfs.register(connection, "id_byte", Byte.class, Byte.class, value -> value);
        Udfs.register(connection, "id_short", Short.class, Short.class, value -> value);
        Udfs.register(connection, "id_int", Integer.class, Integer.class, value -> value);
        Udfs.register(connection, "id_long", Long.class, Long.class, value -> value);
        Udfs.register(connection, "id_float", Float.class, Float.class, value -> value);
        Udfs.register(connection, "id_double", Double.class, Double.class, value -> value);
        Udfs.register(connection, "id_bigint", BigInteger.class, BigInteger.class, value -> value);
        Udfs.register(connection, "id_decimal", BigDecimal.class, BigDecimal.class, value -> value);
        Udfs.register(connection, "id_date", LocalDate.class, LocalDate.class, value -> value);
        Udfs.register(connection, "id_moment", LocalDateTime.class, LocalDateTime.class, value -> value);
        Udfs.register(connection, "id_char", Character.class, Character.class, value -> value);

        assertEquals("a", one("SELECT id_string('a')", String.class));
        assertEquals(Boolean.TRUE, one("SELECT id_bool(true)", Boolean.class));
        assertEquals(Byte.valueOf((byte) 1), one("SELECT id_byte(1::TINYINT)", Byte.class));
        assertEquals(Short.valueOf((short) 2), one("SELECT id_short(2::SMALLINT)", Short.class));
        assertEquals(Integer.valueOf(3), one("SELECT id_int(3)", Integer.class));
        assertEquals(Long.valueOf(4), one("SELECT id_long(4::BIGINT)", Long.class));
        assertEquals(5.5f, one("SELECT id_float(5.5::FLOAT)", Float.class), 1e-6);
        assertEquals(6.5, one("SELECT id_double(6.5)", Double.class), 1e-9);
        assertEquals(BigInteger.valueOf(70), one("SELECT id_bigint(70::HUGEINT)", BigInteger.class));
        assertEquals(0, new BigDecimal("8.25").compareTo(one("SELECT id_decimal(8.25)", BigDecimal.class)));
        assertEquals(LocalDate.of(2026, 9, 30), one("SELECT id_date(DATE '2026-09-30')", LocalDate.class));
        assertEquals(LocalDateTime.of(2026, 9, 30, 12, 0),
                one("SELECT id_moment(TIMESTAMP '2026-09-30 12:00')", LocalDateTime.class));
        assertEquals("x", one("SELECT id_char('x')", String.class));
    }

    @Test
    public void anEnumCrossesAsItsOrdinal() throws Exception {
        Udfs.register(connection, "grade_of", Grade.class, Double.class,
                price -> price > 50 ? Grade.HIGH : Grade.LOW);

        assertEquals(Integer.valueOf(Grade.HIGH.ordinal()), one("SELECT grade_of(80.0)", Integer.class));
        assertEquals(Integer.valueOf(Grade.LOW.ordinal()), one("SELECT grade_of(10.0)", Integer.class));
    }

    @Test
    public void theTypesDuckDbCannotCarryAreRefusedWhenTheFunctionIsRegistered() {
        try {
            Udfs.register(connection, "bad", String.class, UUID.class, Object::toString);
            fail("expected UUID to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot take a UUID"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("1.5.5"));
        }
        try {
            Udfs.register(connection, "bad", byte[].class, String.class, String::getBytes);
            fail("expected byte[] to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot return a byte[]"));
        }
    }

    @Test
    public void aBadNameIsRefused() {
        try {
            Udfs.register(connection, "has space", String.class, String.class, value -> value);
            fail("expected the name to be refused");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("letters, digits"));
        }
    }

    @Test
    public void anExceptionInTheMethodStopsTheQuery() {
        Udfs.register(connection, "explode", String.class, String.class, value -> {
            throw new IllegalStateException("no");
        });
        try {
            Sql.queryLong(connection, "SELECT count(explode(country)) FROM sale", List.of());
            fail("expected the query to fail");
        }
        catch (RuntimeException expected) {
            Throwable root = expected;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(String.valueOf(root.getMessage()), String.valueOf(root.getMessage()).contains("no"));
        }
    }

    @Test
    public void aFunctionIsCallableFromEveryPartOfAQuery() throws Exception {
        Udfs.register(connection, "region_of", String.class, String.class,
                country -> country.equals("US") ? "AMER" : "EMEA");

        assertEquals(2, Sql.queryLong(connection, "SELECT count(DISTINCT region_of(country)) FROM sale", List.of()));
        assertEquals(333, Sql.queryLong(connection, "SELECT count(*) FROM sale WHERE region_of(country) = 'AMER'",
                List.of()));
        assertEquals("AMER", one("SELECT region_of(country) FROM sale GROUP BY 1 ORDER BY 1 LIMIT 1", String.class));
    }

    @Test
    public void theSameNameRegisteredAgainReplacesIt() throws Exception {
        Udfs.register(connection, "twice", Integer.class, Integer.class, number -> number * 2);
        assertEquals(Integer.valueOf(4), one("SELECT twice(2)", Integer.class));

        Udfs.register(connection, "twice", Integer.class, Integer.class, number -> number * 20);

        assertEquals(Integer.valueOf(40), one("SELECT twice(2)", Integer.class));
    }

    @Test
    public void whatACallIntoJavaCosts() throws Exception {
        Sql.execute(connection, "CREATE TABLE big AS SELECT CAST(10.0 + (i % 1000) AS DOUBLE) AS price"
                + " FROM range(2000000) t(i)");
        Udfs.registerDouble(connection, "vat_primitive", (double price) -> price * 1.2);
        Udfs.register(connection, "vat_boxed", Double.class, Double.class, price -> price * 1.2);

        double inSql = median("SELECT sum(price * 1.2) FROM big");
        double primitive = median("SELECT sum(vat_primitive(price)) FROM big");
        double boxed = median("SELECT sum(vat_boxed(price)) FROM big");

        System.out.printf("2,000,000 rows of price * 1.2:%n");
        System.out.printf("   in SQL                       %6.1f ms  %5.1f ns/row%n", inSql, inSql * 1e6 / 2e6);
        System.out.printf("   Udfs.registerDouble          %6.1f ms  %5.1f ns/row%n", primitive,
                primitive * 1e6 / 2e6);
        System.out.printf("   Udfs.register (boxed)        %6.1f ms  %5.1f ns/row%n", boxed, boxed * 1e6 / 2e6);

        // All three agree, which is the point of measuring them together.
        assertEquals(one("SELECT sum(price * 1.2) FROM big", Double.class),
                one("SELECT sum(vat_primitive(price)) FROM big", Double.class), 1.0);
        assertEquals(one("SELECT sum(price * 1.2) FROM big", Double.class),
                one("SELECT sum(vat_boxed(price)) FROM big", Double.class), 1.0);
    }

    private double median(String sql) {
        Sql.queryLong(connection, "SELECT CAST(" + sql.substring(7, sql.indexOf(" FROM")) + " AS BIGINT)"
                + sql.substring(sql.indexOf(" FROM")), List.of());
        long[] timings = new long[7];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            Sql.queryLong(connection, "SELECT CAST(" + sql.substring(7, sql.indexOf(" FROM")) + " AS BIGINT)"
                    + sql.substring(sql.indexOf(" FROM")), List.of());
            timings[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(timings);
        return timings[timings.length / 2] / 1e6;
    }
}
