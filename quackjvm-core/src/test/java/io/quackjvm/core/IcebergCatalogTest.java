package io.quackjvm.core;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IcebergCatalogTest {

    private static final String ITEMS = "lake.demo.items";

    private FakeObjectStore storage;
    private FakeIcebergCatalog catalog;

    @Before
    public void start() throws Exception {
        storage = new FakeObjectStore();
        catalog = new FakeIcebergCatalog(storage, "lake");
    }

    @After
    public void stop() {
        catalog.close();
        storage.close();
    }

    private ObjectStore store() {
        return ObjectStore.s3(storage.endpoint()).keys("k", "s");
    }

    private IcebergCatalog lake() {
        return IcebergCatalog.rest(catalog.endpoint()).warehouse("w");
    }

    private Quack open() {
        return Quack.builder(null).objectStore(store()).catalog("lake", lake()).open();
    }

    private static void createItems(Quack db) {
        db.execute("CREATE SCHEMA lake.demo");
        db.execute("CREATE TABLE " + ITEMS + " (id BIGINT, name VARCHAR)");
    }

    private static long count(Quack db) {
        return db.query("SELECT count(*) FROM " + ITEMS).scalar(Long.class);
    }

    // ---------- reading and writing ----------

    @Test
    public void tablesAreWrittenAndReadThroughTheCatalog() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " SELECT range, 'n' || range FROM range(1000)");
            db.execute("UPDATE " + ITEMS + " SET name = 'changed' WHERE id < 10");
            db.execute("DELETE FROM " + ITEMS + " WHERE id < 5");

            assertEquals(995, count(db));
            assertEquals(Long.valueOf(5), db.query("SELECT count(*) FROM " + ITEMS + " WHERE name = 'changed'").scalar(Long.class));
            assertEquals(3, catalog.snapshots("demo", "items").size());
            assertTrue("data files in the store", storage.objects.keySet().stream()
                    .anyMatch(k -> k.startsWith("lake/demo/items/data/") && k.endsWith(".parquet")));
        }
    }

    @Test
    public void anotherDatabaseSeesWhatOneWrote() {
        try (Quack writer = open(); Quack reader = open()) {
            createItems(writer);
            writer.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            assertEquals(1, count(reader));
            writer.execute("INSERT INTO " + ITEMS + " VALUES (2, 'b')");
            assertEquals(2, count(reader));
        }
    }

    // ---------- conflicts ----------

    // DuckDB 2.0 itself sends an insert or a delete up to five times when the catalog answers 409,
    // and an update once: the conflicts below are forced on updates, so that quackjvm's retry is
    // what is tested.

    @Test
    public void aStatementThatMeetsAnotherWritersCommitIsRunAgain() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            catalog.conflictsToForce.set(2);

            db.execute("UPDATE " + ITEMS + " SET id = id + 1");

            assertEquals(2, catalog.conflicts.get());
            assertEquals("applied once, not three times", List.of(2L), db.query("SELECT id FROM " + ITEMS).list(Long.class));
        }
    }

    @Test
    public void aStatementGivesUpWhenItsRetriesRunOut() {
        try (Quack db = Quack.builder(null).objectStore(store()).catalog("lake", lake().retries(1)).open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            catalog.conflictsToForce.set(5);
            try {
                db.execute("UPDATE " + ITEMS + " SET id = id + 1");
                fail("two conflicts, one retry");
            }
            catch (IllegalStateException e) {
                assertTrue(e.getMessage(), IcebergCatalog.isConflict(e));
            }
            assertEquals(2, catalog.conflicts.get());
            catalog.conflictsToForce.set(0);
            assertEquals(List.of(1L), db.query("SELECT id FROM " + ITEMS).list(Long.class));
        }
    }

    @Test
    public void aTransactionThatConflictsThrowsCommitConflictAndIsNotRunAgain() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            catalog.conflictsToForce.set(1);
            AtomicInteger runs = new AtomicInteger();
            try {
                db.inTransaction(tx -> {
                    runs.incrementAndGet();
                    tx.execute("UPDATE " + ITEMS + " SET id = id + 1");
                });
                fail("the commit conflicted");
            }
            catch (CommitConflict e) {
                assertTrue(e.getMessage(), e.getMessage().contains("another writer committed first"));
            }
            assertEquals(1, runs.get());
            assertEquals(List.of(1L), db.query("SELECT id FROM " + ITEMS).list(Long.class));
        }
    }

    @Test
    public void retryingRunsTheWorkAgainUntilItCommits() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            catalog.conflictsToForce.set(2);
            AtomicInteger runs = new AtomicInteger();

            db.retrying(tx -> {
                runs.incrementAndGet();
                tx.execute("UPDATE " + ITEMS + " SET id = id + 1");
            });

            assertEquals(3, runs.get());
            assertEquals(List.of(2L), db.query("SELECT id FROM " + ITEMS).list(Long.class));
        }
    }

    @Test
    public void aDeleteThatMeetsAnotherDatabasesInsertIsRunAgainOnTheNewTable() {
        try (Quack first = open(); Quack second = open()) {
            createItems(first);
            first.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a'), (2, 'b')");
            AtomicInteger runs = new AtomicInteger();

            first.retrying(tx -> {
                tx.execute("DELETE FROM " + ITEMS + " WHERE id = 1");
                if (runs.incrementAndGet() == 1) {
                    second.execute("INSERT INTO " + ITEMS + " VALUES (3, 'c')");
                }
            });

            assertEquals("DuckDB's own conflict check refused the first commit", 2, runs.get());
            assertEquals(List.of(2L, 3L), first.query("SELECT id FROM " + ITEMS + " ORDER BY id").list(Long.class));
        }
    }

    @Test
    public void withoutACatalogNothingIsRetried() {
        try (Quack db = Quack.inMemory()) {
            AtomicInteger runs = new AtomicInteger();
            try {
                db.retrying(tx -> {
                    runs.incrementAndGet();
                    throw new CommitConflict("Iceberg Conflict_409", null);
                });
                fail();
            }
            catch (CommitConflict expected) {
                assertEquals(1, runs.get());
            }
        }
    }

    // ---------- opening ----------

    @Test
    public void aHardenedDatabaseReachesItsCatalogAndOnlyItsFolders() {
        Properties hardened = new Properties();
        hardened.setProperty("allowed_directories", "['s3://lake/']");
        hardened.setProperty("enable_external_access", "false");
        hardened.setProperty("lock_configuration", "true");
        try (Quack db = Quack.builder(null).settings(hardened).objectStore(store()).catalog("lake", lake()).open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            assertEquals(1, count(db));
            try {
                db.query("SELECT count(*) FROM read_csv('s3://other/a.csv')").scalar(Long.class);
                fail("only s3://lake/ and the catalog are allowed");
            }
            catch (RuntimeException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("disabled by configuration"));
            }
            try {
                db.execute("SET enable_external_access = true");
                fail("the configuration is locked");
            }
            catch (RuntimeException expected) {
                // locked
            }
        }
    }

    @Test
    public void aTokenIsSentWithEveryRequestAndShownNowhere() {
        catalog.requiredToken = "t0ken-value";
        IcebergCatalog withToken = lake().token("t0ken-value");
        try (Quack db = Quack.builder(null).objectStore(store()).catalog("lake", withToken).open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            assertEquals(1, count(db));
        }
        assertTrue(catalog.authorizations.stream().allMatch("Bearer t0ken-value"::equals));
        assertFalse(withToken.toString().contains("t0ken-value"));

        catalog.requiredToken = "another";
        try (Quack ignored = Quack.builder(null).objectStore(store()).catalog("lake", withToken).open()) {
            fail("the catalog refuses the token");
        }
        catch (IllegalStateException e) {
            assertFalse(e.getMessage(), e.getMessage().contains("t0ken-value"));
            assertTrue(e.getMessage(), e.getMessage().contains("Could not attach the catalog"));
        }
    }

    @Test
    public void clientCredentialsAreExchangedForAToken() {
        catalog.requiredToken = catalog.issuedToken;
        IcebergCatalog withClient = lake().oauth2("reader", "client-s3cret", catalog.tokenEndpoint());
        try (Quack db = Quack.builder(null).objectStore(store()).catalog("lake", withClient).open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            db.execute("INSERT INTO " + ITEMS + " VALUES (2, 'b')");
            assertEquals(2, count(db));
            assertEquals("an old snapshot removed through the catalog's API", 1, db.tidy(ITEMS, Duration.ZERO).snapshotsRemoved());
        }
        String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("reader:client-s3cret".getBytes(StandardCharsets.UTF_8));
        assertTrue(catalog.tokenRequests.toString(), catalog.tokenRequests.stream()
                .allMatch(r -> r.startsWith(basic + " ") && r.contains("grant_type=client_credentials")));
        assertTrue("tidy fetched a token of its own", catalog.tokenRequests.size() >= 2);
        assertFalse(withClient.toString().contains("client-s3cret"));
    }

    @Test
    public void aCatalogNeedsAURLAndANameOfItsOwn() {
        for (String bad : new String[]{"localhost:8181", "ftp://host/catalog", "not a url"}) {
            try {
                IcebergCatalog.rest(bad);
                fail(bad);
            }
            catch (IllegalArgumentException expected) {
                // refused
            }
        }
        try {
            Quack.builder(null).catalog("lake", lake()).catalog("lake", lake());
            fail("two catalogs under one name");
        }
        catch (IllegalArgumentException expected) {
            // refused
        }
    }

    // ---------- tidying ----------

    @Test
    public void tidyMergesFilesRemovesOldSnapshotsAndDeletesWhatNothingRefersTo() {
        try (Quack db = open()) {
            createItems(db);
            for (int i = 0; i < 12; i++) {
                db.execute("INSERT INTO " + ITEMS + " VALUES (?, 'n')", i);
            }
            db.execute("DELETE FROM " + ITEMS + " WHERE id = 0");
            assertEquals(13, catalog.snapshots("demo", "items").size());
            int dataFiles = dataFiles();

            Tidied tidied = db.tidy(ITEMS, Duration.ZERO);

            assertEquals(12, tidied.filesMerged());
            assertEquals(1, tidied.filesWritten());
            assertEquals("all but the current snapshot", 13, tidied.snapshotsRemoved());
            assertEquals(1, catalog.snapshots("demo", "items").size());
            assertTrue(tidied.toString(), tidied.filesDeleted() >= dataFiles);
            assertEquals(0, tidied.filesKept());
            List<String> inUse = db.query("SELECT file_path FROM iceberg_metadata(" + ITEMS + ") WHERE status <> 'DELETED'"
                    + " ORDER BY 1").list(String.class);
            assertEquals("what the table uses and nothing else",
                    inUse.stream().map(f -> f.substring("s3://".length())).toList(),
                    storage.objects.keySet().stream().filter(k -> k.startsWith("lake/demo/items/data/")).sorted().toList());
            assertEquals(11, count(db));
            assertEquals(Long.valueOf(66), db.query("SELECT sum(id) FROM " + ITEMS).scalar(Long.class));
        }
        try (Quack fresh = open()) {
            assertEquals("a database that never saw the old files reads the table", 11, count(fresh));
        }
    }

    @Test
    public void tidyKeepsWhatIsNewerThanItIsToldToKeep() {
        try (Quack db = open()) {
            createItems(db);
            for (int i = 0; i < 6; i++) {
                db.execute("INSERT INTO " + ITEMS + " VALUES (?, 'n')", i);
            }
            String stray = "lake/demo/items/data/another-writers-uncommitted.parquet";
            storage.objects.put(stray, "not yet committed".getBytes(StandardCharsets.UTF_8));
            storage.written.put(stray, System.currentTimeMillis());

            Tidied tidied = db.tidy(ITEMS, Duration.ofHours(1));

            assertEquals("no snapshot is an hour old", 0, tidied.snapshotsRemoved());
            assertEquals(0, tidied.filesDeleted());
            assertTrue(tidied.filesKept() >= 1);
            assertTrue(storage.objects.containsKey(stray));
            assertEquals(6, count(db));

            storage.written.put(stray, System.currentTimeMillis() - Duration.ofHours(2).toMillis());
            Tidied later = db.tidy(ITEMS, Duration.ofHours(1));
            assertFalse("two hours old, referred to by nothing", storage.objects.containsKey(stray));
            assertEquals(1, later.filesDeleted());
            assertEquals(6, count(db));
        }
    }

    @Test
    public void tidyTouchesNothingOutsideTheTablesOwnFolders() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("CREATE TABLE lake.demo.items2 (id BIGINT)");
            db.execute("INSERT INTO lake.demo.items2 VALUES (1)");
            db.execute("INSERT INTO lake.demo.items2 VALUES (2)");
            for (int i = 0; i < 3; i++) {
                db.execute("INSERT INTO " + ITEMS + " VALUES (?, 'n')", i);
            }
            String beside = "lake/demo/items-notes.txt";
            storage.objects.put(beside, new byte[]{1});
            storage.written.put(beside, 0L);
            long items2Files = storage.objects.keySet().stream().filter(k -> k.startsWith("lake/demo/items2/")).count();

            db.tidy(ITEMS, Duration.ZERO);

            assertTrue(storage.objects.containsKey(beside));
            assertEquals(items2Files, storage.objects.keySet().stream().filter(k -> k.startsWith("lake/demo/items2/")).count());
            assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM lake.demo.items2").scalar(Long.class));
            assertTrue(storage.deleted.stream().allMatch(k -> k.startsWith("lake/demo/items/")));
        }
    }

    @Test
    public void tidyDeletesNothingWhenTheTableKeepsFilesElsewhere() {
        try (Quack db = open()) {
            createItems(db);
            db.execute("INSERT INTO " + ITEMS + " VALUES (1, 'a')");
            db.execute("INSERT INTO " + ITEMS + " VALUES (2, 'b')");
            db.query("SELECT * FROM set_iceberg_table_properties(" + ITEMS + ", {'write.data.path': 's3://lake/elsewhere'})").list(Object.class);

            Tidied tidied = db.tidy(ITEMS, Duration.ZERO);

            assertEquals(0, tidied.filesDeleted());
            assertTrue(storage.deleted.isEmpty());
            assertEquals(2, count(db));
        }
    }

    @Test
    public void tidyRetriesWhenAnotherWriterCommitsFirst() {
        try (Quack db = open()) {
            createItems(db);
            for (int i = 0; i < 6; i++) {
                db.execute("INSERT INTO " + ITEMS + " VALUES (?, 'n')", i);
            }
            catalog.conflictsToForce.set(1);

            Tidied tidied = db.tidy(ITEMS, Duration.ZERO);

            assertEquals(1, catalog.conflicts.get());
            assertEquals(6, tidied.filesMerged());
            assertEquals(6, count(db));
        }
    }

    @Test
    public void tidyNeedsATableInAnAttachedCatalog() {
        try (Quack db = open()) {
            for (String bad : new String[]{"items", "demo.items", "other.demo.items", null}) {
                try {
                    db.tidy(bad, Duration.ZERO);
                    fail(bad);
                }
                catch (IllegalArgumentException expected) {
                    // refused
                }
            }
            try {
                db.tidy(ITEMS, Duration.ofSeconds(-1));
                fail("negative");
            }
            catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    private int dataFiles() {
        return (int) storage.objects.keySet().stream().filter(k -> k.startsWith("lake/demo/items/data/")).count();
    }

    @Test
    public void aConflictIsRecognisedInBothOfDuckDbsForms() {
        assertTrue(IcebergCatalog.isConflict(new IllegalStateException("x", new java.sql.SQLException(
                "TransactionContext Error: Failed to commit: Failed to commit Iceberg transaction: DELETE on \"c\""
                        + " conflicts with a concurrent commit (scanned snapshot 1, now at 2); re-run the DELETE."))));
        assertTrue(IcebergCatalog.isConflict(new java.sql.SQLException("TransactionContext Error: Failed to commit:"
                + " Failed to commit Iceberg transaction: Request to 'http://h/catalog/v1' returned a non-200 status"
                + " code (Conflict_409).")));
        assertFalse(IcebergCatalog.isConflict(new java.sql.SQLException("Constraint Error: Duplicate key")));
        assertFalse(IcebergCatalog.isConflict(new java.sql.SQLException("Failed to commit Iceberg transaction: HTTP 500")));
    }
}
