package io.quackjvm.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObjectStoreTest {

    private FakeObjectStore server;

    @Before
    public void start() throws Exception {
        server = new FakeObjectStore();
    }

    @After
    public void stop() {
        server.close();
    }

    @Test
    public void aTableGoesToTheStoreAndIsReadBack() {
        ObjectStore store = ObjectStore.s3(server.endpoint()).keys("reader", "s3cret");
        try (Quack quack = Quack.builder(null).objectStore(store).open()) {
            quack.execute("CREATE TABLE item AS SELECT range AS id, range * 2 AS v FROM range(1000)");

            assertEquals(1000, quack.exportTable("item", "s3://items/item.parquet"));

            assertTrue(server.objects.containsKey("items/item.parquet"));
            assertEquals("reader", server.accessKeys.get("items/item.parquet"));
            assertEquals(Long.valueOf(999_000), quack.query("SELECT sum(v) FROM read_parquet(?)",
                    "s3://items/item.parquet").scalar(Long.class));
        }
    }

    @Test
    public void aLargeTableGoesUpInParts() {
        try (Quack quack = Quack.builder(null).objectStore(ObjectStore.s3(server.endpoint()).keys("k", "s")).open()) {
            quack.execute("CREATE TABLE item AS SELECT range AS id, range * 2 AS v FROM range(3000000)");
            quack.exportTable("item", "s3://items/big.parquet");

            assertTrue(server.objects.get("items/big.parquet").length > 10_000_000);
            assertTrue("went up in parts", server.uploadIds.get() > 0);
            assertEquals(Long.valueOf(3_000_000), quack.query("SELECT count(*) FROM read_parquet('s3://items/*.parquet')")
                    .scalar(Long.class));
        }
    }

    @Test
    public void eachStoreAnswersForItsOwnScope() {
        ObjectStore items = ObjectStore.s3(server.endpoint()).keys("items-key", "a").scope("s3://items/");
        ObjectStore notes = ObjectStore.s3(server.endpoint()).keys("notes-key", "b").scope("s3://notes/");
        try (Quack quack = Quack.builder(null).objectStore(items).objectStore(notes).open()) {
            quack.execute("CREATE TABLE item AS SELECT 1 AS id");
            quack.exportTable("item", "s3://items/one.parquet");
            quack.exportTable("item", "s3://notes/one.parquet");

            assertEquals("items-key", server.accessKeys.get("items/one.parquet"));
            assertEquals("notes-key", server.accessKeys.get("notes/one.parquet"));
        }
    }

    @Test
    public void theSecretIsNeverShown() {
        ObjectStore store = ObjectStore.s3(server.endpoint()).keys("reader", "it's-s3cret");
        assertFalse(store.toString(), store.toString().contains("s3cret"));
        try (Quack quack = Quack.builder(null).objectStore(store).open()) {
            assertEquals(Long.valueOf(0), quack.query("SELECT count(*) FROM duckdb_secrets()"
                    + " WHERE secret_string LIKE '%s3cret%'").scalar(Long.class));
            // The quote in the secret was escaped, not cut off: the store is usable.
            quack.execute("CREATE TABLE item AS SELECT 1 AS id");
            quack.exportTable("item", "s3://items/one.parquet");
            assertTrue(server.objects.containsKey("items/one.parquet"));
        }
    }

    @Test
    public void aDatabaseOpenedHardenedStillReachesTheStoreItAllows() {
        try (Quack writer = Quack.builder(null).objectStore(ObjectStore.s3(server.endpoint()).keys("k", "s")).open()) {
            writer.execute("CREATE TABLE item AS SELECT range AS id FROM range(10)");
            writer.exportTable("item", "s3://items/item.parquet");
            writer.exportTable("item", "s3://notes/item.parquet");
            writer.exportTable("item", "s3://items2/item.parquet");
        }
        Properties locked = new Properties();
        locked.setProperty("autoinstall_known_extensions", "false");
        locked.setProperty("autoload_known_extensions", "false");
        locked.setProperty("allowed_directories", "['s3://items/']");
        locked.setProperty("enable_external_access", "false");
        locked.setProperty("lock_configuration", "true");

        try (Quack quack = Quack.builder(null).settings(locked)
                .objectStore(ObjectStore.s3(server.endpoint()).keys("k", "s")).open()) {
            assertEquals(Long.valueOf(10), quack.query("SELECT count(*) FROM read_parquet('s3://items/item.parquet')")
                    .scalar(Long.class));
            assertEquals("false", setting(quack, "enable_external_access"));
            assertEquals("true", setting(quack, "lock_configuration"));
            for (String outside : List.of("s3://notes/item.parquet", "s3://items2/item.parquet", "/etc/hosts")) {
                try {
                    quack.query("SELECT count(*) FROM read_csv(?)", outside).scalar(Long.class);
                    fail("Read " + outside);
                }
                catch (IllegalStateException e) {
                    assertTrue(e.getMessage(), e.getMessage().contains("Permission Error"));
                }
            }
        }
    }

    /**
     * What ObjectStore's javadoc says: DuckDB 2.0 checks an s3:// location against the allowed
     * folders as written, then sends {@code /notes/...} for {@code s3://items/../notes/...}. If this
     * fails, DuckDB has closed the hole: say so in ObjectStore and docs/object-stores.md.
     */
    @Test
    public void anAllowedFolderIsNotABoundaryOnItsOwn() {
        try (Quack writer = Quack.builder(null).objectStore(ObjectStore.s3(server.endpoint()).keys("k", "s")).open()) {
            writer.execute("CREATE TABLE item AS SELECT range AS id FROM range(7)");
            writer.exportTable("item", "s3://notes/item.parquet");
        }
        Properties locked = new Properties();
        locked.setProperty("allowed_directories", "['s3://items/']");
        locked.setProperty("enable_external_access", "false");
        locked.setProperty("lock_configuration", "true");
        try (Quack quack = Quack.builder(null).settings(locked)
                .objectStore(ObjectStore.s3(server.endpoint()).keys("k", "s")).open()) {
            assertEquals(Long.valueOf(7), quack.query("SELECT count(*) FROM read_parquet('s3://items/../notes/item.parquet')")
                    .scalar(Long.class));
        }
    }

    @Test
    public void withoutAStoreAHardenedDatabaseCannotLoadHttpfs() {
        Properties locked = new Properties();
        locked.setProperty("enable_external_access", "false");
        locked.setProperty("lock_configuration", "true");
        try (Quack quack = Quack.builder(null).settings(locked).open()) {
            quack.execute("LOAD httpfs");
            fail("Loaded httpfs");
        }
        catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("disabled"));
        }
    }

    @Test
    public void anEndpointNeedsItsScheme() {
        try {
            ObjectStore.s3("localhost:9000");
            fail();
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("http://"));
        }
    }

    private static String setting(Quack quack, String name) {
        return quack.query("SELECT value FROM duckdb_settings() WHERE name = ?", name).scalar(String.class);
    }
}
