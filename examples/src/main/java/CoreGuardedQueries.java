/*
 * Running SQL that came from somewhere you do not control - a language model, a user, another
 * service - against a database that has real data in it.
 *
 * The check is DuckDB's own parser, not a pattern: json_serialize_sql parses the text and refuses
 * anything that is not a query, which is everything that writes, attaches, loads, copies or
 * configures - a CTE with an INSERT inside it included - and counts the statements so that a second
 * one cannot ride along.
 *
 * That last part matters more than it sounds. With this driver, preparing a multi-statement string
 * executes all but the last statement: prepareStatement("DROP TABLE u; SELECT 1") drops the table.
 * So a guard that "checks" SQL by preparing it has already lost. Nothing here prepares a statement
 * until the parser has said it is a single query.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreGuardedQueries.java
 */

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.guard.GuardedQuery;
import io.quackjvm.core.guard.GuardedResult;
import io.quackjvm.core.guard.Hardening;
import io.quackjvm.core.guard.QueryPolicy;
import io.quackjvm.core.guard.QueryRejected;
import io.quackjvm.core.guard.QueryTimedOut;
import io.quackjvm.core.guard.ResultTruncated;
import org.duckdb.DuckDBConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

public class CoreGuardedQueries {

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            Sql.execute(connection, "CREATE TABLE car (id INTEGER, make VARCHAR, price DOUBLE)");
            Sql.execute(connection, "INSERT INTO car SELECT i, 'make-' || (i % 4), 5000.0 + (i % 900)"
                    + " FROM range(200000) t(i)");

            GuardedQuery guard = GuardedQuery.on(connection, QueryPolicy.readOnly()
                    .timeout(Duration.ofSeconds(2))
                    .maxRows(50));

            System.out.println("What it runs");
            System.out.println("------------");
            show(guard, "SELECT make, count(*) AS cars, round(avg(price)) AS avg_price"
                    + " FROM car GROUP BY 1 ORDER BY 1");

            System.out.println("\nWhat it refuses, and what it says");
            System.out.println("---------------------------------");
            for (String sql : List.of(
                    "DROP TABLE car",
                    "INSERT INTO car VALUES (1, 'x', 1.0)",
                    "WITH x AS (DELETE FROM car RETURNING *) SELECT count(*) FROM x",
                    "SELECT 1; DROP TABLE car",
                    "SELECT 1 -- hidden\n; DROP TABLE car",
                    "ATTACH ':memory:' AS other",
                    "COPY car TO '/tmp/stolen.csv'",
                    "INSTALL httpfs",
                    "SET threads = 1",
                    "PIVOT car ON make USING sum(price)",
                    "SELEKT 1")) {
                System.out.printf("   %-58s %s%n", abbreviate(sql), refusal(guard, sql));
            }
            System.out.printf("%ncar still has %,d rows, and still exists: %s%n",
                    Sql.queryLong(connection, "SELECT count(*) FROM car", List.of()),
                    Sql.queryLong(connection, "SELECT count(*) FROM duckdb_tables() WHERE table_name = 'car'",
                            List.of()) == 1);

            System.out.println("\nWhat the driver would have done without the check");
            System.out.println("-------------------------------------------------");
            try (Connection other = DriverManager.getConnection("jdbc:duckdb:")) {
                Sql.execute(other, "CREATE TABLE secrets (a INTEGER)");
                // Not executed - prepared. That is the whole point.
                try (PreparedStatement ignored = other.prepareStatement("DROP TABLE secrets; SELECT 1")) {
                    // no execute() call anywhere
                }
                System.out.println("   prepareStatement(\"DROP TABLE secrets; SELECT 1\"), never executed");
                System.out.println("   secrets still exists: " + (Sql.queryLong(other,
                        "SELECT count(*) FROM duckdb_tables() WHERE table_name = 'secrets'", List.of()) == 1));
            }

            System.out.println("\nThe caps");
            System.out.println("--------");
            System.out.printf("   %-58s %s%n", "SELECT * FROM car   (50-row cap)", refusal(guard, "SELECT * FROM car"));
            long started = System.nanoTime();
            String timedOut = refusal(guard, "SELECT count(*) FROM range(60000000000) WHERE range % 7 = 0");
            System.out.printf("   %-58s %s%n", "a query that would run for minutes (2s timeout)", timedOut);
            System.out.printf("   %-58s %.1f s%n", "   time actually spent", (System.nanoTime() - started) / 1e9);

