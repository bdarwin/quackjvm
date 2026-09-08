/*
 * CQEngine: two collections in one DuckDBDatabase, queried three ways - existsIn() pushed into a
 * SQL semi-join, database.join(...) for matched pairs, and database.sql(...) for an aggregate.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineJoins.java
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.JoinPair;
import io.quackjvm.core.sql.SqlRow;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.existsIn;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;

public class CqEngineJoins {

    public record Vehicle(int vehicleId, String make, int ownerId, double price) {
        static final SimpleAttribute<Vehicle, Integer> VEHICLE_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "vehicleId") {
                    public Integer getValue(Vehicle vehicle, QueryOptions options) {
                        return vehicle.vehicleId();
                    }
                };
        static final Attribute<Vehicle, Integer> OWNER_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "ownerId") {
                    public Integer getValue(Vehicle vehicle, QueryOptions options) {
                        return vehicle.ownerId();
                    }
                };
        static final Attribute<Vehicle, Double> PRICE =
                new SimpleAttribute<>(Vehicle.class, Double.class, "price") {
                    public Double getValue(Vehicle vehicle, QueryOptions options) {
                        return vehicle.price();
                    }
                };
    }

    public record Person(int personId, String country, String name) {
        static final SimpleAttribute<Person, Integer> PERSON_ID =
                new SimpleAttribute<>(Person.class, Integer.class, "personId") {
                    public Integer getValue(Person person, QueryOptions options) {
                        return person.personId();
                    }
                };
        static final Attribute<Person, String> COUNTRY =
                new SimpleAttribute<>(Person.class, String.class, "country") {
                    public String getValue(Person person, QueryOptions options) {
                        return person.country();
                    }
                };
    }

    public static void main(String[] args) {
        // Collections which share a database are separate tables in the same DuckDB instance,
        // which is what makes joining them possible.
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("256MB").build()) {

            // A columnar layout is required for joins to filter in SQL: it is what turns the
            // objects' fields into real columns.
            IndexedCollection<Vehicle> vehicles = database.collection(Vehicle.VEHICLE_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class))
                    .build();
            IndexedCollection<Person> people = database.collection(Person.PERSON_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Person.class))
                    .build();

            vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
            vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.PRICE));
            people.addIndex(DuckDBIndex.onAttribute(Person.COUNTRY));

            String[] countries = {"FR", "DE", "IE", "US"};
            List<Person> personData = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                personData.add(new Person(i, countries[i % countries.length], "person" + i));
            }
            List<Vehicle> vehicleData = new ArrayList<>();
            for (int i = 0; i < 80_000; i++) {
                // Owner ids 20,000 upwards reference nobody, so those vehicles never match.
                vehicleData.add(new Vehicle(i, "make" + (i % 5), i % 25_000,
                        1_000.0 + (i * 7919L) % 50_000));
            }
            people.addAll(personData);
            vehicles.addAll(vehicleData);
            System.out.printf("%,d vehicles, %,d people, one database.%n", vehicles.size(), people.size());
            System.out.println();
            System.out.println(database.describe());

            // ---------- 1. existsIn: stock CQEngine syntax, one SQL semi-join ----------
            long startedAt = System.nanoTime();
            try (ResultSet<Vehicle> results = vehicles.retrieve(
                    existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID, equal(Person.COUNTRY, "FR")))) {
                System.out.printf("existsIn: %,d vehicles owned by someone in FR, in %,d ms%n",
                        results.size(), (System.nanoTime() - startedAt) / 1_000_000);
            }

            // It composes with ordinary conditions, still as one statement.
            startedAt = System.nanoTime();
            try (ResultSet<Vehicle> results = vehicles.retrieve(
                    com.googlecode.cqengine.query.QueryFactory.and(
                            greaterThan(Vehicle.PRICE, 45_000.0),
                            existsIn(people, Vehicle.OWNER_ID, Person.PERSON_ID,
                                    equal(Person.COUNTRY, "DE"))))) {
                System.out.printf("existsIn and price > 45000: %,d vehicles, in %,d ms%n",
                        results.size(), (System.nanoTime() - startedAt) / 1_000_000);
            }

            // ---------- 2. join(): the matched pairs, which existsIn cannot give you ----------
            startedAt = System.nanoTime();
            long pairCount;
            List<JoinPair<Vehicle, Person>> sample = new ArrayList<>();
            // The stream holds a connection, so it must be closed.
            try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
                    .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                    .whereRight(equal(Person.COUNTRY, "IE"))
                    .whereLeft(greaterThan(Vehicle.PRICE, 49_000.0))
                    .stream()) {
                for (JoinPair<Vehicle, Person> pair : (Iterable<JoinPair<Vehicle, Person>>) pairs::iterator) {
                    if (sample.size() < 3) {
                        sample.add(pair);
                    }
                }
            }
            // count() answers the same question without rebuilding any objects.
            pairCount = database.join(vehicles, people)
                    .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                    .whereRight(equal(Person.COUNTRY, "IE"))
                    .whereLeft(greaterThan(Vehicle.PRICE, 49_000.0))
                    .count();
            System.out.printf("%njoin: %,d matched pairs in %,d ms; the first few:%n",
                    pairCount, (System.nanoTime() - startedAt) / 1_000_000);
            sample.forEach(pair -> System.out.println("  " + pair.left() + "  <->  " + pair.right()));

            // ---------- 3. sql(): an aggregate across both collections ----------
            // Each collection is queryable under its own name, which is a view over its table.
            System.out.println();
            System.out.println("sql: vehicles and average price per country");
            try (Stream<SqlRow> rows = database.sql(
                    "SELECT p.country, count(*) AS vehicles, round(avg(v.price), 2) AS avgPrice "
                            + "FROM " + database.table(vehicles) + " v "
                            + "JOIN " + database.table(people) + " p "
                            + "  ON v." + database.column(vehicles, Vehicle.OWNER_ID)
                            + " = p." + database.column(people, Person.PERSON_ID) + " "
                            + "GROUP BY 1 ORDER BY 2 DESC")) {
                rows.forEach(row -> System.out.printf("  %-3s %,8d vehicles, average %,10.2f%n",
                        row.getString("country"), row.getLong("vehicles"), row.getDouble("avgPrice")));
            }
        }
    }
}

/*
 * Output (Apple Silicon, JDK 25):
 *
 * 80,000 vehicles, 20,000 people, one database.
 *
 * DuckDB database (in memory), queryable with sql():
 *   person  [Person, columnar]
 *       columns: objectKey INTEGER, personId INTEGER, country VARCHAR, name VARCHAR
 *   vehicle  [Vehicle, columnar]
 *       columns: objectKey INTEGER, vehicleId INTEGER, make VARCHAR, ownerId INTEGER, price DOUBLE
 *
 * existsIn: 16,250 vehicles owned by someone in FR, in 121 ms
 * existsIn and price > 45000: 1,948 vehicles, in 61 ms
 *
 * join: 650 matched pairs in 255 ms; the first few:
 *   Vehicle[vehicleId=4950, make=make0, ownerId=4950, price=50050.0]  <->  Person[personId=4950, country=IE, name=person4950]
 *   Vehicle[vehicleId=10986, make=make1, ownerId=10986, price=49134.0]  <->  Person[personId=10986, country=IE, name=person10986]
 *   Vehicle[vehicleId=28078, make=make3, ownerId=3078, price=50682.0]  <->  Person[personId=3078, country=IE, name=person3078]
 *
 * sql: vehicles and average price per country
 *   US    16,250 vehicles, average  25,997.46
 *   FR    16,250 vehicles, average  26,003.54
 *   IE    16,250 vehicles, average  25,998.46
 *   DE    16,250 vehicles, average  25,999.46
 */
