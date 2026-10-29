package io.quackjvm.core.catalog;

import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CatalogTest {

    public record Car(int carId, String make, double price) {
    }

    private DuckDBConnection connection;
    private Catalog catalog;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        Sql.execute(connection, "CREATE TABLE car (carId INTEGER PRIMARY KEY, make VARCHAR NOT NULL, price DOUBLE)");
        Sql.execute(connection, "INSERT INTO car SELECT i, 'make-' || (i % 3), 5000.0 + i FROM range(1000) t(i)");
        catalog = Catalog.of(connection);
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    @Test
    public void theTablesComeBackWithTheirColumns() {
        List<TableInfo> tables = catalog.tables();

        assertEquals(1, tables.size());
        TableInfo car = tables.get(0);
        assertEquals("car", car.name());
        assertEquals(List.of("carId", "make", "price"),
                car.columns().stream().map(ColumnInfo::name).toList());
        assertEquals("INTEGER", car.column("carId").sqlType());
        assertEquals(Integer.class, car.column("carId").javaType());
        assertEquals(Double.class, car.column("price").javaType());
        assertEquals(String.class, car.column("make").javaType());
    }

    @Test
    public void nullabilityComesFromTheDatabase() {
        TableInfo car = catalog.describe("car");

        assertTrue(!car.column("carId").nullable());
        assertTrue(!car.column("make").nullable());
        assertTrue(car.column("price").nullable());
    }

    @Test
    public void theRowEstimateIsThere() {
        assertEquals(1000, catalog.describe("car").rows());
    }

    @Test
    public void aDescriptionAndAUnitAreStoredInTheDatabaseAndReadBack() {
        catalog.describeTable("car", "cars we have sold");
        catalog.describeColumn("car", "price", "what it sold for", "USD");

        TableInfo car = catalog.describe("car");
        assertEquals("cars we have sold", car.description());
        assertEquals("what it sold for", car.column("price").description());
        assertEquals("USD", car.column("price").unit());

        // And they are readable with plain SQL, by anything at all.
        assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM duckdb_tables()"
                + " WHERE table_name = 'car' AND comment = 'cars we have sold'", List.of()));
    }

    @Test
    public void aDescriptionWithoutAUnitIsStoredAsPlainText() {
        catalog.describeColumn("car", "make", "who made it", null);

        assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM duckdb_columns()"
                + " WHERE table_name = 'car' AND column_name = 'make' AND comment = 'who made it'", List.of()));
        assertEquals("who made it", catalog.describe("car").column("make").description());
        assertNull(catalog.describe("car").column("make").unit());
    }

    @Test
    public void aCommentWrittenByHandIsReadAsADescription() {
        Sql.execute(connection, "COMMENT ON COLUMN car.make IS 'written by a migration'");

        assertEquals("written by a migration", catalog.describe("car").column("make").description());
    }

    @Test
    public void aCommentThatLooksLikeJsonButIsNotIsStillADescription() {
        Sql.execute(connection, "COMMENT ON COLUMN car.make IS '{not json at all'");

        assertEquals("{not json at all", catalog.describe("car").column("make").description());
    }

    @Test
    public void quotesInADescriptionSurvive() {
        catalog.describeColumn("car", "make", "it's the maker's \"name\"", "n/a");

        ColumnInfo make = catalog.describe("car").column("make");
        assertEquals("it's the maker's \"name\"", make.description());
        assertEquals("n/a", make.unit());
    }

    @Test
    public void descriptionsSurviveInAFileDatabase() throws Exception {
        Path directory = Files.createTempDirectory("catalog");
        Path file = directory.resolve("catalog.duckdb");
        try {
            try (Connection first = DriverManager.getConnection("jdbc:duckdb:" + file)) {
                Sql.execute(first, "CREATE TABLE t (a INTEGER)");
                Catalog.of(first).describeColumn("t", "a", "how many", "items");
            }
            try (Connection second = DriverManager.getConnection("jdbc:duckdb:" + file)) {
                ColumnInfo column = Catalog.of(second).describe("t").column("a");
                assertEquals("how many", column.description());
                assertEquals("items", column.unit());
            }
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    public void aLayoutCanCarryWhatEachColumnMeans() {
        ColumnarLayout<Car> layout = ColumnarLayout.builder(Car.class)
                .describingTable("cars we have sold")
                .column("carId", Integer.class, Car::carId).describing("our own id")
                .column("make", String.class, Car::make).describing("who made it")
                .column("price", Double.class, Car::price).describing("what it sold for").unit("USD")
                .rowFactory(values -> new Car((Integer) values[0], (String) values[1], (Double) values[2]))
                .build();

        assertEquals(4, catalog.apply("car", layout));

        TableInfo car = catalog.describe("car", layout);
        assertEquals("cars we have sold", car.description());
        assertEquals("our own id", car.column("carId").description());
        assertEquals("USD", car.column("price").unit());
        assertEquals(Integer.class, car.column("carId").javaType());
    }

    @Test
    public void describingBeforeAColumnIsAdedIsRefused() {
        try {
            ColumnarLayout.builder(Car.class).describing("nothing yet");
            fail("expected a complaint");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("add a column first"));
        }
    }

    @Test
    public void createTableWritesWhatTheColumnsMean() {
        TableWriter writer = new TableWriter("part", List.of(
                new ColumnDef("partId", Integer.class).describedAs("our own id"),
                new ColumnDef("weight", Double.class).describedAs("as weighed on arrival").measuredIn("kg"),
                new ColumnDef("name", String.class)), false, 16);
        writer.createTable(connection, true);

        TableInfo part = catalog.describe("part");
        assertEquals("our own id", part.column("partId").description());
        assertEquals("kg", part.column("weight").unit());
        assertNull(part.column("name").description());
    }

    @Test
    public void aTableThatIsNotThereSaysWhatIs() {
        try {
            catalog.describe("nope");
            fail("expected a complaint");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("There is: car"));
        }
    }

    @Test
    public void profileSaysWhatIsActuallyInTheTable() {
        Profile profile = catalog.profile("car", 3);

        assertEquals(1000, profile.rows());
        assertEquals(3, profile.columns().size());
        ColumnProfile price = profile.columns().stream()
                .filter(column -> column.column().equals("price")).findFirst().orElseThrow();
        assertEquals("5000.0", price.min());
        assertEquals("5999.0", price.max());
        assertEquals(Double.valueOf(0.0), price.nullPercentage());
        assertEquals(3, profile.sample().size());
        assertEquals(3, profile.sample().get(0).length);
    }

    @Test
    public void profileCountsNullsAndDistinctValues() {
        Sql.execute(connection, "UPDATE car SET price = NULL WHERE carId % 4 = 0");

        Profile profile = catalog.profile("car", 0);
        ColumnProfile price = profile.columns().stream()
                .filter(column -> column.column().equals("price")).findFirst().orElseThrow();
        ColumnProfile make = profile.columns().stream()
                .filter(column -> column.column().equals("make")).findFirst().orElseThrow();

        assertEquals(25.0, price.nullPercentage(), 0.5);
        assertEquals(Long.valueOf(3), make.approxUnique());
        assertTrue(profile.sample().isEmpty());
    }

    @Test
    public void whatItAllReadsAsText() {
        catalog.describeColumn("car", "price", "what it sold for", "USD");

        String text = catalog.describe("car").toText();
        assertTrue(text, text.startsWith("car (~1,000 rows)"));
        assertTrue(text, text.contains("price DOUBLE in USD - what it sold for"));

        String profile = catalog.profile("car", 1).toText();
        assertTrue(profile, profile.contains("1,000 rows"));
        assertTrue(profile, profile.contains("example rows:"));
    }

    @Test
    public void aTypeWithNoJavaEquivalentComesBackWithoutOne() {
        Sql.execute(connection, "CREATE TABLE nested (id INTEGER, tags VARCHAR[], pair STRUCT(a INTEGER, b VARCHAR))");

        TableInfo nested = catalog.describe("nested");
        assertEquals(Integer.class, nested.column("id").javaType());
        assertNull(nested.column("tags").javaType());
        assertEquals("VARCHAR[]", nested.column("tags").sqlType());
        assertNotNull(nested.column("pair").sqlType());
        assertTrue(!Catalog.isMappable("VARCHAR[]"));
        assertTrue(Catalog.isMappable("INTEGER"));
    }

    @Test
    public void whatProfileCostsAtScale() {
        // Printed rather than asserted: it is a measurement, and it is the reason profile() is not
        // something to call on every request.
        for (long rows : new long[] {1_000_000L, 10_000_000L}) {
            Sql.execute(connection, "CREATE OR REPLACE TABLE big AS SELECT i AS id, 'make-' || (i % 97) AS make,"
                    + " i * 1.5 AS price, i % 7 = 0 AS sold FROM range(" + rows + ") t(i)");
            long started = System.nanoTime();
            Profile profile = catalog.profile("big", 5);
            long took = (System.nanoTime() - started) / 1_000_000;
            assertEquals(rows, profile.rows());
            assertEquals(4, profile.columns().size());
            System.out.printf("profile of %,d rows x 4 columns: %d ms%n", rows, took);
        }
    }

    @Test
    public void describeIsCheapWhateverTheTableHolds() {
        Sql.execute(connection, "CREATE TABLE big AS SELECT i AS id, i * 1.5 AS price FROM range(5000000) t(i)");
        long[] timings = new long[11];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            catalog.describe("big");
            timings[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(timings);
        long median = timings[timings.length / 2] / 1_000;
        System.out.printf("describe of a 5,000,000-row table: %d us%n", median);
        assertTrue("describe took " + median + " us, so it is reading rows", median < 100_000);
    }
}
