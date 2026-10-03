/*
 * What one DuckDB database shares between its connections, and what each connection keeps to itself -
 * the facts a single entry point to quackjvm has to be built on.
 *
 * Every connection here is duplicated from one root connection, which is how quackjvm's pool opens
 * them. What a duplicate sees decides what an entry point may register once for the whole database
 * and what it has to give each unit of work on its own.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreConnectionFacts.java
 */

import io.quackjvm.core.udf.Udfs;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreConnectionFacts {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("connection-facts");
        try (DuckDBConnection root = (DuckDBConnection) DriverManager.getConnection(
                "jdbc:duckdb:" + directory.resolve("live.duckdb"))) {

            System.out.println("What opening a connection costs");
            System.out.println("-------------------------------");
            for (int i = 0; i < 2_000; i++) {
                root.duplicate().close();
            }
            int opened = 20_000;
            long started = System.nanoTime();
            for (int i = 0; i < opened; i++) {
                root.duplicate().close();
            }
            System.out.printf("   duplicate() and close(): %.1f us each, over %,d%n",
                    (System.nanoTime() - started) / 1e3 / opened, opened);

            System.out.println("\nShared by the whole database");
            System.out.println("----------------------------");
            execute(root, "ATTACH '" + directory.resolve("history.duckdb") + "' AS history");
            try (Connection other = root.duplicate()) {
                System.out.println("   a database attached on one connection, seen on another:  "
                        + one(other, "SELECT count(*) FROM duckdb_databases() WHERE database_name = 'history'")
                        .equals("1"));
            }
            try (Connection registering = root.duplicate()) {
                Udfs.registerInt(registering, "plus_one", x -> x + 1);
            }
            try (Connection other = root.duplicate()) {
                System.out.println("   a Java function registered on a connection since closed: plus_one(41) = "
                        + one(other, "SELECT plus_one(41)"));
            }
            try (Connection setting = root.duplicate()) {
                execute(setting, "SET threads = 3");
            }
            try (Connection other = root.duplicate()) {
                System.out.println("   SET threads = 3 on one connection, read on another:      threads = "
                        + one(other, "SELECT current_setting('threads')"));
            }

            System.out.println("\nKept by each connection");
            System.out.println("-----------------------");
            try (Connection creating = root.duplicate()) {
                execute(creating, "CREATE TEMP TABLE staging (x INTEGER)");
                try (Connection other = root.duplicate()) {
                    String seen;
                    try {
                        one(other, "SELECT count(*) FROM staging");
                        seen = "yes";
                    }
                    catch (Exception notThere) {
                        seen = "no";
                    }
                    System.out.println("   a TEMP table, seen from another connection:              " + seen);
                }
            }
            execute(root, "CREATE TABLE t AS SELECT range AS x FROM range(200000)");
            try (Connection writer = root.duplicate(); Connection reader = root.duplicate()) {
                writer.setAutoCommit(false);
                execute(writer, "INSERT INTO t VALUES (-1)");
                System.out.println("   a row not yet committed, seen from another connection:   "
                        + (one(reader, "SELECT count(*) FROM t WHERE x = -1").equals("0") ? "no" : "yes"));
                writer.rollback();
            }

            System.out.println("\nOne connection, eight threads");
            System.out.println("-----------------------------");
            AtomicInteger failed = new AtomicInteger();
            AtomicInteger wrong = new AtomicInteger();
            ExecutorService threads = Executors.newFixedThreadPool(8);
            List<Future<?>> running = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                running.add(threads.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        try {
                            if (!one(root, "SELECT sum(x) FROM t").equals("19999900000")) {
                                wrong.incrementAndGet();
                            }
                        }
                        catch (Exception e) {
                            failed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> each : running) {
                each.get();
            }
            threads.shutdown();
            System.out.println("   1,600 reads on one shared connection: " + failed + " failed, " + wrong + " wrong");

            // A transaction belongs to the connection, not the thread: one thread's rollback takes the other's
            // write with it.
            root.setAutoCommit(false);
            Thread writing = new Thread(() -> {
                try {
                    execute(root, "INSERT INTO t VALUES (-2)");
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            writing.start();
            writing.join();
            Thread rollingBack = new Thread(() -> {
                try {
                    root.rollback();
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            rollingBack.start();
            rollingBack.join();
            root.setAutoCommit(true);
            System.out.println("   thread A inserts on the shared connection, thread B rolls back: A's row "
                    + (one(root, "SELECT count(*) FROM t WHERE x = -2").equals("0") ? "is gone" : "survived"));
        }
        finally {
            try (var walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static String one(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * What opening a connection costs
 * -------------------------------
 *    duplicate() and close(): 6.6 us each, over 20,000
 *
 * Shared by the whole database
 * ----------------------------
 *    a database attached on one connection, seen on another:  true
 *    a Java function registered on a connection since closed: plus_one(41) = 42
 *    SET threads = 3 on one connection, read on another:      threads = 3
 *
 * Kept by each connection
 * -----------------------
 *    a TEMP table, seen from another connection:              no
 *    a row not yet committed, seen from another connection:   no
 *
 * One connection, eight threads
 * -----------------------------
 *    1,600 reads on one shared connection: 0 failed, 0 wrong
 *    thread A inserts on the shared connection, thread B rolls back: A's row is gone
 *
 * So the database is the thing to share and the connection is not. A database attached, a function
 * registered or a setting made on any connection is there for all of them - an entry point can do
 * each once, on whichever connection is to hand, and every later connection sees it. A temp table
 * and a transaction belong to one connection, so each unit of work needs a connection of its own;
 * otherwise one thread's rollback is another thread's lost write.
 *
 * And a connection is cheap to open: 6.6 us. A pool is still worth having, for what a connection
 * accumulates - prepared statements, a staging table - but not to save opening one.
 */
