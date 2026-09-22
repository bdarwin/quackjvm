package io.quackjvm.core.measure;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MeasureTableTest {

    private DuckDBConnection connection;
    private MeasureTable measures;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        measures = MeasureTable.named("m").fields("a", "b", "point", "unit").unit("unit").build();
        measures.create(connection);
        measures.replace(connection, measures.batch()
                .record(1)
                    .put(1.0, "x", "y", "5y", "U1")
                    .put(2.0, "x", "y", "10y", "U1")
                    .put(4.0, "x", "z", "5y", "U1")
                    .put(10.0, "x", "y", "5y", "U2")
                .record(2)
                    .put(0.5, "x", "y", "5y", "U1")
                .build());
        Sql.execute(connection, "CREATE TABLE rate (unit VARCHAR, to_unit VARCHAR, factor DOUBLE)");
        Sql.execute(connection, "INSERT INTO rate VALUES ('U1', 'U1', 1.0), ('U2', 'U1', 2.0)");
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    /** Every row as "column=value|...", so a whole answer can be compared in one assert. */
    private List<String> read(MeasureQuery query) throws Exception {
        List<String> rows = new ArrayList<>();
        Rows result = query.run(connection.duplicate());
        result.forEachRow((SqlRow row) -> {
            StringBuilder text = new StringBuilder();
            for (String column : row.getColumnNames()) {
                Object value = row.get(column);
                text.append(text.length() == 0 ? "" : " | ").append(column).append('=')
                        .append(value instanceof Double d ? String.valueOf(d) : String.valueOf(value));
            }
            rows.add(text.toString());
        });
        return rows;
    }

    @Test
    public void thePointsBecomeColumns() throws Exception {
        assertEquals(List.of(
                        "a=x | b=y | 10y=2.0 | 5y=1.5",
                        "a=x | b=z | 10y=null | 5y=4.0"),
                read(measures.query().rows("a", "b").columns("point").where("unit", "U1")));
    }

    @Test
    public void anyOtherFieldCanBecomeTheColumnsInstead() throws Exception {
        assertEquals(List.of(
                        "point=10y | y=2.0 | z=null",
                        "point=5y | y=1.5 | z=4.0"),
                read(measures.query().rows("point").columns("b").where("unit", "U1")));
    }

    @Test
    public void groupingWithoutColumnsGivesOneTotal() throws Exception {
        assertEquals(List.of("a=x | total=7.5"), read(measures.query().rows("a").where("unit", "U1")));
    }

    @Test
    public void oneRecordOnly() throws Exception {
        assertEquals(List.of("b=y | 10y=2.0 | 5y=1.0", "b=z | 10y=null | 5y=4.0"),
                read(measures.query().records(1).rows("b").columns("point").where("unit", "U1")));
    }

    @Test
    public void filtersKeepOnlyWhatTheyName() throws Exception {
        assertEquals(List.of("b=y | total=3.5"), read(measures.query().rows("b").where("unit", "U1").where("b", "y")));
        assertEquals(List.of(), read(measures.query().rows("b").where("unit", "U1").where("b", "nothing")));
    }

    @Test
    public void theUnitCanBeTheRowsOrTheColumns() throws Exception {
        assertEquals(List.of("unit=U1 | total=7.5", "unit=U2 | total=10.0"), read(measures.query().rows("unit")));
        assertEquals(List.of("a=x | U1=7.5 | U2=10.0"), read(measures.query().rows("a").columns("unit")));
    }

    /** A total across two units is a wrong number that looks right, so it has to be refused. */
    @Test
    public void addingUpTwoUnitsIsRefused() throws Exception {
        try {
            measures.query().rows("a").columns("point").run(connection.duplicate());
            fail("expected a refusal");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("different units"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("convertTo"));
        }
    }

    @Test
    public void convertingLetsThemBeAddedUp() throws Exception {
        // (x, y, 5y): 1.0 + 0.5 in U1, and 10.0 in U2, which is 20.0 in U1.
        assertEquals(List.of(
                        "a=x | b=y | 10y=2.0 | 5y=21.5",
                        "a=x | b=z | 10y=null | 5y=4.0"),
                read(measures.query().rows("a", "b").columns("point").convertTo("U1", "rate")));
    }

    @Test
    public void aUnitWithNoFactorIsLeftOutRatherThanCountedWrong() throws Exception {
        Sql.execute(connection, "DELETE FROM rate WHERE unit = 'U2'");
        assertEquals(List.of("a=x | b=y | 10y=2.0 | 5y=1.5", "a=x | b=z | 10y=null | 5y=4.0"),
                read(measures.query().rows("a", "b").columns("point").convertTo("U1", "rate")));
    }

    @Test
    public void appendingAddsToWhatARecordHas() throws Exception {
        // Record 2 had 0.5, and keeps it.
        measures.append(connection, measures.batch().record(2).put(3.0, "x", "y", "30y", "U1").build());
        assertEquals(List.of("b=y | total=3.5"), read(measures.query().records(2).rows("b").where("unit", "U1")));
    }

    @Test
    public void replacingLeavesARecordWithExactlyTheNewValues() throws Exception {
        measures.replace(connection, measures.batch().record(1).put(9.0, "x", "y", "5y", "U1").build());
        assertEquals(List.of("b=y | total=9.5"), read(measures.query().rows("b").where("unit", "U1")));
    }

    @Test
    public void replacingWithNothingRemovesARecord() throws Exception {
        measures.replace(connection, measures.batch().record(1).build());
        assertEquals(List.of("a=x | total=0.5"), read(measures.query().rows("a").where("unit", "U1")));
    }

    @Test
    public void everyKeyIsStoredOnce() {
        long keys = measures.keyCount(connection);
        measures.append(connection, measures.batch().record(3).put(1.0, "x", "y", "5y", "U1").build());
        assertEquals(keys, measures.keyCount(connection));
        assertEquals(6, measures.valueCount(connection));
        assertEquals(3, measures.recordCount(connection));
    }

    @Test
    public void aKeyNeedsOnePartPerField() {
        try {
            measures.batch().record(1).put(1.0, "x", "y");
            fail("expected a complaint");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("takes 4 parts, not 2"));
        }
    }

    @Test
    public void oneRecordCannotHaveTheSameKeyTwice() {
        try {
            measures.batch().record(1).put(1.0, "x", "y", "5y", "U1").put(2.0, "x", "y", "5y", "U1");
            fail("expected a complaint");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already has a value"));
        }
    }

    /** Text order puts 10y before 1d, which is why a field's values can be given an order. */
    @Test
    public void adeclaredOrderDecidesTheColumnsAndTheRows() throws Exception {
        MeasureTable ordered = MeasureTable.named("ordered").fields("point", "b")
                .order("point", "5y", "10y", "30y").build();
        ordered.create(connection);
        ordered.replace(connection, ordered.batch().record(1)
                .put(1.0, "5y", "y").put(2.0, "10y", "y").put(3.0, "30y", "y").put(4.0, "unnamed", "y").build());

        assertEquals(List.of("b=y | 5y=1.0 | 10y=2.0 | 30y=3.0 | unnamed=4.0"),
                read(ordered.query().rows("b").columns("point")));
        assertEquals(List.of("point=5y | total=1.0", "point=10y | total=2.0", "point=30y | total=3.0",
                        "point=unnamed | total=4.0"),
                read(ordered.query().rows("point")));
    }

    @Test
    public void unknownFieldsAreRefused() {
        try {
            measures.query().rows("nope");
            fail("expected a complaint");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a field"));
        }
    }

    @Test
    public void valuesWithQuotesInThemSurviveAsColumns() throws Exception {
        MeasureTable awkward = MeasureTable.named("awkward").fields("what").build();
        awkward.create(connection);
        awkward.replace(connection, awkward.batch().record(1).put(2.0, "it's \"odd\"").build());
        assertEquals(List.of("it's \"odd\"=2.0"), read(awkward.query().columns("what")));
    }

    @Test
    public void theQueryHandsBackItsSql() {
        String sql = measures.query().rows("a").columns("point").where("unit", "U1").sql(connection);
        assertTrue(sql, sql.contains("WITH totals AS (SELECT key_id, sum(value) AS total"));
        assertTrue(sql, sql.contains("FILTER (WHERE k.\"point\" = '5y')"));
        // Totals per key first, then the dictionary: 20 ms against 227 on ten million values.
        assertTrue(sql, sql.indexOf("GROUP BY key_id") < sql.indexOf("JOIN \"m_key\""));
    }
}
