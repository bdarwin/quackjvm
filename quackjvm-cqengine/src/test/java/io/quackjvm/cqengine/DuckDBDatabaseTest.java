package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import io.quackjvm.cqengine.testutil.Owner;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DuckDBDatabaseTest {

    private DuckDBDatabase database;

    @After
    public void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    private static List<Owner> owners(int count) {
        List<Owner> owners = new ArrayList<>(count);
        String[] countries = {"FR", "DE", "IE", "US"};
        for (int i = 0; i < count; i++) {
            owners.add(new Owner(i, "owner" + i, countries[i % countries.length], 20 + (i % 50)));
        }
        return owners;
    }

    @Test
    public void twoCollectionsShareOneDatabase() {
        database = DuckDBDatabase.inMemory();

        IndexedCollection<Car> cars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class))
                .build();
        IndexedCollection<Owner> ownerCollection = database.collection(Owner.OWNER_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Owner.class))
                .build();
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        ownerCollection.addIndex(DuckDBIndex.onAttribute(Owner.COUNTRY));

        List<Car> generatedCars = Cars.generate(500, 51);
        List<Owner> generatedOwners = owners(100);
        cars.addAll(generatedCars);
        ownerCollection.addAll(generatedOwners);

        // Each collection keeps its own contents, in its own tables.
        assertEquals(generatedCars.size(), cars.size());
        assertEquals(generatedOwners.size(), ownerCollection.size());

        long expectedFords = generatedCars.stream().filter(c -> c.manufacturer().equals("Ford")).count();
        try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
            assertEquals(expectedFords, results.size());
        }
        long expectedFrench = generatedOwners.stream().filter(o -> o.country().equals("FR")).count();
        try (ResultSet<Owner> results = ownerCollection.retrieve(equal(Owner.COUNTRY, "FR"))) {
            assertEquals(expectedFrench, results.size());
        }
    }

    @Test
    public void collectionsGetTheirOwnTables() {
        database = DuckDBDatabase.inMemory();
        DuckDBPersistence<Car, Integer> carPersistence = database.collection(Car.CAR_ID).buildPersistence();
        DuckDBPersistence<Owner, Integer> ownerPersistence = database.collection(Owner.OWNER_ID).buildPersistence();

        assertEquals("cq_car", carPersistence.getObjectTable().getTableName());
        assertEquals("cq_owner", ownerPersistence.getObjectTable().getTableName());
        assertNotEquals(carPersistence.getObjectTable().getTableName(),
                ownerPersistence.getObjectTable().getTableName());
    }

    @Test
    public void twoCollectionsOfTheSameTypeNeedDistinctNames() {
        database = DuckDBDatabase.inMemory();
        database.collection(Car.CAR_ID).buildPersistence();
        try {
            database.collection(Car.CAR_ID).buildPersistence();
            fail("expected a clash between two collections with the same derived name");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("already holds a collection"));
        }

        // Naming one of them resolves it.
        IndexedCollection<Car> archive = database.collection(Car.CAR_ID).name("archivedCars").build();
        archive.add(Cars.generate(1, 52).get(0));
        assertEquals(1, archive.size());
    }

    @Test
    public void sharedCollectionsPersistTogetherInOneFile() throws Exception {
        File file = Files.createTempDirectory("duckcq").resolve("shared.duckdb").toFile();
        file.deleteOnExit();

        DuckDBDatabase first = DuckDBDatabase.inFile(file);
        IndexedCollection<Car> cars = first.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        IndexedCollection<Owner> ownerCollection = first.collection(Owner.OWNER_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Owner.class)).build();
        cars.addAll(Cars.generate(50, 53));
        ownerCollection.addAll(owners(20));
        long bytesUsed = first.getBytesUsed();
        first.close();

        assertTrue(bytesUsed > 0);

        database = DuckDBDatabase.inFile(file);
        IndexedCollection<Car> reopenedCars = database.collection(Car.CAR_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Car.class)).build();
        IndexedCollection<Owner> reopenedOwners = database.collection(Owner.OWNER_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Owner.class)).build();
        assertEquals(50, reopenedCars.size());
        assertEquals(20, reopenedOwners.size());
    }

    @Test
    public void closingACollectionDoesNotCloseASharedDatabase() {
        database = DuckDBDatabase.inMemory();
        DuckDBPersistence<Car, Integer> carPersistence = database.collection(Car.CAR_ID).buildPersistence();
        IndexedCollection<Car> cars = new com.googlecode.cqengine.ConcurrentIndexedCollection<>(carPersistence);
        IndexedCollection<Owner> ownerCollection = database.collection(Owner.OWNER_ID).build();
        cars.addAll(Cars.generate(10, 54));
        ownerCollection.addAll(owners(5));

        carPersistence.close();

        // The shared database, and the other collection in it, are unaffected.
        assertTrue(!database.isClosed());
        assertEquals(5, ownerCollection.size());
    }
}
