/*
 * What is in the database, and what it means - for anything that has to decide what to query before
 * it can query it.
 *
 * Descriptions and units live in the database as DuckDB comments, not in a registry of ours: they
 * survive in a file database, travel with it, and can be read with plain SQL by anything at all.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx2g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreCatalog.java
 */

import io.quackjvm.core.catalog.Catalog;
import io.quackjvm.core.catalog.ColumnInfo;
import io.quackjvm.core.catalog.Profile;
import io.quackjvm.core.catalog.TableInfo;
import io.quackjvm.core.duckdb.ColumnDef;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.duckdb.TableWriter;
import io.quackjvm.core.layout.ColumnarLayout;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.util.List;

public class CoreCatalog {

    public record Car(int carId, String make, double price, int mileage) {
    }

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

            // A layout that says what each column means, and what it is measured in.
            ColumnarLayout<Car> layout = ColumnarLayout.builder(Car.class)
                    .describingTable("cars we have sold, one row each")
                    .column("carId", Integer.class, Car::carId).describing("our own id for the car")
                    .column("make", String.class, Car::make).describing("who made it")
                    .column("price", Double.class, Car::price).describing("what it sold for").unit("USD")
                    .column("mileage", Integer.class, Car::mileage).describing("on the clock at sale").unit("km")
                    .rowFactory(values -> new Car((Integer) values[0], (String) values[1], (Double) values[2],
                            (Integer) values[3]))
                    .build();

            // Creating the table writes those into the database as comments.
            TableWriter writer = new TableWriter("car", layout.toColumnDefs(), false, 16);
            writer.createTable(connection, true);
            Catalog catalog = Catalog.of(connection);
            catalog.describeTable("car", layout.getDescription());
            Sql.execute(connection, "INSERT INTO car SELECT i, 'make-' || (i % 4), 5000.0 + (i % 900),"
                    + " (i * 37) % 250000 FROM range(200000) t(i)");

            // A second table, described by hand, with no layout anywhere.
            Sql.execute(connection, "CREATE TABLE service (serviceId INTEGER, carId INTEGER, cost DOUBLE)");
            Sql.execute(connection, "INSERT INTO service SELECT i, i % 200000, 50.0 + (i % 400)"
                    + " FROM range(50000) t(i)");
            catalog.describeTable("service", "work done on a car after it was sold");
            catalog.describeColumn("service", "cost", "what the work cost", "USD");

            System.out.println("Every table, as something deciding what to ask would read it");
            System.out.println("-----------------------------------------------------------");
            for (TableInfo table : catalog.tables()) {
                System.out.println(table.toText());
            }

            System.out.println("\nThe same, with the Java types filled in from the layout");
            System.out.println("------------------------------------------------------");
            for (ColumnInfo column : catalog.describe("car", layout).columns()) {
                System.out.printf("   %-10s %-8s %-18s %s%n", column.name(), column.sqlType(),
                        column.javaType() == null ? "-" : column.javaType().getSimpleName(),
                        column.unit() == null ? "" : "in " + column.unit());
            }