            System.out.println("\nWhat the check costs");
            System.out.println("--------------------");
            String question = "SELECT make, count(*) FROM car GROUP BY 1";
            for (int i = 0; i < 50; i++) {
                guard.run(question);
                plain(connection, question);
            }
            System.out.printf("   guarded:   %.2f ms%n", median(() -> guard.run(question)));
            System.out.printf("   unguarded: %.2f ms%n", median(() -> plain(connection, question)));
            System.out.printf("   the check alone, without running anything: %.2f ms%n",
                    median(() -> guard.whyRejected(question)));

            System.out.println("\nHardening: taking the way out away as well");
            System.out.println("------------------------------------------");
            Path file = Files.createTempFile("guard-example", ".csv");
            Files.writeString(file, "a\n1\n");
            System.out.printf("   %-58s %s%n", "reading a local file, checks only",
                    refusalOrRan(guard, "SELECT * FROM read_csv('" + file + "')"));
            try (Connection hardened = DriverManager.getConnection("jdbc:duckdb:")) {
                GuardedQuery locked = GuardedQuery.on(hardened,
                        QueryPolicy.readOnly().hardening(Hardening.DATABASE));
                System.out.printf("   %-58s %s%n", "the same, with Hardening.DATABASE",
                        refusalOrRan(locked, "SELECT * FROM read_csv('" + file + "')"));
                System.out.printf("   %-58s %s%n", "and the environment",
                        refusalOrRan(locked, "SELECT getenv('HOME')"));
            }
            Files.deleteIfExists(file);

            try (Connection sandbox = Hardening.sandbox()) {
                Sql.execute(sandbox, "CREATE TABLE snapshot AS SELECT 1 AS a");
                System.out.println("   a sandbox database still works as a database: "
                        + Sql.queryLong(sandbox, "SELECT count(*) FROM snapshot", List.of()) + " row");
                System.out.println("   and cannot be unlocked: " + attempt(sandbox,
                        "SET enable_external_access = true"));
            }

