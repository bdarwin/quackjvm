package io.quackjvm.cqengine.bench;

import io.quackjvm.core.sql.Rows;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Pre-aggregate table versus caching the result, for a dashboard.
 *
 * <p>DuckDB has no materialised views - {@code CREATE MATERIALIZED VIEW} is a parser error, and a
 * plain {@code CREATE VIEW} is live, so it re-scans the base table every time. The only way to
 * precompute is {@code CREATE TABLE AS SELECT}: a real table you keep in step yourself. Which
 * raises the fair objection that it is a cache like any other, with the same staleness problem.
 * This measures what is actually different about it.</p>
 */
public final class PreAggregateBenchmark {

    private static final String[] REGIONS = {"EMEA", "AMER", "APAC", "LATAM"};
    private static final String[] MAKES = {"Ford", "BMW", "Toyota", "Honda", "Tesla", "Kia", "Audi", "Fiat"};
    private static final String[] COLOURS = {"red", "blue", "black", "white", "silver"};

    private static DuckDBConnection c;

    static void ddl(String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static double best(int reps, Runnable r) {
        double b = Double.MAX_VALUE;
        for (int i = 0; i < reps; i++) {
            long t = System.nanoTime();
            r.run();
            b = Math.min(b, (System.nanoTime() - t) / 1e6);
        }
        return b;
    }

    static long q(String sql, Object... params) {
        return rows(sql, params).count();
    }

    static Rows rows(String sql, Object... params) {
        try {
            return Rows.of(c.duplicate(), sql, params);
        }
        catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        c = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        ddl("SET memory_limit='2GB'");
        ddl("CREATE TABLE sale (saleId INTEGER, region VARCHAR, make VARCHAR, model VARCHAR,"
                + " colour VARCHAR, price DOUBLE, year INTEGER)");
        append(0, rows);
        System.out.printf("%,d sales in the base table.%n%n", rows);

        // The pre-aggregate: every dimension a panel might group or filter by, one row per
        // combination, with additive measures only.
        double buildMs = best(1, () -> {
            try {
                ddl("DROP TABLE IF EXISTS agg");
                ddl("CREATE TABLE agg AS SELECT region, make, colour, year,"
                        + " count(*) AS n, sum(price) AS total FROM sale GROUP BY 1,2,3,4");
            }
            catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        long aggRows = Rows.of(c.duplicate(), "SELECT count(*) FROM agg").scalar(Long.class);
        System.out.printf("Pre-aggregate: %,d rows, built in %.0f ms (%.1f%% of the base table)%n%n",
                aggRows, buildMs, 100.0 * aggRows / rows);

        System.out.printf("%-52s %11s %11s%n", "panel", "base", "pre-agg");
        panel("group by make: count and average price",
                "SELECT make, count(*) AS n, avg(price) FROM sale GROUP BY 1 ORDER BY 2 DESC",
                "SELECT make, sum(n) AS n, sum(total)/sum(n) FROM agg GROUP BY 1 ORDER BY 2 DESC");
        panel("pivot: colour across make",
                "PIVOT sale ON colour USING count(*) GROUP BY make",
                "PIVOT agg ON colour USING sum(n) GROUP BY make");
        panel("revenue by year for one make",
                "SELECT year, sum(price) FROM sale WHERE make = 'BMW' GROUP BY 1 ORDER BY 1",
                "SELECT year, sum(total) FROM agg WHERE make = 'BMW' GROUP BY 1 ORDER BY 1");
        panel("headline numbers for one region",
                "SELECT count(*), avg(price) FROM sale WHERE region = 'EMEA'",
                "SELECT sum(n), sum(total)/sum(n) FROM agg WHERE region = 'EMEA'");
        panel("a slice nobody precomputed: region x year, APAC only",
                "SELECT year, count(*), sum(price) FROM sale WHERE region='APAC' GROUP BY 1 ORDER BY 1",
                "SELECT year, sum(n), sum(total) FROM agg WHERE region='APAC' GROUP BY 1 ORDER BY 1");

        System.out.println("\nWhat the pre-aggregate cannot answer:");
        System.out.printf("  %-50s %11.1f ms  %s%n", "median price (not additive)",
                best(3, () -> q("SELECT median(price) FROM sale")), "no pre-agg equivalent");
        System.out.printf("  %-50s %11.1f ms  %s%n", "exact distinct models (not additive)",
                best(3, () -> q("SELECT count(DISTINCT model) FROM sale")), "no pre-agg equivalent");

        // ---------- Keeping it in step ----------
        System.out.println("\nKeeping it in step as 50,000 new sales arrive:");
        double fullRebuild = best(3, () -> {
            try {
                ddl("DROP TABLE IF EXISTS agg2");
                ddl("CREATE TABLE agg2 AS SELECT region, make, colour, year,"
                        + " count(*) AS n, sum(price) AS total FROM sale GROUP BY 1,2,3,4");
            }
            catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        append(rows, 50_000);
        // Additive measures fold in: aggregate only the new rows and append the delta. Panels
        // already sum over the table, so duplicate group rows are simply summed too.
        double incremental = best(1, () -> {
            try {
                ddl("INSERT INTO agg SELECT region, make, colour, year, count(*), sum(price)"
                        + " FROM sale WHERE saleId >= " + rows + " GROUP BY 1,2,3,4");
            }
            catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        System.out.printf("  %-50s %11.1f ms%n", "full rebuild", fullRebuild);
        System.out.printf("  %-50s %11.1f ms%n", "incremental: fold in the delta", incremental);

        // Correctness: incremental must equal a rebuild.
        ddl("DROP TABLE IF EXISTS fresh");
        ddl("CREATE TABLE fresh AS SELECT region, make, colour, year, count(*) AS n,"
                + " sum(price) AS total FROM sale GROUP BY 1,2,3,4");
        long mismatches = rows(
                "SELECT count(*) FROM ("
              + "  SELECT make, sum(n) AS n, round(sum(total),2) AS total FROM agg GROUP BY 1) a "
              + "FULL JOIN ("
              + "  SELECT make, sum(n) AS n, round(sum(total),2) AS total FROM fresh GROUP BY 1) f "
              + "USING (make) WHERE a.n IS DISTINCT FROM f.n OR a.total IS DISTINCT FROM f.total")
                .scalar(Long.class);
        System.out.printf("  %-50s %11s%n", "incremental result matches a full rebuild",
                mismatches == 0 ? "yes" : "NO - " + mismatches + " groups differ");
        c.close();
    }

    private static void panel(String label, String base, String agg) {
        double b = best(3, () -> q(base));
        double a = best(3, () -> q(agg));
        System.out.printf("  %-50s %8.1f ms %8.2f ms  %.0fx%n", label, b, a, b / a);
    }

    private static void append(int from, int count) throws SQLException {
        try (DuckDBAppender ap = c.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "sale")) {
            for (int i = from; i < from + count; i++) {
                ap.beginRow();
                ap.append(i);
                ap.append(REGIONS[i % REGIONS.length]);
                ap.append(MAKES[(i / 3) % MAKES.length]);
                ap.append("model" + (i % 250));
                ap.append(COLOURS[(i / 7) % COLOURS.length]);
                ap.append((double) (5_000 + (i * 7919L) % 60_000));
                ap.append(2000 + (i % 25));
                ap.endRow();
            }
        }
    }
}
