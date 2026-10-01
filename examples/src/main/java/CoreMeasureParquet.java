/*
 * The file side of a measure: what the history exports, what is in a file, and the three ways to
 * read one - with plain SQL and no quackjvm at all, over HTTP where the file lies, and into a database
 * that has never heard of the measure.
 *
 * A history exports contributions, not state: a row per value that moved, with the refresh it
 * belongs to beside it. The state at any point is the sum of the contributions from the last full
 * set at or before that point, and that rule is one SQL statement, which this example writes out so
 * that a reader with nothing but DuckDB and the files can use it.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreMeasureParquet.java [directory-to-keep-the-files-in]
 */

import com.sun.net.httpserver.HttpServer;
import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.measure.MeasureHistory;
import io.quackjvm.core.measure.MeasureRefresh;
import io.quackjvm.core.measure.MeasureTable;
import io.quackjvm.core.sql.Rows;
import io.quackjvm.core.sql.SqlRow;
import org.duckdb.DuckDBConnection;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CoreMeasureParquet {

    static final Instant MONDAY = Instant.parse("2026-09-28T09:00:00Z");
    static final Instant TUESDAY = Instant.parse("2026-09-29T09:00:00Z");

    public static void main(String[] args) throws Exception {
        // Kept after the run, so the files can be opened: pass a directory, or one is made under the temp dir.
        Path directory = args.length > 0 ? Files.createDirectories(Path.of(args[0]).toAbsolutePath())
                : Files.createTempDirectory("measure-parquet");
        System.out.println("Everything is written under " + directory + " (kept when the run ends)\n");
        Path lake = directory.resolve("lake");
        HttpServer server = null;
        try (DuckDBConnection live = (DuckDBConnection) DriverManager.getConnection(
                "jdbc:duckdb:" + directory.resolve("live.duckdb"))) {
            MeasureTable exposure = MeasureTable.named("exposure")
                    .fields("group", "point")
                    .order("point", "5y", "10y", "30y")
                    .writtenBy("svc_a")
                    .build();
            exposure.create(live);
            MeasureRefresh.createLedger(live);
            MeasureHistory history = MeasureHistory.at(directory.resolve("history.duckdb").toString());
            history.create(live, exposure);

            // Two days: a full set each morning, increments through the day.
            MeasureRefresh.at(MONDAY, "mon-09:00").full(exposure, exposure.batch()
                    .record(3).put(10, "g1", "5y").put(20, "g1", "10y")
                    .record(4).put(7, "g2", "5y")
                    .build()).commit(live);
            MeasureRefresh.at(MONDAY.plusSeconds(3600), "mon-10:00").increment(exposure, exposure.batch()
                    .record(3).put(12, "g1", "5y").put(20, "g1", "10y").put(5, "g1", "30y")
                    .build()).commit(live);
            MeasureRefresh.at(MONDAY.plusSeconds(7200), "mon-11:00").remove(exposure, 4).commit(live);
            MeasureRefresh.at(TUESDAY, "tue-09:00").full(exposure, exposure.batch()
                    .record(3).put(1, "g1", "5y")
                    .record(9).put(4, "g3", "10y")
                    .build()).commit(live);
            MeasureRefresh.at(TUESDAY.plusSeconds(3600), "tue-10:00").increment(exposure, exposure.batch()
                    .record(9).put(6, "g3", "10y").build()).commit(live);
            history.ship(live, exposure);

            System.out.println("Export: one file per day of refresh");
            System.out.println("-----------------------------------");
            MeasureHistory.Exported exported = history.export(live, exposure, lake.toString());
            List<String> files = exported.files();
            for (String file : files) {
                System.out.printf("   %-62s %,d bytes%n", file.substring(lake.toString().length() + 1),
                        Files.size(Path.of(file)));
            }
            System.out.println("\n   in full:");
            files.forEach(file -> System.out.println("   " + file));

            System.out.println("\nWhat a file holds - asked with plain SQL, nothing of quackjvm's involved");
            System.out.println("----------------------------------------------------------------------");
            try (Connection plain = DriverManager.getConnection("jdbc:duckdb:")) {
                // hive_partitioning = false: otherwise DuckDB reads the measure= and part= segments of
                // the path back as two extra columns, which are there for a lake to prune by, not in the file.
                print(plain, "DESCRIBE SELECT * FROM read_parquet('" + files.get(0) + "', hive_partitioning = false)",
                        "%-14s %s");
                System.out.println("   and the measure's own definition, in the file's metadata:");
                print(plain, "SELECT key::VARCHAR AS key, decode(value) AS value FROM parquet_kv_metadata('"
                        + files.get(0) + "')", "   %-18s %s");

                System.out.println("\nEvery row of Monday, which is every contribution in the order it was made");
                System.out.println("-----------------------------------------------------------------------");
                print(plain, "SELECT refresh_id, kind, record_id, \"group\" || ' ' || point AS key, value"
                        + " FROM read_parquet('" + files.get(0) + "') ORDER BY seq, record_id, key",
                        "%-10s %-10s %-3s %-8s %6.1f");

                System.out.println("\nThe state at a point, by the rule, in SQL a reader with only the files can run");
                System.out.println("-----------------------------------------------------------------------------");
                // The files by name here; a glob such as measure=exposure/part=*/svc_a-*.parquet reads the
                // same, and is what a lake would use.
                String list = "['" + String.join("', '", files) + "']";
                String stateAt = "WITH c AS (SELECT * FROM read_parquet(" + list + ", hive_partitioning = false)),"
                        + " since AS (SELECT coalesce(max(seq), 0) AS seq FROM c WHERE kind = 'full'"
                        + " AND refreshed_at <= ?)"
                        + " SELECT \"group\", point, sum(value) AS total FROM c, since"
                        + " WHERE c.refreshed_at <= ? AND c.seq >= since.seq"
                        + " GROUP BY ALL HAVING sum(value) <> 0 ORDER BY \"group\", point";
                System.out.println("   " + stateAt.replace(lake.toString(), "<lake>").replace(" since AS", "\n   since AS")
                        .replace(" SELECT \"group\"", "\n   SELECT \"group\"").replace(" WHERE c.", "\n   WHERE c.")
                        .replace(" GROUP BY", "\n   GROUP BY"));
                for (String[] when : new String[][] {
                        {"Monday 10:00", "2026-09-28 10:00:00"}, {"Monday 11:00", "2026-09-28 11:00:00"},
                        {"Tuesday 10:00", "2026-09-29 10:00:00"}}) {
                    System.out.printf("%n   %s%n", when[0]);
                    print(plain, stateAt.replace("?", "TIMESTAMP '" + when[1] + "'"), "      %-4s %-4s %6.1f");
                }
            }

            System.out.println("\nOver HTTP, where the files lie - nothing is downloaded whole");
            System.out.println("-----------------------------------------------------------");
            try (Connection remote = DriverManager.getConnection("jdbc:duckdb:")) {
                String unavailable = loadHttpfs(remote);
                if (unavailable != null) {
                    System.out.println("   skipped: " + unavailable);
                }
                else {
                    server = serve(lake);
                    int port = server.getAddress().getPort();
                    String url = "http://127.0.0.1:" + port + "/" + files.get(0).substring(lake.toString().length() + 1);
                    System.out.println("   " + url);
                    long started = System.nanoTime();
                    print(remote, "SELECT kind, count(*) AS rows, round(sum(abs(value)), 1) AS moved FROM read_parquet('"
                            + url + "') GROUP BY 1 ORDER BY 1", "   %-10s %3s %8s");
                    System.out.printf("   %.0f ms, reading only the byte ranges the query needed%n",
                            (System.nanoTime() - started) / 1e6);
                }
            }

            System.out.println("\nInto a database that has never heard of the measure");
            System.out.println("---------------------------------------------------");
            try (DuckDBConnection fresh = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
                // The definition comes out of the file; the history here is empty, so the files are
                // the only source and the ledger is read from them too.
                MeasureTable described = MeasureTable.describedBy(fresh, files.get(0));
                System.out.println("   the file says it is: " + described);
                // Its tables, empty: a point in time is read across the history, the outbox and the
                // files, so the first two have to exist even when they hold nothing.
                described.create(fresh);
                MeasureRefresh.createLedger(fresh);
                MeasureHistory empty = MeasureHistory.at(directory.resolve("fresh-history.duckdb").toString());
                empty.create(fresh, described);
                String[] all = files.toArray(new String[0]);
                show(fresh, "Monday 11:00 from the files alone",
                        described.query().asOf(MONDAY.plusSeconds(7200), empty).from(all).rows("group", "point"));
                show(fresh, "Tuesday 10:00 from the files alone",
                        described.query().asOf(TUESDAY.plusSeconds(3600), empty).from(all).rows("group", "point"));
                show(fresh, "and pivoted, the point as columns",
                        described.query().asOf(TUESDAY.plusSeconds(3600), empty).from(all).rows("group").columns("point"));
            }
        }
        finally {
            if (server != null) {
                server.stop(0);
            }
        }
        System.out.println("\nThe parquet files are still there: " + lake);
    }

    /** A static file server over the lake directory, on a free port. */
    /** Loads httpfs, installing it once if need be; the reason it cannot be had, or null. */
    static String loadHttpfs(Connection connection) {
        try {
            Sql.execute(connection, "LOAD httpfs");
            return null;
        }
        catch (Exception notInstalled) {
            try {
                Sql.execute(connection, "INSTALL httpfs");
                Sql.execute(connection, "LOAD httpfs");
                return null;
            }
            catch (Exception offline) {
                return "the httpfs extension is not installed and could not be downloaded (needs the network once: "
                        + "INSTALL httpfs). Everything else here works without it.";
            }
        }
    }

    static HttpServer serve(Path root) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Path file = root.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long size = Files.size(file);
            String range = exchange.getRequestHeaders().getFirst("Range");
            byte[] all = Files.readAllBytes(file);
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().add("Content-Length", String.valueOf(size));
                exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-");
                long from = Long.parseLong(parts[0]);
                long to = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : size - 1;
                exchange.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + size);
                exchange.sendResponseHeaders(206, to - from + 1);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(all, (int) from, (int) (to - from + 1));
                }
                return;
            }
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(all);
            }
        });
        server.start();
        return server;
    }

    static void print(Connection connection, String sql, String format) throws Exception {
        List<String> lines = new ArrayList<>();
        Rows.of(connection instanceof DuckDBConnection duck ? duck.duplicate() : connection, sql)
                .forEachRow((SqlRow row) -> {
                    Object[] values = new Object[row.getColumnNames().size()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = row.get(row.getColumnNames().get(i));
                    }
                    lines.add("   " + String.format(format, values));
                });
        lines.forEach(System.out::println);
    }

    static void show(DuckDBConnection connection, String what, io.quackjvm.core.measure.MeasureQuery query)
            throws Exception {
        StringBuilder line = new StringBuilder("   " + what + ": ");
        List<String> cells = new ArrayList<>();
        query.run(connection.duplicate()).forEachRow((SqlRow row) -> {
            StringBuilder cell = new StringBuilder();
            for (String column : row.getColumnNames()) {
                if (column.equals("group") || column.equals("point")) {
                    cell.append(cell.length() == 0 ? "" : " ").append(row.get(column));
                }
                else {
                    cell.append(' ').append(column).append('=').append(row.get(column));
                }
            }
            cells.add(cell.toString());
        });
        System.out.println(line + String.join(",  ", cells));
    }
}

