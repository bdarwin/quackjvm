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

    // ---------- deleting, signed as S3 expects ----------

    @Test
    public void theSignatureIsAwsOwnExample() {
        // "Example: GET Object" in AWS's Signature Version 4 documentation for S3.
        java.util.Map<String, String> headers = new java.util.TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        headers.put("x-amz-date", "20130524T000000Z");

        String authorization = ObjectStore.authorization("GET", "/test.txt", headers,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "20130524T000000Z");

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", authorization);
    }

    @Test
    public void anObjectIsFoundWhereEachKindOfStoreKeepsIt() {
        assertEquals("http://localhost:9000/items/data/day%3D2026-01-01/a%20b.parquet",
                ObjectStore.s3("http://localhost:9000").url("s3://items/data/day=2026-01-01/a b.parquet").toString());
        assertEquals("https://items.storage.example.net/a.csv",
                ObjectStore.s3("https://storage.example.net").pathStyle(false).url("s3://items/a.csv").toString());
        assertEquals("https://items.s3.eu-west-1.amazonaws.com/a.csv",
                ObjectStore.aws("eu-west-1").url("s3://items/a.csv").toString());
        assertEquals("https://s3.eu-west-1.amazonaws.com/items/a.csv",
                ObjectStore.aws("eu-west-1").pathStyle(true).url("s3://items/a.csv").toString());
        for (String bad : new String[]{"s3://items", "s3://items/", "https://items/a.csv"}) {
            try {
                ObjectStore.s3("http://localhost:9000").url(bad);
                fail(bad);
            }
            catch (IllegalArgumentException expected) {
                // not an object
            }
        }
    }

    @Test
    public void theStoreWithTheLongestScopeDeletes() {
        ObjectStore any = ObjectStore.s3(server.endpoint()).keys("any", "a");
        ObjectStore items = ObjectStore.s3(server.endpoint()).keys("items", "b").scope("s3://items/");
        ObjectStore deep = ObjectStore.s3(server.endpoint()).keys("deep", "c").scope("s3://items/deep/");
        List<ObjectStore> stores = List.of(any, items, deep);

        assertEquals(deep, ObjectStore.forLocation(stores, "s3://items/deep/a"));
        assertEquals(items, ObjectStore.forLocation(stores, "s3://items/a"));
        assertEquals(any, ObjectStore.forLocation(stores, "s3://notes/a"));
        assertEquals(null, ObjectStore.forLocation(List.of(items), "s3://notes/a"));
    }

    @Test
    public void deletingSignsWithTheStoresKeyAndAMissingObjectIsNoFailure() throws Exception {
        server.objects.put("items/a.csv", new byte[]{1});
        ObjectStore store = ObjectStore.s3(server.endpoint()).keys("deleter", "d");
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

        store.delete(http, "s3://items/a.csv");
        store.delete(http, "s3://items/a.csv");

        assertFalse(server.objects.containsKey("items/a.csv"));
        assertEquals(List.of("items/a.csv", "items/a.csv"), server.deleted);
        assertEquals("deleter", server.accessKeys.get("items/a.csv"));
    }
}
