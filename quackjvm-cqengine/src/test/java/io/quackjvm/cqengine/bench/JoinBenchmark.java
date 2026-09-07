package io.quackjvm.cqengine.bench;

import io.quackjvm.core.sql.JoinPair;
import io.quackjvm.core.sql.SqlRow;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.existsIn;

/**
 * What a cross-collection join costs, three ways: on the heap, pushed into DuckDB as one SQL
 * statement, and evaluated by CQEngine itself over DuckDB-backed collections.
 *
 * <p>The last is what you get without the push-down - CQEngine's {@code existsIn} asks the foreign
 * collection about one object at a time, which over a database is a query per object.</p>
 */
public class JoinBenchmark {

    public record Vehicle(int vehicleId, String make, int ownerId, double price) {
        static final SimpleAttribute<Vehicle, Integer> VEHICLE_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "vehicleId") {
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.vehicleId();
                    }
                };
        static final Attribute<Vehicle, Integer> OWNER_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "ownerId") {
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.ownerId();
                    }
                };
    }

    public record Person(int personId, String country, String name, int age) {
        static final SimpleAttribute<Person, Integer> PERSON_ID =
                new SimpleAttribute<>(Person.class, Integer.class, "personId") {
                    public Integer getValue(Person p, QueryOptions q) {
                        return p.personId();
                    }
                };
        static final Attribute<Person, String> COUNTRY =
                new SimpleAttribute<>(Person.class, String.class, "country") {
                    public String getValue(Person p, QueryOptions q) {
                        return p.country();
                    }
                };
        static final Attribute<Person, String> NAME =
                new SimpleAttribute<>(Person.class, String.class, "name") {
                    public String getValue(Person p, QueryOptions q) {
                        return p.name();
                    }
                };
    }

    private static final String[] COUNTRIES = {"FR", "DE", "IE", "US", "GB"};

    public static void main(String[] args) {
        int vehicleCount = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int personCount = args.length > 1 ? Integer.parseInt(args[1]) : 50_000;

        List<Person> people = new ArrayList<>(personCount);
        for (int i = 0; i < personCount; i++) {
            people.add(new Person(i, COUNTRIES[i % COUNTRIES.length], "person" + i, 20 + (i % 50)));
        }
        List<Vehicle> vehicles = new ArrayList<>(vehicleCount);
        for (int i = 0; i < vehicleCount; i++) {
            vehicles.add(new Vehicle(i, "make" + (i % 20), i % personCount, (i % 100) * 1000.0));
        }

        System.out.printf("%,d vehicles joined to %,d people, %d%% of whom match the restriction.%n%n",
                vehicleCount, personCount, 100 / COUNTRIES.length);

        // ---- on-heap CQEngine ----
        IndexedCollection<Vehicle> heapVehicles = new ConcurrentIndexedCollection<>();
        IndexedCollection<Person> heapPeople = new ConcurrentIndexedCollection<>();
        heapVehicles.addIndex(HashIndex.onAttribute(Vehicle.OWNER_ID));
        heapPeople.addIndex(HashIndex.onAttribute(Person.COUNTRY));
        heapPeople.addIndex(NavigableIndex.onAttribute(Person.PERSON_ID));
        heapVehicles.addAll(vehicles);
        heapPeople.addAll(people);

        Query<Vehicle> heapQuery = existsIn(heapPeople, Vehicle.OWNER_ID, Person.PERSON_ID,
                equal(Person.COUNTRY, "FR"));
        report("CQEngine on-heap", () -> count(heapVehicles, heapQuery));

        // ---- DuckDB, join pushed into SQL ----
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("512MB").build()) {
            IndexedCollection<Vehicle> duckVehicles = database.collection(Vehicle.VEHICLE_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class)).build();
            IndexedCollection<Person> duckPeople = database.collection(Person.PERSON_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Person.class)).build();
            duckVehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
            duckPeople.addIndex(DuckDBIndex.onAttribute(Person.COUNTRY));
            duckVehicles.addAll(vehicles);
            duckPeople.addAll(people);

            Query<Vehicle> pushedDown = existsIn(duckPeople, Vehicle.OWNER_ID, Person.PERSON_ID,
                    equal(Person.COUNTRY, "FR"));
            report("DuckDB, pushed into SQL", () -> count(duckVehicles, pushedDown));

            // Person.NAME has no index, so the restriction cannot be translated and CQEngine
            // evaluates the join itself - one foreign lookup per object.
            Query<Vehicle> notPushedDown = existsIn(duckPeople, Vehicle.OWNER_ID, Person.PERSON_ID,
                    equal(Person.NAME, "person7"));
            report("DuckDB, CQEngine's own evaluation", () -> count(duckVehicles, notPushedDown));

            System.out.println();
            System.out.println("  Returning the matched pairs, not just the left side:");

            // What a CQEngine user writes today: build a map of the other collection, then walk.
            report("hand-written join, on-heap", () -> {
                java.util.Map<Integer, Person> byId = new java.util.HashMap<>();
                try (ResultSet<Person> matching = heapPeople.retrieve(equal(Person.COUNTRY, "FR"))) {
                    matching.forEach(p -> byId.put(p.personId(), p));
                }
                // Build the actual pairs, so this is comparable with what join() returns.
                List<io.quackjvm.core.sql.JoinPair<Vehicle, Person>> pairs = new ArrayList<>();
                try (ResultSet<Vehicle> all = heapVehicles.retrieve(
                        com.googlecode.cqengine.query.QueryFactory.has(Vehicle.OWNER_ID))) {
                    for (Vehicle vehicle : all) {
                        Person person = byId.get(vehicle.ownerId());
                        if (person != null) {
                            pairs.add(new io.quackjvm.core.sql.JoinPair<>(vehicle, person));
                        }
                    }
                }
                return pairs.size();
            });

            report("DuckDB join(), materialising pairs", () -> {
                try (java.util.stream.Stream<io.quackjvm.core.sql.JoinPair<Vehicle, Person>> pairs =
                             database.join(duckVehicles, duckPeople)
                                     .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                                     .whereRight(equal(Person.COUNTRY, "FR"))
                                     .stream()) {
                    return pairs.toList().size();
                }
            });

            System.out.println();
            System.out.println("  Aggregating across both collections:");
            report("DuckDB sql(), GROUP BY country", () -> {
                String sql = "SELECT p.country, count(*) AS vehicles, avg(v.price) AS avgPrice FROM "
                        + database.tableName(duckVehicles) + " v JOIN "
                        + database.tableName(duckPeople) + " p ON v.ownerId = p.personId GROUP BY 1";
                try (java.util.stream.Stream<io.quackjvm.core.sql.SqlRow> rows = database.sql(sql)) {
                    return (int) rows.count();
                }
            });
        }
    }

    private static int count(IndexedCollection<Vehicle> collection, Query<Vehicle> query) {
        try (ResultSet<Vehicle> results = collection.retrieve(query)) {
            int count = 0;
            for (Vehicle ignored : results) {
                count++;
            }
            return count;
        }
    }

    private static void report(String label, Supplier<Integer> workload) {
        long start = System.nanoTime();
        int matched = workload.get();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.printf("  %-36s %9.3f s   (%,d vehicles matched)%n", label, seconds, matched);
    }
}
