package com.duckcq;

import com.duckcq.index.DuckDBIndex;
import com.duckcq.layout.ColumnarLayout;
import com.duckcq.persistence.DuckDBDatabase;
import com.duckcq.query.JoinPair;
import com.duckcq.query.SqlRow;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The join-pairs API and the raw SQL escape hatch. */
public class JoinPairsAndSqlTest {

    public record Vehicle(int vehicleId, String make, int ownerId, double price) {
        static final SimpleAttribute<Vehicle, Integer> VEHICLE_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "vehicleId") {
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.vehicleId();
                    }
                };
        static final Attribute<Vehicle, String> MAKE =
                new SimpleAttribute<>(Vehicle.class, String.class, "make") {
                    public String getValue(Vehicle v, QueryOptions q) {
                        return v.make();
                    }
                };
        static final Attribute<Vehicle, Integer> OWNER_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "ownerId") {
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.ownerId();
                    }
                };
    }

    public record Person(int personId, String country, String name) {
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

    private DuckDBDatabase database;
    private IndexedCollection<Vehicle> vehicles;
    private IndexedCollection<Person> people;
    private final List<Vehicle> vehicleData = new ArrayList<>();
    private final List<Person> personData = new ArrayList<>();

    private static final String[] COUNTRIES = {"FR", "DE", "IE", "US"};

    @Before
    public void setUp() {
        database = DuckDBDatabase.inMemory();
        vehicles = database.collection(Vehicle.VEHICLE_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class)).build();
        people = database.collection(Person.PERSON_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Person.class)).build();
        vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
        vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.MAKE));
        people.addIndex(DuckDBIndex.onAttribute(Person.COUNTRY));

        for (int i = 0; i < 40; i++) {
            personData.add(new Person(i, COUNTRIES[i % COUNTRIES.length], "person" + i));
        }
        for (int i = 0; i < 200; i++) {
            // ownerId 40..49 reference nobody, so those vehicles have no match.
            vehicleData.add(new Vehicle(i, "make" + (i % 4), i % 50, (i % 10) * 1000.0));
        }
        people.addAll(personData);
        vehicles.addAll(vehicleData);
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    /** The join's expected pairs, computed in plain Java. */
    private List<JoinPair<Vehicle, Person>> expectedPairs() {
        Map<Integer, Person> byId = new HashMap<>();
        personData.forEach(p -> byId.put(p.personId(), p));
        List<JoinPair<Vehicle, Person>> expected = new ArrayList<>();
        for (Vehicle vehicle : vehicleData) {
            Person person = byId.get(vehicle.ownerId());
            if (person != null) {
                expected.add(new JoinPair<>(vehicle, person));
            }
        }
        return expected;
    }

    @Test
    public void joinReturnsEveryMatchedPair() {
        List<JoinPair<Vehicle, Person>> expected = expectedPairs();
        try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .stream()) {
            List<JoinPair<Vehicle, Person>> actual = pairs.toList();
            assertEquals(expected.size(), actual.size());
            assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(actual));
        }
    }

    @Test
    public void unmatchedObjectsAreExcluded() {
        try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .stream()) {
            assertTrue(pairs.allMatch(pair -> pair.left().ownerId() == pair.right().personId()));
        }
        assertEquals(200, vehicles.size());
        // 10 of every 50 vehicles point at a non-existent owner.
        assertEquals(expectedPairs().size(), database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID).count());
    }

    @Test
    public void restrictionsApplyToBothSides() {
        long expected = expectedPairs().stream()
                .filter(pair -> pair.right().country().equals("FR") && pair.left().make().equals("make0"))
                .count();

        try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .whereRight(equal(Person.COUNTRY, "FR"))
                .whereLeft(equal(Vehicle.MAKE, "make0"))
                .stream()) {
            assertEquals(expected, pairs.count());
        }
        assertEquals(expected, database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .whereRight(equal(Person.COUNTRY, "FR"))
                .whereLeft(equal(Vehicle.MAKE, "make0"))
                .count());
    }

    @Test
    public void restrictionsWhichCannotBeTranslatedAreAppliedToTheResults() {
        // Person.NAME is not indexed, so this restriction cannot become SQL.
        long expected = expectedPairs().stream()
                .filter(pair -> pair.right().name().equals("person3"))
                .count();
        assertTrue(expected > 0);

        try (Stream<JoinPair<Vehicle, Person>> pairs = database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .whereRight(equal(Person.NAME, "person3"))
                .stream()) {
            assertEquals(expected, pairs.count());
        }
        assertEquals(expected, database.join(vehicles, people)
                .on(Vehicle.OWNER_ID, Person.PERSON_ID)
                .whereRight(equal(Person.NAME, "person3"))
                .count());
    }

    @Test
    public void joinOnANonKeyIndexedAttribute() {
        // Join people to vehicles the other way round, on the vehicle's indexed ownerId.
        long expected = expectedPairs().size();
        assertEquals(expected, database.join(people, vehicles)
                .on(Person.PERSON_ID, Vehicle.OWNER_ID)
                .count());
    }

    @Test
    public void joiningOnAnUnindexedAttributeIsRejectedClearly() {
        try {
            database.join(vehicles, people).on(Vehicle.MAKE, Person.NAME).count();
            fail("expected a clear error for an unindexed join attribute");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("neither the primary key"));
        }
    }

    @Test
    public void joiningACollectionFromAnotherDatabaseIsRejectedClearly() {
        IndexedCollection<Person> heapPeople = new ConcurrentIndexedCollection<>();
        try {
            database.join(vehicles, heapPeople).on(Vehicle.OWNER_ID, Person.PERSON_ID).count();
            fail("expected a clear error for a collection outside this database");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not stored in this database"));
        }
    }

    // ---------- Raw SQL ----------

    @Test
    public void rawSqlCanAggregateAcrossCollections() {
        Map<String, Long> expected = new HashMap<>();
        expectedPairs().forEach(pair -> expected.merge(pair.right().country(), 1L, Long::sum));

        String sql = "SELECT p.country AS country, count(*) AS vehicles "
                + "FROM " + database.tableName(vehicles) + " v "
                + "JOIN " + database.tableName(people) + " p ON v.ownerId = p.personId "
                + "GROUP BY 1 ORDER BY 1";
        Map<String, Long> actual = new HashMap<>();
        try (Stream<SqlRow> rows = database.sql(sql)) {
            rows.forEach(row -> actual.put(row.getString("country"), row.getLong("vehicles")));
        }
        assertEquals(expected, actual);
    }

    @Test
    public void rawSqlAcceptsParameters() {
        long expected = expectedPairs().stream()
                .filter(pair -> pair.right().country().equals("DE"))
                .count();
        String sql = "SELECT count(*) FROM " + database.tableName(vehicles) + " v "
                + "JOIN " + database.tableName(people) + " p ON v.ownerId = p.personId "
                + "WHERE p.country = ?";
        try (Stream<SqlRow> rows = database.sql(sql, "DE")) {
            assertEquals(expected, rows.findFirst().orElseThrow().getLong(1));
        }
    }

    @Test
    public void rawSqlRowsCanBeCopiedOutOfTheStream() {
        String sql = "SELECT vehicleId, make FROM " + database.tableName(vehicles) + " ORDER BY vehicleId LIMIT 3";
        List<Object[]> copied;
        try (Stream<SqlRow> rows = database.sql(sql)) {
            copied = rows.map(SqlRow::toArray).toList();
        }
        assertEquals(3, copied.size());
        assertEquals(0, ((Number) copied.get(0)[0]).intValue());
        assertEquals(1, ((Number) copied.get(1)[0]).intValue());
        assertEquals("make0", copied.get(0)[1]);
    }
}
