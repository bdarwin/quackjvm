package io.quackjvm.core.live;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LiveTablesTest {

    public record Car(int carId, String make, double price, boolean sold) {
    }

    public enum Grade { LOW, HIGH }

    public record Everything(String text, Character letter, Boolean flag, Byte tiny, Short small, Integer number,
                             Long big, Float single, Double twice, BigInteger huge, BigDecimal exact, Grade grade,
                             LocalDate day, LocalDateTime moment, Instant instant) {
    }

    private DuckDBConnection connection;
    private List<Car> cars;
    private ColumnarLayout<Car> layout;

    @Before
    public void open() throws Exception {
        connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        cars = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            cars.add(new Car(i, "make-" + (i % 4), 5000.0 + i, i % 3 == 0));
        }
        layout = ColumnarLayout.ofRecord(Car.class);
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private Rows query(String sql) throws Exception {
        return Rows.of(connection.duplicate(), sql);
    }

    @Test
    public void aListIsQueryableAsATable() throws Exception {
        try (LiveTable table = LiveTables.register(connection, "car", cars, layout)) {
            assertEquals("car", table.getName());
            assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
            assertEquals(4, Sql.queryLong(connection, "SELECT count(DISTINCT make) FROM car", List.of()));
            assertEquals(List.of("make-0", "make-1", "make-2", "make-3"),
                    query("SELECT DISTINCT make FROM car ORDER BY 1").list(String.class));
        }
    }

    @Test
    public void everyColumnComesBackWithTheRightTypeAndValue() throws Exception {
        try (LiveTable ignored = LiveTables.register(connection, "car", cars, layout)) {
            List<Car> back = query("SELECT carId, make, price, sold FROM car WHERE carId = 7").records(Car.class);

            assertEquals(1, back.size());
            assertEquals(cars.get(7), back.get(0));
        }
    }

    @Test
    public void itIsLiveRatherThanACopy() throws Exception {
        List<Car> living = new CopyOnWriteArrayList<>(cars);
        try (LiveTable ignored = LiveTables.register(connection, "car", living, layout)) {
            assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));

            living.add(new Car(1000, "added-later", 1.0, false));

            assertEquals(401, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
            assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM car WHERE make = 'added-later'",
                    List.of()));
        }
    }

    @Test
    public void aSnapshotDoesNotChangeUnderneath() throws Exception {
        List<Car> living = new CopyOnWriteArrayList<>(cars);
        try (LiveTable ignored = LiveTables.snapshot(connection, "car", living, layout)) {
            living.add(new Car(1000, "added-later", 1.0, false));

            assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
        }
    }

    @Test
    public void aSupplierCanHandOverWhateverIsCurrent() throws Exception {
        List<List<Car>> current = new ArrayList<>();
        current.add(cars.subList(0, 10));
        try (LiveTable ignored = LiveTables.register(connection, "car", () -> current.get(0), layout)) {
            assertEquals(10, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));

            current.set(0, cars);

            assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
        }
    }

    @Test
    public void itJoinsAgainstAStoredTable() throws Exception {
        Sql.execute(connection, "CREATE TABLE sale (saleId INTEGER, carId INTEGER, amount DOUBLE)");
        Sql.execute(connection, "INSERT INTO sale SELECT i, i % 400, 100.0 + (i % 50) FROM range(20000) t(i)");

        try (LiveTable ignored = LiveTables.register(connection, "car", cars, layout)) {
            List<String> rows = new ArrayList<>();
            query("SELECT c.make, count(*) AS n, round(sum(s.amount)) AS total FROM car c"
                    + " JOIN sale s ON s.carId = c.carId GROUP BY 1 ORDER BY 1")
                    .forEachRow(row -> rows.add(row.get("make") + " " + row.get("n") + " " + row.get("total")));

            assertEquals(4, rows.size());
            assertEquals(20000, Sql.queryLong(connection, "SELECT count(*) FROM car c JOIN sale s"
                    + " ON s.carId = c.carId", List.of()));
        }
    }

    @Test
    public void onlyTheColumnsAskedForAreReadFromTheObjects() throws Exception {
        AtomicInteger priceReads = new AtomicInteger();
        ColumnarLayout<Car> counting = ColumnarLayout.builder(Car.class)
                .column("carId", Integer.class, Car::carId)
                .column("make", String.class, Car::make)
                .column("price", Double.class, car -> {
                    priceReads.incrementAndGet();
                    return car.price();
                })
                .rowFactory(values -> new Car((Integer) values[0], (String) values[1], (Double) values[2], false))
                .build();

        try (LiveTable ignored = LiveTables.register(connection, "car", cars, counting)) {
            Sql.queryLong(connection, "SELECT count(DISTINCT make) FROM car", List.of());

            assertEquals("price was read although the query never asked for it", 0, priceReads.get());

            Sql.queryLong(connection, "SELECT count(*) FROM car WHERE price > 5000", List.of());
            assertTrue("price should have been read now", priceReads.get() > 0);
        }
    }

    @Test
    public void unregisteringTakesTheTableAway() throws Exception {
        LiveTable table = LiveTables.register(connection, "car", cars, layout);
        assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));

        table.unregister();

        assertTrue(!table.isOpen());
        try {
            Sql.queryLong(connection, "SELECT count(*) FROM car", List.of());
            fail("expected the table to be gone");
        }
        catch (RuntimeException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains("car"));
        }
    }

    @Test
    public void unregisteringTwiceIsHarmless() {
        LiveTable table = LiveTables.register(connection, "car", cars, layout);
        table.unregister();
        table.close();
    }

    @Test
    public void registeringTheSameNameAgainReplacesWhatItReads() throws Exception {
        LiveTables.register(connection, "car", cars.subList(0, 5), layout);
        assertEquals(5, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));

        LiveTables.register(connection, "car", cars, layout);

        assertEquals(400, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
    }

    @Test
    public void anEmptyCollectionIsAnEmptyTable() throws Exception {
        try (LiveTable ignored = LiveTables.register(connection, "car", new ArrayList<Car>(), layout)) {
            assertEquals(0, Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
        }
    }

    @Test
    public void nullsComeBackAsNulls() throws Exception {
        record Maybe(Integer id, String text) {
        }
        List<Maybe> rows = List.of(new Maybe(1, "here"), new Maybe(2, null));
        try (LiveTable ignored = LiveTables.register(connection, "maybe", rows,
                ColumnarLayout.ofRecord(Maybe.class))) {
            assertEquals(1, Sql.queryLong(connection, "SELECT count(*) FROM maybe WHERE text IS NULL", List.of()));
        }
    }

    @Test
    public void everyTypeThatCanBeWrittenRoundTrips() throws Exception {
        Everything one = new Everything("text", 'x', true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.5,
                BigInteger.valueOf(70), new BigDecimal("8.25"), Grade.HIGH,
                LocalDate.of(2026, 9, 30), LocalDateTime.of(2026, 9, 30, 12, 30), Instant.parse("2026-09-30T09:00:00Z"));
        try (LiveTable ignored = LiveTables.register(connection, "everything", List.of(one),
                ColumnarLayout.ofRecord(Everything.class))) {
            List<Everything> back = query("SELECT * FROM everything").records(Everything.class);

            assertEquals(1, back.size());
            // A BigDecimal comes back at the scale quackjvm stores decimals at, exactly as it would
            // from a table: DECIMAL(38,10).
            assertEquals(one.exact().setScale(io.quackjvm.core.duckdb.DuckDBTypes.DECIMAL_SCALE),
                    back.get(0).exact());
            assertEquals(one, new Everything(back.get(0).text(), back.get(0).letter(), back.get(0).flag(),
                    back.get(0).tiny(), back.get(0).small(), back.get(0).number(), back.get(0).big(),
                    back.get(0).single(), back.get(0).twice(), back.get(0).huge(), one.exact(),
                    back.get(0).grade(), back.get(0).day(), back.get(0).moment(), back.get(0).instant()));
        }
    }

    @Test
    public void theThreeTypesThatCannotBeWrittenAreRefusedWithAWayForward() {
        record WithUuid(int id, UUID uuid) {
        }
        record WithBlob(int id, byte[] data) {
        }
        record WithTime(int id, LocalTime at) {
        }
        for (Class<?> type : List.of(WithUuid.class, WithBlob.class, WithTime.class)) {
            try {
                LiveTables.register(connection, "bad", List.of(),
                        ColumnarLayout.reflective((Class<Object>) type));
                fail("expected " + type.getSimpleName() + " to be refused");
            }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("1.5.5"));
                assertTrue(expected.getMessage(), expected.getMessage().contains("map it to String")
                        || expected.getMessage().contains("map it to String, or"));
            }
        }
    }

    @Test
    public void aBadNameIsRefused() {
        for (String name : List.of("has space", "1starts-with-a-digit", "semi;colon", "")) {
            try {
                LiveTables.register(connection, name, cars, layout);
                fail("expected '" + name + "' to be refused");
            }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("letters, digits"));
            }
        }
    }

    @Test
    public void itSaysHowManyRowsItHasBeenAskedFor() throws Exception {
        try (LiveTable table = LiveTables.register(connection, "car", cars, layout)) {
            Sql.queryLong(connection, "SELECT count(*) FROM car", List.of());
            long afterOne = table.getRowsRead();
            Sql.queryLong(connection, "SELECT count(*) FROM car", List.of());

            assertEquals(400, afterOne);
            assertEquals(800, table.getRowsRead());
        }
    }

    @Test
    public void theCatalogSeesIt() throws Exception {
        try (LiveTable ignored = LiveTables.register(connection, "car", cars, layout)) {
            io.quackjvm.core.catalog.TableInfo info = io.quackjvm.core.catalog.Catalog.of(connection).describe("car");

            assertEquals(List.of("carId", "make", "price", "sold"),
                    info.columns().stream().map(io.quackjvm.core.catalog.ColumnInfo::name).toList());
        }
    }

    @Test
    public void howItComparesWithLoadingTheDataIn() throws Exception {
        // The comparison the brief asks for: reading objects where they lie against copying them into
        // a table first. Printed, because it is a measurement.
        for (int count : new int[] {100_000, 1_000_000}) {
            List<Car> many = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                many.add(new Car(i, "make-" + (i % 97), 5000.0 + (i % 900), i % 3 == 0));
            }
            try (DuckDBConnection fresh = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                Sql.execute(fresh, "CREATE TABLE sale (saleId INTEGER, carId INTEGER, amount DOUBLE)");
                Sql.execute(fresh, "INSERT INTO sale SELECT i, i % " + count + ", 100.0 + (i % 50)"
                        + " FROM range(200000) t(i)");

                LiveTables.register(fresh, "car_live", many, layout);
                long liveAggregate = time(() -> Sql.queryLong(fresh,
                        "SELECT count(DISTINCT make) FROM car_live", List.of()));
                long liveJoin = time(() -> Sql.queryLong(fresh, "SELECT count(*) FROM car_live c"
                        + " JOIN sale s ON s.carId = c.carId", List.of()));

                io.quackjvm.core.duckdb.TableWriter writer = new io.quackjvm.core.duckdb.TableWriter(
                        "car_stored", layout.toColumnDefs(), false, 16);
                writer.createTable(fresh, true);
                long load = System.nanoTime();
                try (var appender = fresh.createAppender("main", "car_stored")) {
                    for (Car car : many) {
                        appender.beginRow();
                        appender.append(car.carId());
                        appender.append(car.make());
                        appender.append(car.price());
                        appender.append(car.sold());
                        appender.endRow();
                    }
                    appender.flush();
                }
                long loadMs = (System.nanoTime() - load) / 1_000_000;
                long storedAggregate = time(() -> Sql.queryLong(fresh,
                        "SELECT count(DISTINCT make) FROM car_stored", List.of()));
                long storedJoin = time(() -> Sql.queryLong(fresh, "SELECT count(*) FROM car_stored c"
                        + " JOIN sale s ON s.carId = c.carId", List.of()));

                System.out.printf("%,10d objects | live: aggregate %5.1f ms, join %5.1f ms"
                                + " | stored: load %4d ms, aggregate %5.1f ms, join %5.1f ms%n",
                        count, liveAggregate / 1e6, liveJoin / 1e6, loadMs, storedAggregate / 1e6,
                        storedJoin / 1e6);
            }
        }
    }

    private static long time(Runnable work) {
        work.run();
        long[] timings = new long[5];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            work.run();
            timings[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(timings);
        return timings[timings.length / 2];
    }
}
