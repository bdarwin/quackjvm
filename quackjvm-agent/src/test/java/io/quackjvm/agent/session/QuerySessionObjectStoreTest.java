package io.quackjvm.agent.session;

import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryFailed;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.core.ObjectStore;
import io.quackjvm.core.Quack;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QuerySessionObjectStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeObjectStore server;
    private ObjectStore store;

    @Before
    public void start() throws Exception {
        server = new FakeObjectStore();
        store = ObjectStore.s3(server.endpoint()).keys("reader", "s3cret");
        try (Quack writer = Quack.builder(null).objectStore(store).open()) {
            writer.execute("CREATE TABLE reading AS SELECT range AS id, range * 2 AS v FROM range(100)");
            writer.exportTable("reading", "s3://items/reading.parquet");
            writer.exportTable("reading", "s3://notes/reading.parquet");
        }
    }

    @After
    public void stop() {
        server.close();
    }

    @Test
    public void anAllowedUrlIsKeptAsItIsWritten() {
        Hardening.Options options = Hardening.options().allowedFolders("s3://items/", "https://example.net/data/");
        assertEquals(List.of("s3://items/", "https://example.net/data/"), options.getAllowedFolders());
        assertEquals(List.of("s3://items/a.parquet"), options.allowedFiles("s3://items/a.parquet").getAllowedFiles());
    }

    @Test
    public void aSessionReadsTheBucketItAllowsAndNoOther() throws Exception {
        Hardening.Options options = Hardening.options().allowedFolders("s3://items/").objectStore(store);
        try (QuerySession session = QuerySession.open(options, QueryPolicy.readOnly())) {
            session.register("remote", "SELECT * FROM read_parquet('s3://items/reading.parquet')");
            assertEquals(9900L, ((Number) session.run("SELECT sum(v) FROM remote").getRows().get(0)[0]).longValue());

            assertRefused(session, "SELECT count(*) FROM read_parquet('s3://notes/reading.parquet')");
            assertRefused(session, "SELECT count(*) FROM read_csv('/etc/hosts')");

            // Reopened to let a local folder in: the store and the registration come with it.
            Path local = folder.newFolder("local").toPath();
            Files.writeString(local.resolve("a.csv"), "x\n1\n");
            session.allowFolders(List.of(local));
            assertEquals(Long.valueOf(100), session.run("SELECT count(*) FROM remote").getRows().get(0)[0]);
            assertEquals(Long.valueOf(1), session.run("SELECT count(*) FROM read_csv('" + local.resolve("a.csv")
                    + "')").getRows().get(0)[0]);
        }
    }

    @Test
    public void aDatabaseHardenedWithAStoreLetsTheGuardReadIt() {
        Hardening.Options options = Hardening.options().allowedFolders("s3://items/");
        Quack.Builder builder = Quack.builder(null).settings(options.properties()).objectStore(store);
        try (Quack quack = builder.open()) {
            assertTrue(Hardening.isHardened(quack.sharedConnection()));
            assertEquals(Long.valueOf(100), GuardedQuery.on(quack, QueryPolicy.readOnly())
                    .run("SELECT count(*) FROM read_parquet('s3://items/*.parquet')").getRows().get(0)[0]);
        }
    }

    private static void assertRefused(QuerySession session, String sql) {
        try {
            session.run(sql);
            fail("Ran " + sql);
        }
        catch (QueryFailed e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Permission Error"));
        }
    }
}
