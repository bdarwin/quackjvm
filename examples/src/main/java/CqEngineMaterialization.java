/*
 * Materializations: an expensive query precomputed into a DuckDB table, so that requests are served
 * from the answer rather than from the data - and refreshed without readers ever seeing it missing.
 *
 * A DuckDB VIEW is not this: it stores the query, not its result, and re-runs it every time. DuckDB
 * has no materialised views, so this is a real table that quackjvm keeps in step for you.
 *
 * The part worth having a library for is the refresh. The obvious way - DROP TABLE, then CREATE
 * TABLE AS - leaves a moment in which the table does not exist: with four threads reading across
 * fifteen refreshes that produced 1,280 failures of "Table with name ... does not exist". refresh()
 * builds the replacement alongside and swaps the two inside one transaction instead. This example
 * runs readers through fifteen refreshes and counts the failures.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED -Xmx4g \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineMaterialization.java
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.persistence.DuckDBDatabase;
import io.quackjvm.cqengine.persistence.DuckDBDatabase.ManagedMaterialization;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class CqEngineMaterialization {

    public record Sale(int saleId, String region, String make, double price, int year) {
        static final SimpleAttribute<Sale, Integer> SALE_ID =
                new SimpleAttribute<>(Sale.class, Integer.class, "saleId") {
                    public Integer getValue(Sale sale, QueryOptions options) {
                        return sale.saleId();
                    }
                };
    }

    /** One row of the rollup as read back - the whole row, so comparing two of them means something. */
    public record RegionRow(String region, long n, double avgPrice) {
    }

    /** One row of the panel, read back as a record - components matched to columns by position. */
    public record TopMake(String region, String make, double revenue, long rank) {
    }

    static final String[] REGIONS = {"EMEA", "AMER", "APAC", "LATAM", "ANZ", "MEA"};
    static final String[] MAKES = {"Ford", "BMW", "Toyota", "Honda", "Tesla", "Kia", "Audi", "Fiat",
            "Volvo", "Mazda", "Seat", "Skoda"};

    static Sale sale(int id) {
        return new Sale(id, REGIONS[id % REGIONS.length], MAKES[(id / 7) % MAKES.length],
                5_000 + (id * 7919L) % 60_000, 2000 + id % 25);
    }

    public static void main(String[] args) throws Exception {
        int count = 5_000_000;
        try (DuckDBDatabase database = DuckDBDatabase.builder().memoryLimit("2GB").build()) {
            IndexedCollection<Sale> sales = database.collection(Sale.SALE_ID)
                    .columnarLayout(ColumnarLayout.ofRecord(Sale.class))
                    .build();
            List<Sale> batch = new ArrayList<>(500_000);
            for (int id = 0; id < count; id++) {
                batch.add(sale(id));
                if (batch.size() == 500_000) {
                    sales.addAll(batch);
                    batch.clear();
                }
            }
            String table = database.table(sales);
            System.out.printf("%,d sales loaded.%n%n", sales.size());

            // ---------- 1. A panel, precomputed ----------

            // The top three makes by revenue in each region: a GROUP BY feeding a window function.
            String topMakes = "SELECT region, make, sum(price) AS revenue,"
                    + " rank() OVER (PARTITION BY region ORDER BY sum(price) DESC) AS rank"
                    + " FROM " + table + " GROUP BY 1, 2";

            double direct = bestMillis(() -> database.query(
                    "SELECT * FROM (" + topMakes + ") WHERE rank <= 3").records(TopMake.class));

            // Builds the table if it is not there already. The name is what queries use.
            ManagedMaterialization panel = database.materialize("top_makes").as(topMakes).build();

            double materialized = bestMillis(() -> database.query(
                    "SELECT * FROM top_makes WHERE rank <= 3").records(TopMake.class));

            System.out.println("1. The panel, computed against the base table, then from its materialization:");
            System.out.printf("   base table        %7.2f ms%n", direct);
            System.out.printf("   materialization   %7.2f ms   (%.0fx, %,d rows)%n",
                    materialized, direct / materialized, panel.rowCount());

            // It is an ordinary table, so it can be sliced a way the panel never was.
            List<TopMake> emea = database.query(
                    "SELECT * FROM top_makes WHERE region = ? AND rank <= 3 ORDER BY rank", "EMEA")
                    .records(TopMake.class);
            System.out.println("   and it is still a table - EMEA, from the same materialization:");
            emea.forEach(row -> System.out.printf("     %d. %-7s %,.0f%n", row.rank(), row.make(), row.revenue()));

            // ---------- 2. Refreshing while people are reading ----------

            System.out.println();
            System.out.println("2. Fifteen refreshes, with four threads reading throughout:");
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong reads = new AtomicLong();
            AtomicLong failures = new AtomicLong();
            ExecutorService readers = Executors.newFixedThreadPool(4);
            for (int i = 0; i < 4; i++) {
                readers.submit(() -> {
                    while (!stop.get()) {
                        try {
                            database.query("SELECT count(*) FROM top_makes").scalar(Long.class);
                            reads.incrementAndGet();
                        }
                        catch (RuntimeException e) {
                            failures.incrementAndGet();
                        }
                    }
                });
            }
            long startedAt = System.nanoTime();
            for (int i = 0; i < 15; i++) {
                panel.refresh();
            }
            double refreshing = (System.nanoTime() - startedAt) / 1e6;
            stop.set(true);
            readers.shutdown();
            readers.awaitTermination(30, TimeUnit.SECONDS);
            System.out.printf("   %,d reads, %d failed, %.0f ms per refresh%n",
                    reads.get(), failures.get(), refreshing / 15);
            System.out.println("   (Measured separately under the same load: DROP then CREATE TABLE AS failed 1,280 reads.)");

            // ---------- 3. Folding in new rows instead of rebuilding ----------

            // For additive measures - counts and sums, never averages - a rollup can take just the
            // new rows. Store count and sum, derive the average when reading: sum(total) / sum(n).
            ManagedMaterialization rollup = database.materialize("sales_by_region")
                    .as("SELECT region, year, count(*) AS n, sum(price) AS total FROM " + table
                            + " GROUP BY 1, 2")
                    .build();

            int watermark = count;
            List<Sale> arriving = new ArrayList<>();
            for (int id = watermark; id < watermark + 250_000; id++) {
                arriving.add(sale(id));
            }
            sales.addAll(arriving);

            startedAt = System.nanoTime();
            rollup.appendDelta("SELECT region, year, count(*), sum(price) FROM " + table
                    + " WHERE saleId >= ? GROUP BY 1, 2", watermark);
            double delta = (System.nanoTime() - startedAt) / 1e6;

            // After an append the table holds partial groups, so readers re-aggregate.
            String readRollup = "SELECT region, sum(n) AS n, round(sum(total) / sum(n), 2) AS avg_price"
                    + " FROM sales_by_region GROUP BY 1 ORDER BY 1";
            // records(), not list(): list() returns only the first column, and comparing region
            // names would say "same" whatever the numbers were.
            List<RegionRow> incremental = database.query(readRollup).records(RegionRow.class);

            startedAt = System.nanoTime();
            rollup.refresh();
            double rebuild = (System.nanoTime() - startedAt) / 1e6;
            List<RegionRow> rebuilt = database.query(readRollup).records(RegionRow.class);

            System.out.println();
            System.out.printf("3. 250,000 new sales arrive, into a rollup of %,d:%n", count);
            System.out.printf("   fold in the new rows   %7.1f ms%n", delta);
            System.out.printf("   rebuild from scratch   %7.1f ms%n", rebuild);
            System.out.println("   same answer both ways: " + incremental.equals(rebuilt));
            rebuilt.forEach(row -> System.out.printf("     %-6s %,10d sales, average %,.2f%n",
                    row.region(), row.n(), row.avgPrice()));
        }
    }

    /** Best of five, so the first run's warm-up does not count against either side. */
    private static double bestMillis(Runnable work) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long startedAt = System.nanoTime();
            work.run();
            best = Math.min(best, (System.nanoTime() - startedAt) / 1e6);
        }
        return best;
    }
}

/*
 * Output (Apple Silicon, 10 cores, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 5,000,000 sales loaded.
 *
 * 1. The panel, computed against the base table, then from its materialization:
 *    base table          14.99 ms
 *    materialization      0.28 ms   (54x, 72 rows)
 *    and it is still a table - EMEA, from the same materialization:
 *      1. Audi    4,166,412,232
 *      2. Ford    4,166,245,528
 *      3. Toyota  2,083,431,104
 *
 * 2. Fifteen refreshes, with four threads reading throughout:
 *    5,899 reads, 0 failed, 20 ms per refresh
 *    (Measured separately under the same load: DROP then CREATE TABLE AS failed 1,280 reads.)
 *
 * 3. 250,000 new sales arrive, into a rollup of 5,000,000:
 *    fold in the new rows       4.8 ms
 *    rebuild from scratch      14.1 ms
 *    same answer both ways: true
 *      AMER      875,000 sales, average 35,002.00
 *      ANZ       875,000 sales, average 34,998.93
 *      APAC      875,000 sales, average 35,000.93
 *      EMEA      875,000 sales, average 34,997.10
 *      LATAM     875,000 sales, average 35,000.00
 *      MEA       875,000 sales, average 34,998.00
 */
