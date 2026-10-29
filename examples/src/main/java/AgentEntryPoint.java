/*
 * The agent features through one Quack, with no connection in sight: a Java list queried as a
 * table, a Java method called from SQL, the catalog describing what is there, and a query nobody
 * vetted, run in a sandbox. These are in quackjvm-agent; CoreEntryPoint shows the rest.
 *
 * What the database shares is registered on it once, through db.register(...), and closed with it.
 * The guard and the catalog borrow a connection for each query, so they are safe to share.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/AgentEntryPoint.java
 */

import io.quackjvm.agent.catalog.Catalog;
import io.quackjvm.agent.guard.GuardedQuery;
import io.quackjvm.agent.guard.Hardening;
import io.quackjvm.agent.guard.QueryPolicy;
import io.quackjvm.agent.live.LiveTables;
import io.quackjvm.agent.udf.Udfs;
import io.quackjvm.core.Quack;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.core.sql.SqlRow;

import java.util.ArrayList;
import java.util.List;

public class AgentEntryPoint {

    public record Car(int carId, String make, double price) {
    }

    public static void main(String[] args) {
        try (Quack db = Quack.inMemory()) {
            db.execute("CREATE TABLE item (id INTEGER PRIMARY KEY, count INTEGER)");

            System.out.println("A Java list as a table, and a Java method in SQL");
            System.out.println("------------------------------------------------");
            List<Car> cars = List.of(new Car(1, "a", 1000), new Car(2, "b", 2500), new Car(3, "a", 1800));
            db.register(root -> LiveTables.register(root, "car", cars, ColumnarLayout.ofRecord(Car.class)));
            db.register(root -> Udfs.register(root, "with_tax", Double.class, Double.class, price -> price * 1.2));
            db.query("SELECT make, count(*) AS cars, round(sum(with_tax(price)), 2) AS taxed FROM car"
                    + " GROUP BY make ORDER BY make").forEachRow((SqlRow row) ->
                    System.out.println("   " + row.get("make") + ": " + row.get("cars") + " cars, " + row.get("taxed")));
            List<String> names = new ArrayList<>();
            Catalog.of(db).tables().forEach(table -> names.add(table.name()));
            System.out.println("   the catalog sees " + names);
        }

        System.out.println("\nA query nobody vetted, in a sandbox");
        System.out.println("-----------------------------------");
        try (Quack sandbox = Hardening.openSandbox()) {
            sandbox.register(root -> LiveTables.register(root, "car",
                    List.of(new Car(1, "a", 1000), new Car(2, "b", 2500)), ColumnarLayout.ofRecord(Car.class)));
            GuardedQuery guard = GuardedQuery.on(sandbox, QueryPolicy.readOnly());
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
 * What it printed, on this machine (DuckDB 2.0.0):
 *
 * A Java list as a table, and a Java method in SQL
 * ------------------------------------------------
 *    a: 2 cars, 3360.0
 *    b: 1 cars, 3000.0
 *    the catalog sees [car, item]
 *
 * A query nobody vetted, in a sandbox
 * -----------------------------------
 *    SELECT count(*) FROM car -> 2
 *    DROP VIEW car -> refused: DROP is not a query, so it will not be run. Only statements that read are allowed: SELECT, WITH, FROM, TABLE, VALUES, DESCRIBE, SUMMARIZE, UNPIVOT and EXPLAIN. DuckDB's parser said: Only SELECT statements can be serialized to json!
 *    SELECT * FROM read_csv('/etc/hosts') -> refused: DuckDB refused to run this: Permission Error: Cannot access file "/etc/hosts" - file system operations are disabled by configuration
 */
