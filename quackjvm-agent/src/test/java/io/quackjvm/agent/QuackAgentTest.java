package io.quackjvm.agent;

import io.quackjvm.agent.catalog.Catalog;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.guard.QueryTimedOut;
import io.quackjvm.agent.live.LiveTable;
import io.quackjvm.agent.live.LiveTables;
import io.quackjvm.agent.udf.Udfs;
import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.metrics.QuackMetrics;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The agent features through a {@link Quack}: each borrows a connection, and none holds one. */
public class QuackAgentTest {

    private static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");

    public record Car(int carId, String make, double price) {
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private QuackMetrics metrics;
    private Quack db;

    @Before
    public void open() {
        metrics = new QuackMetrics();
        db = Quack.builder(folder.getRoot().toPath().resolve("live.duckdb").toString())
                .metrics(metrics)
                .connections(2)
                .open();
        db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, count INTEGER)");
        db.execute("INSERT INTO item VALUES (1, 10), (2, 20)");
    }

    @After
    public void close() {
        db.close();
    }

    private double inUse() {
        return metrics.snapshot().gauge(QuackMetrics.CONNECTIONS_IN_USE);
    }

    @Test
    public void aGuardedQueryTimesOutAndGivesItsConnectionBack() {
        try {
            GuardedQuery.on(db, QueryPolicy.readOnly().timeout(Duration.ofMillis(100)))
                    .run("SELECT count(*) FROM range(15000) a, range(15000) b WHERE (a.range * 7 + b.range) % 1000003 = 1");
            fail("the guarded query should have timed out");
        }
        catch (QueryTimedOut expected) {
            // interrupted
        }
        assertEquals(0.0, inUse(), 0.0);
        assertEquals(Long.valueOf(2), db.query("SELECT count(*) FROM item").scalar(Long.class));
    }

    @Test
    public void theCatalogDescribesTheDatabaseWithoutHoldingAConnection() {
        assertTrue(Catalog.of(db).tables().stream().anyMatch(table -> table.name().equals("item")));
        assertEquals(0.0, inUse(), 0.0);
    }

    @Test
    public void aHardenedDatabaseStillShipsToTheHistoryItWasOpenedWith() throws Exception {
        MeasureTable measure = MeasureTable.named("m").fields("a", "point").writtenBy("svc_a").build();
        try (Quack hardened = Quack.builder(folder.getRoot().toPath().resolve("hard.duckdb").toString())
                .history(folder.getRoot().toPath().resolve("hard-history.duckdb").toString())
                .open()) {
            // After open, which has attached the history: hardening stops a database attaching files.
            Hardening.apply(hardened);
            try (Connection connection = hardened.connection()) {
                assertTrue(Hardening.isHardened(connection));
            }
            hardened.create(measure);
            hardened.commit(MeasureRefresh.at(NINE, "r1").full(measure,
                    measure.batch().record(3).put(10, "x", "5y").put(20, "x", "10y").build()));
            assertEquals(1, hardened.ship(measure).entries());
        }
    }

    @Test
    public void liveTablesAndFunctionsAreSeenEverywhereAndGoWithTheDatabase() {
        List<Car> cars = List.of(new Car(1, "a", 1.0), new Car(2, "b", 2.0));
        LiveTable table = db.register(root -> LiveTables.register(root, "car", cars, ColumnarLayout.ofRecord(Car.class)));
        db.register(root -> Udfs.register(root, "twice", Double.class, Double.class, x -> x * 2));

        assertEquals(Double.valueOf(6.0), db.query("SELECT sum(twice(price)) FROM car").scalar(Double.class));
        db.inTransaction(tx -> assertEquals(Long.valueOf(2),
                tx.query("SELECT count(*) FROM car").scalar(Long.class)));

        db.close();
        assertFalse(table.isOpen());
    }

    @Test
    public void twoDatabasesInOneJvmKeepTheirOwnLiveTables() {
        try (Quack other = Quack.inMemory()) {
            db.register(root -> LiveTables.register(root, "car", List.of(new Car(1, "a", 1.0)),
                    ColumnarLayout.ofRecord(Car.class)));
            other.register(root -> LiveTables.register(root, "car",
                    List.of(new Car(1, "a", 1.0), new Car(2, "b", 2.0), new Car(3, "c", 3.0)),
                    ColumnarLayout.ofRecord(Car.class)));
            assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM car").scalar(Long.class));
            assertEquals(Long.valueOf(3), other.query("SELECT count(*) FROM car").scalar(Long.class));
        }
        assertEquals(Long.valueOf(1), db.query("SELECT count(*) FROM car").scalar(Long.class));
    }

    @Test
    public void aSandboxIsADifferentDatabaseThatCannotReachFiles() {
        db.register(root -> LiveTables.register(root, "car", List.of(new Car(1, "a", 1.0)),
                ColumnarLayout.ofRecord(Car.class)));
        try (Quack sandbox = Hardening.openSandbox()) {
            try {
                sandbox.query("SELECT count(*) FROM car").scalar(Long.class);
                fail("the sandbox saw the application's table");
            }
            catch (RuntimeException expected) {
                // a different database
            }
            try {
                sandbox.query("SELECT * FROM read_csv('" + folder.getRoot() + "/x.csv')").list(Object[].class);
                fail("the sandbox read a file");
            }
            catch (RuntimeException expected) {
                // hardened
            }
            assertEquals(0, GuardedQuery.on(sandbox, QueryPolicy.readOnly()).run("SELECT 1 WHERE false").getRowCount());
        }
    }
}