/*
 * What it printed, on this machine (10 cores, DuckDB 1.5.5):
 *
 * Export: one file per day of refresh
 * -----------------------------------
 *    measure=exposure/part=2026-09-28/svc_a-1790834689824.parquet   1,694 bytes
 *    measure=exposure/part=2026-09-29/svc_a-1790834689831.parquet   1,580 bytes
 *
 * What a file holds - asked with plain SQL, nothing of quackjvm's involved
 * ----------------------------------------------------------------------
 *    record_id      BIGINT
 *    group          VARCHAR
 *    point          VARCHAR
 *    value          DOUBLE
 *    refresh_id     VARCHAR
 *    refreshed_at   TIMESTAMP
 *    writer         VARCHAR
 *    kind           VARCHAR
 *    seq            BIGINT
 *    and the measure's own definition, in the file's metadata:
 *       quackjvm_measure   {"measure":"exposure","fields":["group","point"],"orders":{"point":["5y","10y","30y"]},"part":"2026-09-28","written_by":"svc_a","written_at":"2026-10-01T06:04:49.825897Z","version":1}
 *
 * Every row of Monday, which is every contribution in the order it was made
 * -----------------------------------------------------------------------
 *    mon-09:00  full       3   g1 10y     20.0
 *    mon-09:00  full       3   g1 5y      10.0
 *    mon-09:00  full       4   g2 5y       7.0
 *    mon-10:00  increment  3   g1 30y      5.0
 *    mon-10:00  increment  3   g1 5y       2.0
 *    mon-11:00  increment  4   g2 5y      -7.0
 *
 * The state at a point, by the rule, in SQL a reader with only the files can run
 * -----------------------------------------------------------------------------
 *    WITH c AS (SELECT * FROM read_parquet(['<lake>/measure=exposure/part=2026-09-28/svc_a-1790834689824.parquet', '<lake>/measure=exposure/part=2026-09-29/svc_a-1790834689831.parquet'], hive_partitioning = false)),
 *    since AS (SELECT coalesce(max(seq), 0) AS seq FROM c WHERE kind = 'full' AND refreshed_at <= ?)
 *    SELECT "group", point, sum(value) AS total FROM c, since
 *    WHERE c.refreshed_at <= ? AND c.seq >= since.seq
 *    GROUP BY ALL HAVING sum(value) <> 0 ORDER BY "group", point
 *
 *    Monday 10:00
 *          g1   10y    20.0
 *          g1   30y     5.0
 *          g1   5y     12.0
 *          g2   5y      7.0
 *
 *    Monday 11:00
 *          g1   10y    20.0
 *          g1   30y     5.0
 *          g1   5y     12.0
 *
 *    Tuesday 10:00
 *          g1   5y      1.0
 *          g3   10y     6.0
 *
 * Over HTTP, where the files lie - nothing is downloaded whole
 * -----------------------------------------------------------
 *    http://127.0.0.1:53016/measure=exposure/part=2026-09-28/svc_a-1790834689824.parquet
 *       full         3     37.0
 *       increment    3     14.0
 *    22 ms, reading only the byte ranges the query needed
 *
 * Into a database that has never heard of the measure
 * ---------------------------------------------------
 *    the file says it is: MeasureTable[exposure [group, point], written by svc_a]
 *    Monday 11:00 from the files alone: g1 5y total=12.0,  g1 10y total=20.0,  g1 30y total=5.0
 *    Tuesday 10:00 from the files alone: g1 5y total=1.0,  g3 10y total=6.0
 *    and pivoted, the point as columns: g1 5y=1.0 10y=null,  g3 5y=null 10y=6.0
 *
 * A file is contributions, not state, and it carries everything needed to turn one into the other:
 * the refresh each row belongs to, when that was, who wrote it, and whether it was a full set. The
 * rule - sum from the last full set at or before the point - is the five-line statement above, and a
 * reader with nothing but DuckDB and the files gets the same answers quackjvm does.
 *
 * Over HTTP, DuckDB fetches the byte ranges a query needs rather than the file; the example's own
 * tiny server answers Range requests to show it. And a database that has never heard of the measure
 * reads its definition out of a file's metadata and asks the files for a point in time directly.
 */