            System.out.println("\nAnd what hardening would cost this database");
            System.out.println("-------------------------------------------");
            System.out.println("   archiving a measure, exporting Parquet and reading it back all need file");
            System.out.println("   access, so they stop working on a hardened database. That is why it is off");
            System.out.println("   by default, and why the sandbox above is a second database rather than this one.");
        }
    }

    static void show(GuardedQuery guard, String sql) {
        GuardedResult result = guard.run(sql);
        System.out.println("   " + sql);
        for (String line : result.toText().split("\n")) {
            System.out.println("      " + line);
        }
        System.out.printf("   %d rows, %d bytes, %.1f ms%n", result.getRowCount(), result.getBytes(),
                result.getTook().toNanos() / 1e6);
    }

    /** What the guard said, in one line. */
    static String refusal(GuardedQuery guard, String sql) {
        try {
            guard.run(sql);
            return "RAN";
        }
        catch (QueryRejected e) {
            return shorten(e.getMessage());
        }
        catch (ResultTruncated e) {
            return shorten(e.getMessage());
        }
        catch (QueryTimedOut e) {
            return shorten(e.getMessage());
        }
    }

    static String refusalOrRan(GuardedQuery guard, String sql) {
        String outcome = refusal(guard, sql);
        return outcome.equals("RAN") ? "ran - the parser has no reason to refuse it" : outcome;
    }

    static String attempt(Connection connection, String sql) {
        try {
            Sql.execute(connection, sql);
            return "allowed";
        }
        catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return shorten(root.getMessage());
        }
    }

    static void plain(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rows.getObject(1);
            }
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static double median(Runnable work) {
        long[] timings = new long[21];
        for (int i = 0; i < timings.length; i++) {
            long started = System.nanoTime();
            work.run();
            timings[i] = System.nanoTime() - started;
        }
        Arrays.sort(timings);
        return timings[timings.length / 2] / 1e6;
    }

    /** The first sentence of a refusal, which is the part that says what happened. */
    static String shorten(String message) {
        String line = message.split("\n")[0];
        int stop = line.indexOf(". ");
        if (stop > 0 && stop < 100) {
            return line.substring(0, stop + 1);
        }
        return line.length() > 104 ? line.substring(0, 101) + "..." : line;
    }

    static String abbreviate(String sql) {
        String line = sql.replace('\n', ' ');
        return line.length() > 56 ? line.substring(0, 53) + "..." : line;
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * What it runs
 * ------------
 *    SELECT make, count(*) AS cars, round(avg(price)) AS avg_price FROM car GROUP BY 1 ORDER BY 1
 *       make    cars   avg_price
 *       make-0  50000  5448.0   
 *       make-1  50000  5449.0   
 *       make-2  50000  5450.0   
 *       make-3  50000  5451.0   
 *    4 rows, 112 bytes, 5.3 ms
 *
 * What it refuses, and what it says
 * ---------------------------------
 *    DROP TABLE car                                             DROP is not a query, so it will not be run.
 *    INSERT INTO car VALUES (1, 'x', 1.0)                       INSERT is not a query, so it will not be run.
 *    WITH x AS (DELETE FROM car RETURNING *) SELECT count(...   WITH is not a query, so it will not be run.
 *    SELECT 1; DROP TABLE car                                   SELECT is not a query, so it will not be run.
 *    SELECT 1 -- hidden ; DROP TABLE car                        SELECT is not a query, so it will not be run.
 *    ATTACH ':memory:' AS other                                 ATTACH is not a query, so it will not be run.
 *    COPY car TO '/tmp/stolen.csv'                              COPY is not a query, so it will not be run.
 *    INSTALL httpfs                                             INSTALL is not a query, so it will not be run.
 *    SET threads = 1                                            SET is not a query, so it will not be run.
 *    PIVOT car ON make USING sum(price)                         PIVOT cannot be checked: DuckDB's parser will not serialize a pivot, so there is no way to tell it ap...
 *    SELEKT 1                                                   SELEKT is not a query, so it will not be run.
 *
 * car still has 200,000 rows, and still exists: true
 *
 * What the driver would have done without the check
 * -------------------------------------------------
 *    prepareStatement("DROP TABLE secrets; SELECT 1"), never executed
 *    secrets still exists: false
 *
 * The caps
 * --------
 *    SELECT * FROM car   (50-row cap)                           The query returns more than 50 rows, which is the policy's limit.
 *    a query that would run for minutes (2s timeout)            The query was cancelled after 2000 ms.
 *       time actually spent                                     2.0 s
 *
 * What the check costs
 * --------------------
 *    guarded:   2.59 ms
 *    unguarded: 2.07 ms
 *    the check alone, without running anything: 0.19 ms
 *
 * Hardening: taking the way out away as well
 * ------------------------------------------
 *    reading a local file, checks only                          ran - the parser has no reason to refuse it
 *    the same, with Hardening.DATABASE                          DuckDB refused to run this: Permission Error: Cannot access file "/var/folders/p2/3wph7cw515d_ch9lsq6...
 *    and the environment                                        DuckDB refused to run this: Catalog Error: Scalar Function with name getenv does not exist!
 *    a sandbox database still works as a database: 1 row
 *    and cannot be unlocked: Invalid Input Error: Cannot change configuration option "enable_external_access" - the configuration ...
 *
 * And what hardening would cost this database
 * -------------------------------------------
 *    archiving a measure, exporting Parquet and reading it back all need file
 *    access, so they stop working on a hardened database. That is why it is off
 *    by default, and why the sandbox above is a second database rather than this one.
 *
 * The line that matters most is "secrets still exists: false". That table was dropped by a
 * prepareStatement call that was never executed - which is what this driver does with a
 * multi-statement string, and why the check parses the text instead of preparing it.
 *
 * The check costs about half a millisecond: two parses, one of the statement and one of the wrapped
 * form that carries the row cap. Everything else is what the query itself costs.
 *
 * Reading a local file is a legitimate query as far as the parser is concerned, which is what
 * hardening is for - at the price of the whole database losing file access, archiving and Parquet
 * included. Hence the sandbox: a second database with no way out, which can still be given live data.
 */
