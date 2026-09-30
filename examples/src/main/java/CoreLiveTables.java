/*
 * Java objects queried as a table, with no load step and nothing copied.
 *
 * LiveTables.register hands a collection and a ColumnarLayout to DuckDB as a table function, and puts
 * a view over it, so SQL says "car" rather than "car()". The objects stay where they are: add one and
 * the next query sees it.
 *
 * What this shows: the same list queried, joined against stored rows, read live, and what it costs
 * against copying the objects into a table first.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreLiveTables.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.live.LiveTable;
import io.quackjvm.core.live.LiveTables;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class CoreLiveTables {

    public record Car(int carId, String make, double price, boolean sold) {
    }

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            // 200,000 rows that do live in DuckDB, to join against.
            Sql.execute(connection, "CREATE TABLE sale (saleId INTEGER, carId INTEGER, amount DOUBLE)");
            Sql.execute(connection, "INSERT INTO sale SELECT i, i % 1000, 100.0 + (i % 50)"
                    + " FROM range(200000) t(i)");

            List<Car> cars = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 1000; i++) {
                cars.add(new Car(i, "make-" + (i % 4), 5000.0 + (i % 900), i % 3 == 0));
            }

            ColumnarLayout<Car> layout = ColumnarLayout.ofRecord(Car.class);
            try (LiveTable car = LiveTables.register(connection, "car", cars, layout)) {

                System.out.println("An ordinary List<Car>, queried as a table");
                System.out.println("----------------------------------------");
                print(connection, "SELECT make, count(*) AS cars, round(avg(price), 1) AS avg_price"
                        + " FROM car GROUP BY 1 ORDER BY 1");

                System.out.println("\nJoined against 200,000 rows that are stored - no load step");
                System.out.println("---------------------------------------------------------");
                print(connection, "SELECT c.make, count(*) AS sales, round(sum(s.amount)) AS total"
                        + " FROM car c JOIN sale s ON s.carId = c.carId GROUP BY 1 ORDER BY 1");

                System.out.println("\nStill live: add to the list, ask again");
                System.out.println("-------------------------------------");
                System.out.printf("   before: %,d rows%n",
                        Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()));
                cars.add(new Car(9999, "added-just-now", 12345.0, false));
                System.out.printf("   after adding one object: %,d rows, and it is there: %s%n",
                        Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()),
                        Rows.of(connection.duplicate(), "SELECT make FROM car WHERE carId = 9999")
                                .scalar(String.class));
                cars.remove(cars.size() - 1);

                System.out.println("\nOnly the columns a query asks for are read from the objects");
                System.out.println("----------------------------------------------------------");
                AtomicLong priceReads = new AtomicLong();
                ColumnarLayout<Car> counting = ColumnarLayout.builder(Car.class)
                        .column("carId", Integer.class, Car::carId)
                        .column("make", String.class, Car::make)
                        .column("price", Double.class, c -> {
                            priceReads.incrementAndGet();
                            return c.price();
                        })
                        .rowFactory(values -> new Car((Integer) values[0], (String) values[1],
                                (Double) values[2], false))
                        .build();
                try (LiveTable counted = LiveTables.register(connection, "car_counted", cars, counting)) {
                    Sql.queryLong(connection, "SELECT count(DISTINCT make) FROM car_counted", List.of());
                    System.out.printf("   after a query about make only:  price read %d times%n", priceReads.get());
                    Sql.queryLong(connection, "SELECT count(*) FROM car_counted WHERE price > 5000", List.of());
                    System.out.printf("   after a query about price:      price read %d times%n", priceReads.get());
                }

                System.out.printf("%nrows handed to SQL so far: %,d%n", car.getRowsRead());
            }

            System.out.println("\nAnd afterwards the name is gone");
            System.out.println("-------------------------------");
            try {
                Sql.queryLong(connection, "SELECT count(*) FROM car", List.of());
            }
            catch (RuntimeException expected) {
                System.out.println("   " + firstLine(expected));
            }

            atScale();
        }
    }

    /** Reading objects where they lie, against copying them into a table first. */
    static void atScale() throws Exception {
        System.out.println("\nLive against loaded, on the same objects");
        System.out.println("---------------------------------------");
        System.out.printf("%12s  %-34s  %s%n", "objects", "live (no load step)", "stored (loaded first)");
        ColumnarLayout<Car> layout = ColumnarLayout.ofRecord(Car.class);
        for (int count : new int[] {100_000, 1_000_000}) {
            List<Car> many = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                many.add(new Car(i, "make-" + (i % 97), 5000.0 + (i % 900), i % 3 == 0));
            }
            try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                Sql.execute(connection, "CREATE TABLE sale (saleId INTEGER, carId INTEGER, amount DOUBLE)");
                Sql.execute(connection, "INSERT INTO sale SELECT i, i % " + count + ", 100.0 + (i % 50)"
                        + " FROM range(200000) t(i)");

                LiveTables.register(connection, "car_live", many, layout);
                double liveGroup = median(() -> Sql.queryLong(connection,
                        "SELECT count(DISTINCT make) FROM car_live", List.of()));
                double liveJoin = median(() -> Sql.queryLong(connection, "SELECT count(*) FROM car_live c"
                        + " JOIN sale s ON s.carId = c.carId", List.of()));

                new TableWriter("car_stored", layout.toColumnDefs(), false, 16).createTable(connection, true);
                long started = System.nanoTime();
                try (var appender = connection.createAppender("main", "car_stored")) {
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
                double load = (System.nanoTime() - started) / 1e6;
                double storedGroup = median(() -> Sql.queryLong(connection,
                        "SELECT count(DISTINCT make) FROM car_stored", List.of()));
                double storedJoin = median(() -> Sql.queryLong(connection, "SELECT count(*) FROM car_stored c"
                        + " JOIN sale s ON s.carId = c.carId", List.of()));

                System.out.printf("%,12d  group %6.1f ms, join %5.1f ms   load %6.1f ms,"
                                + " then group %5.1f ms, join %4.1f ms%n",
                        count, liveGroup, liveJoin, load, storedGroup, storedJoin);
            }
        }
        System.out.println("   The join reads one column of the objects, so the projection being pushed down");
        System.out.println("   shows: it costs a tenth of the group-by, which reads a string per object.");
        System.out.println("   Live wins when the data would otherwise have to be loaded first, and when it");
        System.out.println("   changes under you. Loading wins as soon as the same rows are queried again.");
    }

    static void print(DuckDBConnection connection, String sql) throws Exception {
        Rows rows = Rows.of(connection.duplicate(), sql);
        List<String> lines = new ArrayList<>();
        rows.forEachRow((SqlRow row) -> {
            StringBuilder line = new StringBuilder("   ");
            for (String column : row.getColumnNames()) {
                line.append(String.format("%-14s", row.get(column)));
            }
            lines.add(line.toString());
        });
        lines.forEach(System.out::println);
    }

    static double median(Runnable work) {
        work.run();
        long[] timings = new long[5];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            work.run();
            timings[i] = System.nanoTime() - started;
        }
        Arrays.sort(timings);
        return timings[timings.length / 2] / 1e6;
    }

    static String firstLine(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage()).split("\n")[0];
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * An ordinary List<Car>, queried as a table
 * ----------------------------------------
 *    make-0        250           5408.0        
 *    make-1        250           5409.0        
 *    make-2        250           5410.0        
 *    make-3        250           5411.0        
 *
 * Joined against 200,000 rows that are stored - no load step
 * ---------------------------------------------------------
 *    make-0        50000         6200000.0     
 *    make-1        50000         6250000.0     
 *    make-2        50000         6200000.0     
 *    make-3        50000         6250000.0     
 *
 * Still live: add to the list, ask again
 * -------------------------------------
 *    before: 1,000 rows
 *    after adding one object: 1,001 rows, and it is there: added-just-now
 *
 * Only the columns a query asks for are read from the objects
 * ----------------------------------------------------------
 *    after a query about make only:  price read 0 times
 *    after a query about price:      price read 1000 times
 *
 * rows handed to SQL so far: 5,002
 *
 * And afterwards the name is gone
 * -------------------------------
 *    Catalog Error: Table with name car does not exist!
 *
 * Live against loaded, on the same objects
 * ---------------------------------------
 *      objects  live (no load step)                 stored (loaded first)
 *      100,000  group   46.2 ms, join   7.7 ms   load   62.3 ms, then group   1.6 ms, join  1.7 ms
 *    1,000,000  group  463.9 ms, join  40.6 ms   load  479.1 ms, then group   2.4 ms, join  2.7 ms
 *    The join reads one column of the objects, so the projection being pushed down
 *    shows: it costs a tenth of the group-by, which reads a string per object.
 *    Live wins when the data would otherwise have to be loaded first, and when it
 *    changes under you. Loading wins as soon as the same rows are queried again.
 *
 * Three things worth noticing.
 *
 * The projection is pushed down: a query about make never calls the price accessor, and the join -
 * which reads one int per object - costs a tenth of the group-by, which reads a string per object.
 *
 * Reading objects is the cost. About 460 ns an object for a string column, against 2.4 ms for the
 * same query over a stored table, because a stored table is columnar and compressed and this is a
 * loop over the heap. The load that would fix it costs about the same as one live query, which is
 * the whole trade: live wins for a query or two and for data that changes under you, loading wins
 * the moment the same rows are asked about again.
 *
 * A live table is a view over a table function, so the catalog lists it like any other table, and
 * closing it takes the name away.
 */
