/*
 * A plain java.util.List, queried and joined with SQL as if it were a DuckDB table - with no load
 * step. Uses the table functions DuckDB added to its Java client in 1.5.
 *
 * A table function is a function you call in the FROM clause that produces rows, the way
 * read_csv('file.csv') or range(10) do. DuckDB 1.5 lets you write one in Java, so anything Java can
 * reach - a list, a cache, another database, a paged API - becomes something SQL can join.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreTableFunction.java
 */

import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDataChunkWriter;
import org.duckdb.DuckDBFunctions;
import org.duckdb.DuckDBTableFunction;
import org.duckdb.DuckDBTableFunctionBindInfo;
import org.duckdb.DuckDBTableFunctionBuilder;
import org.duckdb.DuckDBTableFunctionCallInfo;
import org.duckdb.DuckDBTableFunctionInitInfo;
import org.duckdb.DuckDBValue;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoreTableFunction {

    /** What lives in the JVM: an ordinary record in an ordinary list. */
    public record Rating(int model, String grade, double score) {
    }

    /**
     * The lists SQL can see, by name. The table function takes the name as its argument, so one
     * registered function can serve any number of lists: java_ratings('ratings').
     */
    static final Map<String, List<Rating>> LISTS = new ConcurrentHashMap<>();

    /** Result of bind: which list this query reads. */
    record Bound(List<Rating> rows) {
    }

    /** Result of init: how far this execution has got through the list. */
    static final class Cursor {
        int position;
    }

    /**
     * The three callbacks answer three questions.
     *
     * <ul>
     *   <li>{@code bind} - what shape is this table? Runs once when the query is prepared. Read the
     *       SQL arguments here and declare the result columns.</li>
     *   <li>{@code init} - where are we up to? Per-execution state.</li>
     *   <li>{@code apply} - give me the next chunk. DuckDB calls it repeatedly; fill up to
     *       {@code capacity()} rows and return how many. Returning 0 means done.</li>
     * </ul>
     */
    static final class ListTable implements DuckDBTableFunction<Bound, Cursor, Object> {

        @Override
        public Bound bind(DuckDBTableFunctionBindInfo info) {
            String name;
            try (DuckDBValue argument = info.getParameter(0)) {
                name = argument.getString();
            }
            List<Rating> rows = LISTS.get(name);
            if (rows == null) {
                throw new IllegalArgumentException("No list registered as '" + name + "'");
            }
            info.addResultColumn("model", Integer.class);
            info.addResultColumn("grade", String.class);
            info.addResultColumn("score", Double.class);
            // A hint for the optimizer, e.g. to pick the build side of a hash join.
            info.setCardinality(rows.size(), false);
            return new Bound(rows);
        }

        @Override
        public Cursor init(DuckDBTableFunctionInitInfo info) {
            return new Cursor();
        }

        @Override
        public long apply(DuckDBTableFunctionCallInfo call, DuckDBDataChunkWriter out) {
            List<Rating> rows = call.<Bound>getBindData().rows();
            Cursor cursor = call.getInitData();
            long written = 0;
            // Column by column, row by row, into DuckDB's own vectors. Note setString, not
            // setVarchar.
            while (written < out.capacity() && cursor.position < rows.size()) {
                Rating rating = rows.get(cursor.position++);
                out.vector(0).setInt(written, rating.model());
                out.vector(1).setString(written, rating.grade());
                out.vector(2).setDouble(written, rating.score());
                written++;
            }
            return written;
        }
    }

    public static void main(String[] args) throws Exception {
        List<Rating> ratings = new ArrayList<>();
        for (int model = 0; model < 400; model++) {
            String grade = model % 3 == 0 ? "A" : model % 3 == 1 ? "B" : "C";
            ratings.add(new Rating(model, grade, 1.0 + (model % 50) / 10.0));
        }
        LISTS.put("ratings", ratings);

        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

            // Some data that does live in DuckDB, to join against.
            execute(connection, "CREATE TABLE sale (id INTEGER, model INTEGER, price DOUBLE)");
            execute(connection, "INSERT INTO sale SELECT i, i % 400, 100.0 + (i % 900)"
                    + " FROM range(200000) t(i)");

            try (DuckDBTableFunctionBuilder builder = DuckDBFunctions.tableFunction()) {
                builder.withName("java_ratings")
                        .withParameter(String.class)
                        .withFunction(new ListTable())
                        .register(connection);
            }

            System.out.println("1. The list, queried as a table:");
            print(connection, "SELECT grade, count(*) AS n, round(avg(score), 3) AS avg_score"
                    + " FROM java_ratings('ratings') GROUP BY 1 ORDER BY 1");

            System.out.println();
            System.out.println("2. Joined against 200,000 rows stored in DuckDB - no load step:");
            long startedAt = System.nanoTime();
            print(connection, "SELECT r.grade, count(*) AS sales, round(sum(s.price), 2) AS revenue"
                    + " FROM sale s JOIN java_ratings('ratings') r ON s.model = r.model"
                    + " WHERE r.score > 3.0 GROUP BY 1 ORDER BY 3 DESC");
            System.out.printf("   (%.1f ms)%n", (System.nanoTime() - startedAt) / 1e6);

            System.out.println();
            System.out.println("3. The list is live. Change it in Java and the next query sees it:");
            ratings.add(new Rating(9_999, "S", 99.0));
            print(connection, "SELECT count(*) AS n, max(score) AS best FROM java_ratings('ratings')");

            System.out.println();
            System.out.println("4. An exception thrown in bind fails the query before anything runs:");
            try {
                print(connection, "SELECT * FROM java_ratings('nope')");
            }
            catch (SQLException expected) {
                // The Java exception arrives flattened into the SQLException's text, stack trace
                // and all - not as its cause. Through a plain Statement the first line is a generic
                // "unsuccessful pending query" wrapper, so look for the Binder Error line.
                System.out.println("   " + expected.getMessage().lines()
                        .filter(line -> line.contains("Binder Error"))
                        .findFirst().orElse(expected.getMessage()));
            }
        }
    }

    private static void execute(DuckDBConnection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void print(DuckDBConnection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                StringBuilder line = new StringBuilder("   ");
                for (int i = 1; i <= columns; i++) {
                    line.append(rows.getMetaData().getColumnLabel(i)).append('=')
                            .append(rows.getString(i)).append(i < columns ? "  " : "");
                }
                System.out.println(line);
            }
        }
    }
}

/*
 * Output (Apple Silicon, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * 1. The list, queried as a table:
 *    grade=A  n=134  avg_score=3.45
 *    grade=B  n=133  avg_score=3.456
 *    grade=C  n=133  avg_score=3.444
 *
 * 2. Joined against 200,000 rows stored in DuckDB - no load step:
 *    grade=A  sales=39000  revenue=2.18268E7
 *    grade=C  sales=38500  revenue=2.15561E7
 *    grade=B  sales=38500  revenue=2.15365E7
 *    (3.7 ms)
 *
 * 3. The list is live. Change it in Java and the next query sees it:
 *    n=401  best=99.0
 *
 * 4. An exception thrown in bind fails the query before anything runs:
 *    Error: Binder Error: java.lang.IllegalArgumentException: No list registered as 'nope'
 */
