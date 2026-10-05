/*
 * The whole of quackjvm through one object, with no connection in sight: records written and asked
 * questions of, a table and a measure written together in one transaction, a failed transaction
 * that leaves nothing behind, a measure read as of a point from its history, a Java list queried as
 * a table, a Java method called from SQL, many threads at once, and a query nobody vetted, run in a
 * sandbox.
 *
 * Quack owns the connections. Every call borrows one and gives it back; a transaction holds one
 * from start to end; what the database shares - its history, its functions, its live tables - is
 * set up once. db.connection() is the way down to DuckDB itself, for anything not wrapped.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreEntryPoint.java
 */

import io.quackjvm.core.Quack;
import io.quackjvm.core.guard.QueryPolicy;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.metrics.QuackMetrics;
import io.quackjvm.core.sql.SqlRow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CoreEntryPoint {

    static final Instant NINE = Instant.parse("2026-09-30T09:00:00Z");
    static final Instant TEN = NINE.plusSeconds(3600);
    static final String SLOW = "SELECT count(*) FROM range(15000) a, range(15000) b"
            + " WHERE (a.range * 7 + b.range) % 1000003 = 1";

    public record Car(int carId, String make, double price) {
    }

    public record Reading(int readingId, String site, double celsius) {
    }

    public record SiteStats(String site, long readings, double average) {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("entry-point");
        QuackMetrics metrics = new QuackMetrics();
        try (Quack db = Quack.builder(directory.resolve("live.duckdb").toString())
                .history(directory.resolve("history.duckdb").toString())
                .metrics(metrics)
                .open()) {

            System.out.println("Objects in, answers out");
            System.out.println("-----------------------");
            ColumnarLayout<Reading> readings = ColumnarLayout.ofRecord(Reading.class);
            db.createTable("reading", readings);
            List<Reading> written = new ArrayList<>();
            for (int i = 0; i < 100_000; i++) {
                boolean kitchen = i % 3 == 0;
                written.add(new Reading(i, kitchen ? "kitchen" : "hall", (kitchen ? 21 : 18) + (i % 70) / 10.0));
            }
            long writing = System.nanoTime();
            db.write("reading", readings, written);
            System.out.printf("   100,000 records written in %d ms%n", (System.nanoTime() - writing) / 1_000_000);
            System.out.println("   average in the kitchen: " + db.query("SELECT round(avg(celsius), 2) FROM reading"
                    + " WHERE site = ?", "kitchen").scalar(Double.class));
            for (SiteStats stats : db.query("SELECT site, count(*), round(avg(celsius), 2) FROM reading GROUP BY 1"
                    + " ORDER BY 1").records(SiteStats.class)) {
                System.out.println("   " + stats);
            }
            db.write("reading", readings, List.of(new Reading(0, "kitchen", 99.0)));
            System.out.println("   written again under key 0, it replaces: "
                    + db.query("SELECT count(*) FROM reading").scalar(Long.class) + " rows, reading 0 is "
                    + db.query("SELECT celsius FROM reading WHERE readingId = 0").scalar(Double.class));

            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, count INTEGER)");
            db.execute("INSERT INTO item VALUES (1, 10), (2, 20)");
            MeasureTable exposure = MeasureTable.named("exposure")
                    .fields("group", "point")
                    .order("point", "5y", "10y")
                    .writtenBy("svc_a")
                    .build();
            db.create(exposure);
            String values = "SELECT count(*) FROM " + exposure.getValueTable();

            System.out.println("\nA table and a measure, written in one transaction");
            System.out.println("-------------------------------------------------");
            db.inTransaction(tx -> {
                tx.execute("UPDATE item SET count = count + ? WHERE id = ?", 5, 1);
                tx.commit(MeasureRefresh.at(NINE, "09:00").full(exposure, exposure.batch()
                        .record(3).put(10, "g1", "5y").put(20, "g1", "10y")
                        .build()));
            });
            System.out.println("   item 1 now counts " + db.query("SELECT count FROM item WHERE id = 1").scalar(Integer.class)
                    + ", and the measure holds " + db.query(values).scalar(Long.class) + " values");

            System.out.println("\nA transaction that throws leaves nothing behind - of either");
            System.out.println("------------------------------------------------------------");
            try {
                db.inTransaction(tx -> {
                    tx.execute("UPDATE item SET count = 0 WHERE id = 1");
                    tx.commit(MeasureRefresh.at(TEN, "10:00").increment(exposure, exposure.batch()
                            .record(3).put(99, "g1", "5y").put(20, "g1", "10y")
                            .build()));
                    throw new IllegalStateException("changed its mind after writing both");
                });
            }
            catch (IllegalStateException e) {
                System.out.println("   threw: " + e.getMessage());
            }
            System.out.println("   item 1 still counts " + db.query("SELECT count FROM item WHERE id = 1").scalar(Integer.class)
                    + ", record 3's 5y is still " + db.query("SELECT v.value FROM " + exposure.getValueTable()
                    + " v JOIN " + exposure.getKeyTable() + " k ON k.id = v.key_id WHERE k.point = '5y'").scalar(Double.class));
            db.commit(MeasureRefresh.at(TEN, "10:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(20, "g1", "10y")
                    .build()));
            System.out.println("   and the id 10:00 was never used, so it commits now: 5y is "
                    + db.query("SELECT v.value FROM " + exposure.getValueTable() + " v JOIN " + exposure.getKeyTable()
                    + " k ON k.id = v.key_id WHERE k.point = '5y'").scalar(Double.class));

            System.out.println("\nShipped to the history, and read as of each point");
            System.out.println("-------------------------------------------------");
            System.out.println("   shipped " + db.ship(exposure).entries() + " refreshes");
            for (Instant at : List.of(NINE, TEN)) {
                List<String> cells = new ArrayList<>();
                db.query(db.asOf(exposure, at).rows("group").columns("point")).forEachRow((SqlRow row) ->
                        cells.add(row.get("group") + " 5y=" + row.get("5y") + " 10y=" + row.get("10y")));
                System.out.println("   as of " + at + ": " + String.join(", ", cells));
            }

            System.out.println("\nA Java list as a table, and a Java method in SQL");
            System.out.println("------------------------------------------------");
            List<Car> cars = List.of(new Car(1, "a", 1000), new Car(2, "b", 2500), new Car(3, "a", 1800));
            db.live("car", cars, ColumnarLayout.ofRecord(Car.class));
            db.function("with_tax", Double.class, Double.class, price -> price * 1.2);
            db.query("SELECT make, count(*) AS cars, round(sum(with_tax(price)), 2) AS taxed FROM car"
                    + " GROUP BY make ORDER BY make").forEachRow((SqlRow row) ->
                    System.out.println("   " + row.get("make") + ": " + row.get("cars") + " cars, " + row.get("taxed")));
            List<String> names = new ArrayList<>();
            db.catalog().tables().forEach(table -> names.add(table.name()));
            System.out.println("   the catalog sees " + names);

            System.out.println("\nMany threads, one database");
            System.out.println("--------------------------");
            ExecutorService threads = Executors.newFixedThreadPool(8);
            List<Future<Long>> answers = new ArrayList<>();
            long started = System.nanoTime();
            for (int i = 0; i < 800; i++) {
                int id = 100 + i;
                answers.add(threads.submit(() -> {
                    db.execute("INSERT INTO item VALUES (?, ?)", id, 1);
                    return db.query("SELECT count(*) FROM item WHERE id = ?", id).scalar(Long.class);
                }));
            }
            long found = 0;
            for (Future<Long> answer : answers) {
                found += answer.get();
            }
            System.out.printf("   800 writes and 800 reads from 8 threads: %d of 800 read back, in %d ms%n",
                    found, (System.nanoTime() - started) / 1_000_000);
            Future<Long> slow = threads.submit(() -> db.query(SLOW).scalar(Long.class));
            Thread.sleep(50);
            long quick = System.nanoTime();
            db.query("SELECT count(*) FROM item").scalar(Long.class);
            long quickMs = (System.nanoTime() - quick) / 1_000_000;
            slow.get();
            threads.shutdown();
            System.out.printf("   a quick query while a slow one ran: %d ms - each on a connection of its own%n", quickMs);
            System.out.printf("   connections in use afterwards: %.0f%n",
                    metrics.snapshot().gauge(QuackMetrics.CONNECTIONS_IN_USE));

            System.out.println("\nThe way down, for what is not wrapped");
            System.out.println("-------------------------------------");
            try (Connection connection = db.connection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT database_name FROM duckdb_databases()"
                         + " WHERE NOT internal ORDER BY 1")) {
                List<String> databases = new ArrayList<>();
                while (rows.next()) {
                    databases.add(rows.getString(1));
                }
                System.out.println("   attached: " + databases + " - the history, attached once at open");
            }
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }

        System.out.println("\nA query nobody vetted, in a sandbox");
        System.out.println("-----------------------------------");
        try (Quack sandbox = Quack.sandbox()) {
            sandbox.live("car", List.of(new Car(1, "a", 1000), new Car(2, "b", 2500)), ColumnarLayout.ofRecord(Car.class));
            var guard = sandbox.guarded(QueryPolicy.readOnly());
            System.out.println("   SELECT count(*) FROM car -> " + guard.run("SELECT count(*) FROM car").getRows().get(0)[0]);
            for (String attempt : List.of("DROP VIEW car", "SELECT * FROM read_csv('/etc/hosts')")) {
                try {
                    guard.run(attempt);
                    System.out.println("   " + attempt + " -> ran (it should not have)");
                }
                catch (RuntimeException refused) {
                    System.out.println("   " + attempt + " -> refused: " + refused.getMessage().split("\n")[0]);
                }
            }
        }
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * Objects in, answers out
 * -----------------------
 *    100,000 records written in 102 ms
 *    average in the kitchen: 24.45
 *    SiteStats[site=hall, readings=66666, average=21.45]
 *    SiteStats[site=kitchen, readings=33334, average=24.45]
 *    written again under key 0, it replaces: 100000 rows, reading 0 is 99.0
 *
 * A table and a measure, written in one transaction
 * -------------------------------------------------
 *    item 1 now counts 15, and the measure holds 2 values
 *
 * A transaction that throws leaves nothing behind - of either
 * ------------------------------------------------------------
 *    threw: changed its mind after writing both
 *    item 1 still counts 15, record 3's 5y is still 10.0
 *    and the id 10:00 was never used, so it commits now: 5y is 12.0
 *
 * Shipped to the history, and read as of each point
 * -------------------------------------------------
 *    shipped 2 refreshes
 *    as of 2026-09-30T09:00:00Z: g1 5y=10.0 10y=20.0
 *    as of 2026-09-30T10:00:00Z: g1 5y=12.0 10y=20.0
 *
 * A Java list as a table, and a Java method in SQL
 * ------------------------------------------------
 *    a: 2 cars, 3360.0
 *    b: 1 cars, 3000.0
 *    the catalog sees [car, exposure_contribution, exposure_key, exposure_value, item, measure_refresh, measure_watermark, reading]
 *
 * Many threads, one database
 * --------------------------
 *    800 writes and 800 reads from 8 threads: 800 of 800 read back, in 108 ms
 *    a quick query while a slow one ran: 0 ms - each on a connection of its own
 *    connections in use afterwards: 0
 *
 * The way down, for what is not wrapped
 * -------------------------------------
 *    attached: [live, quack_history] - the history, attached once at open
 *
 * A query nobody vetted, in a sandbox
 * -----------------------------------
 *    SELECT count(*) FROM car -> 2
 *    DROP VIEW car -> refused: DROP is not a query, so it will not be run. Only statements that read are allowed: SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT and EXPLAIN. DuckDB's parser said: Only SELECT statements can be serialized to json!
 *    SELECT * FROM read_csv('/etc/hosts') -> refused: DuckDB refused to run this: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration
 *
 * Not one connection was opened, passed or closed by this code until the way-down section asked for
 * one. Records went in through the layout and came back as records, and a record written again under
 * its key replaced the old one in one transaction. The transaction that threw took both its writes
 * with it - the table's update and the measure's refresh - and because its refresh id was never
 * committed, the same id went in cleanly afterwards. The history was attached once, when the database
 * opened, and every later call saw it.
 *
 * Eight threads shared the database without sharing a connection: each call borrowed one and gave
 * it back, so a quick query issued while a slow one ran answered at once instead of queueing behind
 * it, and nothing was left borrowed at the end.
 *
 * The sandbox is another database altogether. It held only the two cars put into it, refused a
 * statement that was not a read, and could not open a file even when the statement was one.
 */
