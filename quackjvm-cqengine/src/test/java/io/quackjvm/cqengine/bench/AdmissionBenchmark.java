package io.quackjvm.cqengine.bench;

import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Complex analytical queries, many users, a table of real size.
 *
 * <p>DuckDB parallelises <em>within</em> a query and expects to own the machine while it runs one.
 * A dashboard asks it to run sixteen at once, and {@code threads} is a <b>global</b> setting - it
 * cannot be lowered for interactive queries and left high for analytical ones, because setting it
 * on any connection sets it for all of them. So the only remaining lever is on our side of the
 * boundary: let fewer queries run at a time and make the rest wait.</p>
 *
 * <p>This measures that. Same load, same DuckDB settings, the only difference being how many
 * queries are allowed to execute concurrently.</p>
 */
public final class AdmissionBenchmark {

    private static DuckDBConnection root;

    private static void ddl(String sql) throws SQLException {
        try (Statement s = root.createStatement()) {
            s.execute(sql);
        }
    }

    /** Panels that would otherwise be a lot of Java: multi-level grouping, ranking, filtering. */
    private static final String[] PANELS = {
            // top models by revenue within each region, ranked - a window function
            "SELECT region, make, model, revenue, rank FROM ("
          + "  SELECT region, make, model, sum(price) AS revenue,"
          + "         rank() OVER (PARTITION BY region ORDER BY sum(price) DESC) AS rank"
          + "  FROM sale WHERE year >= ? GROUP BY 1,2,3) t WHERE rank <= 5",
            // multi-dimensional roll-up with a HAVING
            "SELECT region, make, year, count(*) AS n, sum(price) AS total, avg(price) AS avg"
          + " FROM sale GROUP BY 1,2,3 HAVING count(*) > 100 ORDER BY total DESC LIMIT 50",
            // pivot across two dimensions
            "PIVOT sale ON colour USING sum(price) GROUP BY region, year",
            // share of total per make - a correlated aggregate, one statement instead of two passes
            "SELECT make, sum(price) AS total,"
          + " sum(price) / (SELECT sum(price) FROM sale) AS share"
          + " FROM sale WHERE region = ? GROUP BY 1 ORDER BY 2 DESC"};

    /** The same four panels, answered from a pre-aggregate instead of the base table. */
    private static final String[] AGG_PANELS = {
            "SELECT region, make, model, revenue, rank FROM ("
          + "  SELECT region, make, model, sum(total) AS revenue,"
          + "         rank() OVER (PARTITION BY region ORDER BY sum(total) DESC) AS rank"
          + "  FROM agg WHERE year >= ? GROUP BY 1,2,3) t WHERE rank <= 5",
            "SELECT region, make, year, sum(n) AS n, sum(total) AS total, sum(total)/sum(n) AS avg"
          + " FROM agg GROUP BY 1,2,3 HAVING sum(n) > 100 ORDER BY total DESC LIMIT 50",
            "PIVOT agg ON colour USING sum(total) GROUP BY region, year",
            "SELECT make, sum(total) AS total,"
          + " sum(total) / (SELECT sum(total) FROM agg) AS share"
          + " FROM agg WHERE region = ? GROUP BY 1 ORDER BY 2 DESC"};

    private static String[] panels = PANELS;

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 10_000_000;
        long seconds = args.length > 1 ? Long.parseLong(args[1]) : 6;
        int users = args.length > 2 ? Integer.parseInt(args[2]) : 16;

        root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        ddl("SET memory_limit='8GB'");
        ddl("CREATE TABLE sale (id INTEGER, region VARCHAR, make VARCHAR, model VARCHAR,"
                + " colour VARCHAR, price DOUBLE, year INTEGER)");
        long started = System.nanoTime();
        try (DuckDBAppender ap = root.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "sale")) {
            for (int i = 0; i < rows; i++) {
                ap.beginRow();
                ap.append(i);
                ap.append("R" + (i % 6));
                ap.append("M" + ((i / 3) % 12));
                ap.append("model" + (i % 400));
                ap.append("C" + ((i / 7) % 5));
                ap.append((double) (5_000 + (i * 7919L) % 60_000));
                ap.append(2000 + (i % 25));
                ap.endRow();
            }
        }
        System.out.printf("%,d rows loaded in %.1f s. %d concurrent users, four complex panels.%n",
                rows, (System.nanoTime() - started) / 1e9, users);
        System.out.printf("DuckDB reports %s threads.%n%n", Rows.of(root.duplicate(),
                "SELECT current_setting('threads')").scalar(String.class));

        System.out.printf("  %-38s %11s %10s %10s %10s%n",
                "configuration", "panels/s", "p50", "p95", "p99");
        for (int limit : new int[]{0, 1, 2, 4, 8}) {
            run(users, seconds, limit);
        }

        // Now the same panels against a pre-aggregate, to separate "share the machine better"
        // from "give the machine less to do".
        long t0 = System.nanoTime();
        ddl("CREATE TABLE agg AS SELECT region, make, model, colour, year,"
                + " count(*) AS n, sum(price) AS total FROM sale GROUP BY 1,2,3,4,5");
        long aggRows = Rows.of(root.duplicate(), "SELECT count(*) FROM agg").scalar(Long.class);
        System.out.printf("%nPre-aggregate: %,d rows (%.2f%% of the base table), built in %.0f ms%n",
                aggRows, 100.0 * aggRows / rows, (System.nanoTime() - t0) / 1e6);
        panels = AGG_PANELS;
        System.out.printf("  %-38s %11s %10s %10s %10s%n",
                "configuration", "panels/s", "p50", "p95", "p99");
        for (int limit : new int[]{0, 4}) {
            run(users, seconds, limit);
        }
    }

    private static void run(int users, long seconds, int limit) {
        Semaphore gate = limit > 0 ? new Semaphore(limit, true) : null;
        String label = limit == 0 ? "no limit (all " + users + " at once)" : "at most " + limit + " running";
        Conc.Result r = Conc.best(users, 2000, seconds * 1000, 2, thread ->
                (threadIndex, iteration) -> {
                    int panel = Math.floorMod(threadIndex + (int) iteration, panels.length);
                    if (gate != null) {
                        gate.acquire();
                    }
                    try {
                        query(panel);
                    }
                    finally {
                        if (gate != null) {
                            gate.release();
                        }
                    }
                });
        System.out.printf("  %-38s %11.1f %8.1f ms %8.1f ms %8.1f ms%s%n", label, r.opsPerSecond(),
                r.percentileMicros(50) / 1000, r.percentileMicros(95) / 1000,
                r.percentileMicros(99) / 1000, r.errors > 0 ? "  errors=" + r.errors : "");
    }

    private static void query(int panel) {
        String sql = panels[panel];
        Object[] params = switch (panel) {
            case 0 -> new Object[]{2000 + ThreadLocalRandom.current().nextInt(20)};
            case 3 -> new Object[]{"R" + ThreadLocalRandom.current().nextInt(6)};
            default -> new Object[0];
        };
        try {
            Rows.of(root.duplicate(), sql, params).count();
        }
        catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
