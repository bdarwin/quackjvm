package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.testutil.Owner;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.hash.HashIndex;
import com.googlecode.cqengine.index.navigable.NavigableIndex;
import com.googlecode.cqengine.query.Query;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.googlecode.cqengine.query.option.DeduplicationStrategy;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.deduplicate;
import static com.googlecode.cqengine.query.QueryFactory.queryOptions;
import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.existsIn;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static com.googlecode.cqengine.query.QueryFactory.in;
import static com.googlecode.cqengine.query.QueryFactory.lessThan;
import static com.googlecode.cqengine.query.QueryFactory.not;
import static com.googlecode.cqengine.query.QueryFactory.or;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Joins across collections: the thing CQEngine cannot do efficiently, because its existsIn()
 * performs one foreign lookup per object. Every case is checked against an equivalent pair of
 * on-heap collections, which evaluate the same queries CQEngine's own way.
 */
public class JoinTest {

    /** A vehicle owned by an Owner - the local side of the join. */
    public record Vehicle(int vehicleId, String make, int ownerId, double price) {

        static final SimpleAttribute<Vehicle, Integer> VEHICLE_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "vehicleId") {
                    @Override
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.vehicleId();
                    }
                };
        static final Attribute<Vehicle, String> MAKE =
                new SimpleAttribute<>(Vehicle.class, String.class, "make") {
                    @Override
                    public String getValue(Vehicle v, QueryOptions q) {
                        return v.make();
                    }
                };
        static final Attribute<Vehicle, Integer> OWNER_ID =
                new SimpleAttribute<>(Vehicle.class, Integer.class, "ownerId") {
                    @Override
                    public Integer getValue(Vehicle v, QueryOptions q) {
                        return v.ownerId();
                    }
                };
        static final Attribute<Vehicle, Double> PRICE =
                new SimpleAttribute<>(Vehicle.class, Double.class, "price") {
                    @Override
                    public Double getValue(Vehicle v, QueryOptions q) {
                        return v.price();
                    }
                };
    }

    private DuckDBDatabase database;
    private IndexedCollection<Vehicle> vehicles;
    private IndexedCollection<Owner> owners;
    private IndexedCollection<Vehicle> heapVehicles;
    private IndexedCollection<Owner> heapOwners;

    private static final String[] COUNTRIES = {"FR", "DE", "IE", "US"};
    private static final String[] MAKES = {"Ford", "Honda", "Tesla", "BMW"};

    @Before
    public void setUp() {
        database = DuckDBDatabase.inMemory();
        vehicles = database.collection(Vehicle.VEHICLE_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Vehicle.class)).build();
        owners = database.collection(Owner.OWNER_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Owner.class)).build();
        vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.OWNER_ID));
        vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.MAKE));
        vehicles.addIndex(DuckDBIndex.onAttribute(Vehicle.PRICE));
        owners.addIndex(DuckDBIndex.onAttribute(Owner.COUNTRY));
        owners.addIndex(DuckDBIndex.onAttribute(Owner.AGE));

        heapVehicles = new ConcurrentIndexedCollection<>();
        heapOwners = new ConcurrentIndexedCollection<>();
        // The reference collections get equivalent on-heap indexes, so that CQEngine's query engine
        // takes the same path on both sides and the comparison isolates the join itself.
        heapVehicles.addIndex(HashIndex.onAttribute(Vehicle.OWNER_ID));
        heapVehicles.addIndex(HashIndex.onAttribute(Vehicle.MAKE));
        heapVehicles.addIndex(NavigableIndex.onAttribute(Vehicle.PRICE));
        heapOwners.addIndex(HashIndex.onAttribute(Owner.COUNTRY));
        heapOwners.addIndex(NavigableIndex.onAttribute(Owner.AGE));

        List<Owner> ownerData = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ownerData.add(new Owner(i, "owner" + i, COUNTRIES[i % COUNTRIES.length], 20 + (i % 50)));
        }
        List<Vehicle> vehicleData = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            // Some vehicles reference an owner which does not exist, to exercise unmatched rows.
            int ownerId = i % 250;
            vehicleData.add(new Vehicle(i, MAKES[i % MAKES.length], ownerId, (i % 100) * 1000.0));
        }
        owners.addAll(ownerData);
        vehicles.addAll(vehicleData);
        heapOwners.addAll(ownerData);
        heapVehicles.addAll(vehicleData);
    }

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    /**
     * Runs the same query against DuckDB and against on-heap CQEngine, and compares.
     *
     * <p>Deduplication is requested explicitly, because an {@code or()} in CQEngine returns an
     * object once per branch it matches unless asked not to, and which branches end up index-backed
     * differs between the two collections. With it on, both sides count distinct objects.</p>
     */
    private void assertJoinMatchesCQEngine(Query<Vehicle> duckDbQuery, Query<Vehicle> heapQuery) {
        QueryOptions options = queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION));
        Set<Vehicle> expected = new HashSet<>();
        int expectedSize;
        try (ResultSet<Vehicle> results = heapVehicles.retrieve(heapQuery, options)) {
            results.forEach(expected::add);
            expectedSize = results.size();
        }
        Set<Vehicle> actual = new HashSet<>();
        try (ResultSet<Vehicle> results = vehicles.retrieve(duckDbQuery,
                queryOptions(deduplicate(DeduplicationStrategy.LOGICAL_ELIMINATION)))) {
            results.forEach(actual::add);
            assertEquals("results differ for: " + duckDbQuery, expected, actual);
            assertEquals("size() differs for: " + duckDbQuery, expectedSize, results.size());
        }
        assertTrue("the join should match at least something", expected.size() > 0);
    }

    @Test
    public void joinWithNoForeignRestriction() {
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID));
    }

    @Test
    public void joinRestrictedByEquality() {
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR")),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR")));
    }

    @Test
    public void joinRestrictedByRange() {
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, greaterThan(Owner.AGE, 50)),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, greaterThan(Owner.AGE, 50)));
    }

    @Test
    public void joinRestrictedByAnd() {
        Query<Owner> restriction = and(equal(Owner.COUNTRY, "DE"), between(Owner.AGE, 30, 40));
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction));
    }

    @Test
    public void joinRestrictedByOr() {
        Query<Owner> restriction = or(equal(Owner.COUNTRY, "IE"), greaterThan(Owner.AGE, 60));
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction));
    }

    @Test
    public void joinRestrictedByNot() {
        Query<Owner> restriction = not(equal(Owner.COUNTRY, "US"));
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction));
    }

    @Test
    public void joinRestrictedByIn() {
        Query<Owner> restriction = in(Owner.COUNTRY, "FR", "DE");
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, restriction));
    }

    @Test
    public void joinCombinedWithALocalQuery() {
        assertJoinMatchesCQEngine(
                and(equal(Vehicle.MAKE, "Tesla"),
                        existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR"))),
                and(equal(Vehicle.MAKE, "Tesla"),
                        existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR"))));
    }

    @Test
    public void joinCombinedWithALocalRangeAndOr() {
        assertJoinMatchesCQEngine(
                or(lessThan(Vehicle.PRICE, 5_000.0),
                        existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "IE"))),
                or(lessThan(Vehicle.PRICE, 5_000.0),
                        existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "IE"))));
    }

    @Test
    public void joinAgainstACollectionInAnotherDatabaseStillWorks() {
        // Not pushed down - the foreign collection is on the heap - but it must still be correct.
        assertJoinMatchesCQEngine(
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR")),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.COUNTRY, "FR")));
    }

    @Test
    public void joinOnAnUnindexedForeignAttributeStillWorks() {
        // Owner.NAME has no index, so the restriction cannot be translated; CQEngine evaluates it.
        assertJoinMatchesCQEngine(
                existsIn(owners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.NAME, "owner7")),
                existsIn(heapOwners, Vehicle.OWNER_ID, Owner.OWNER_ID, equal(Owner.NAME, "owner7")));
    }
}