            System.out.println("\nAnd the comments are just SQL, readable by anything");
            System.out.println("--------------------------------------------------");
            try (var statement = connection.prepareStatement("SELECT table_name, column_name, comment"
                    + " FROM duckdb_columns() WHERE comment IS NOT NULL ORDER BY table_name, column_name");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    System.out.printf("   %-8s %-10s %s%n", rows.getString(1), rows.getString(2), rows.getString(3));
                }
            }

            System.out.println("\nWhat is actually in a table");
            System.out.println("---------------------------");
            Profile profile = catalog.profile("car", 3);
            System.out.println(profile.toText());

            System.out.println("\nWhat each of those costs");
            System.out.println("------------------------");
            System.out.printf("   describe (the schema only):    %s%n", median(() -> catalog.describe("car")));
            System.out.printf("   profile of 200,000 rows:       %s%n", median(() -> catalog.profile("car", 5)));
            for (long rows : new long[] {1_000_000L, 10_000_000L}) {
                Sql.execute(connection, "CREATE OR REPLACE TABLE big AS SELECT i AS id,"
                        + " 'make-' || (i % 97) AS make, i * 1.5 AS price, i % 7 = 0 AS sold"
                        + " FROM range(" + rows + ") t(i)");
                System.out.printf("   profile of %,11d rows:   %s%n", rows, median(() -> catalog.profile("big", 5)));
            }
            System.out.println("   so: describe whenever you like, profile when something has to decide.");

            System.out.println("\nA table that is not there says what is");
            System.out.println("-------------------------------------");
            try {
                catalog.describe("cars");
            }
            catch (IllegalArgumentException expected) {
                System.out.println("   " + expected.getMessage());
            }

            System.out.println("\nColumn definitions can carry it too, without a layout");
            System.out.println("----------------------------------------------------");
            new TableWriter("part", List.of(
                    new ColumnDef("partId", Integer.class).describedAs("our own id"),
                    new ColumnDef("weight", Double.class).describedAs("as weighed on arrival").measuredIn("kg")),
                    false, 16).createTable(connection, true);
            System.out.println("   " + catalog.describe("part").toText().replace("\n", "\n   "));
        }
    }

    static String median(Runnable work) {
        for (int i = 0; i < 3; i++) {
            work.run();
        }
        long[] timings = new long[7];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            work.run();
            timings[i] = System.nanoTime() - started;
        }
        java.util.Arrays.sort(timings);
        double ms = timings[timings.length / 2] / 1e6;
        return ms < 1 ? String.format("%.0f us", ms * 1000) : String.format("%.0f ms", ms);
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * Every table, as something deciding what to ask would read it
 * -----------------------------------------------------------
 * car (~200,000 rows) - cars we have sold, one row each
 *   carId INTEGER not null - our own id for the car
 *   make VARCHAR - who made it
 *   price DOUBLE in USD - what it sold for
 *   mileage INTEGER in km - on the clock at sale
 * service (~50,000 rows) - work done on a car after it was sold
 *   serviceId INTEGER
 *   carId INTEGER
 *   cost DOUBLE in USD - what the work cost
 *
 * The same, with the Java types filled in from the layout
 * ------------------------------------------------------
 *    carId      INTEGER  Integer            
 *    make       VARCHAR  String             
 *    price      DOUBLE   Double             in USD
 *    mileage    INTEGER  Integer            in km
 *
 * And the comments are just SQL, readable by anything
 * --------------------------------------------------
 *    car      carId      our own id for the car
 *    car      make       who made it
 *    car      mileage    {"description":"on the clock at sale","unit":"km"}
 *    car      price      {"description":"what it sold for","unit":"USD"}
 *    service  cost       {"description":"what the work cost","unit":"USD"}
 *
 * What is actually in a table
 * ---------------------------
 * car: 200,000 rows - cars we have sold, one row each
 *   carId INTEGER: 0 to 199999, ~187,859 distinct, mean 99999.5
 *   make VARCHAR: make-0 to make-3, ~4 distinct
 *   price DOUBLE: 5000.0 to 5899.0, ~1,022 distinct, mean 5449.15
 *   mileage INTEGER: 0 to 249999, ~182,977 distinct, mean 123986.5
 *   example rows:
 *     0, make-0, 5000.0, 0
 *     1, make-1, 5001.0, 37
 *     2, make-2, 5002.0, 74
 *
 * What each of those costs
 * ------------------------
 *    describe (the schema only):    1 ms
 *    profile of 200,000 rows:       32 ms
 *    profile of   1,000,000 rows:   34 ms
 *    profile of  10,000,000 rows:   307 ms
 *    so: describe whenever you like, profile when something has to decide.
 *
 * A table that is not there says what is
 * -------------------------------------
 *    No table named 'cars'. There is: big, car, service.
 *
 * Column definitions can carry it too, without a layout
 * ----------------------------------------------------
 *    part (~0 rows)
 *      partId INTEGER not null - our own id
 *      weight DOUBLE in kg - as weighed on arrival
 *
 * Note the raw comments: a column with a unit is stored as a small JSON object, because a comment is
 * one string and this needs to be two. A comment written by anyone else is read as a plain
 * description, so nothing has to know about the convention.
 *
 * describe reads metadata only, so it costs the same whatever the table holds. profile is SUMMARIZE,
 * which is a full scan - 34 ms at a million rows, 307 ms at ten million. That is cheap enough that
 * nothing here is cached: describe whenever you like, profile when something has to decide.
 */
