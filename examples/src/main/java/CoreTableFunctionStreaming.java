/*
 * A table function over a source that is never held in memory: a paged "remote" feed, pulled one
 * page at a time only as DuckDB asks for more rows.
 *
 * This is what makes table functions more than a way to query a List. DuckDB drives the loop - it
 * calls apply() for the next chunk until you return 0 - so an unbounded or expensive source (a
 * cursor, a REST endpoint paging through results, a message topic) streams through SQL without
 * being materialised first.
 *
 * It also shows where that stops being true, which matters as soon as each page costs a network
 * call: a bare LIMIT stops the fetching early, but a WHERE clause in front of the LIMIT does not -
 * every page is fetched even when the first one already holds every row the query returns. There is
 * no filter pushdown in the Java table-function builder, so the function never sees the WHERE.
 *
 * Also shows named parameters (page_size := 250) and projection pushdown: when a query only needs
 * some columns, DuckDB says which, and the function produces only those.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CoreTableFunctionStreaming.java
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
import java.util.concurrent.atomic.AtomicInteger;

public class CoreTableFunctionStreaming {

    public record Event(long id, String source, String level, double latencyMillis) {
    }

    /**
     * Stands in for a remote API that returns results a page at a time. It counts how many pages
     * were fetched and how many fields were filled, which is the point of the example.
     */
    static final class EventFeed {
        static final int TOTAL = 100_000;
        static final AtomicInteger pagesFetched = new AtomicInteger();
        static final AtomicInteger fieldsWritten = new AtomicInteger();

        static List<Event> page(int number, int size) {
            pagesFetched.incrementAndGet();
            List<Event> events = new ArrayList<>(size);
            for (long id = (long) number * size; id < Math.min(TOTAL, (long) (number + 1) * size); id++) {
                events.add(new Event(id, id % 4 == 0 ? "api" : id % 4 == 1 ? "worker" : "web",
                        id % 50 == 0 ? "ERROR" : "INFO", (id * 7919) % 1000 / 1.0));
            }
            return events;
        }

        static void reset() {
            pagesFetched.set(0);
            fieldsWritten.set(0);
        }
    }

    record Bound(int pageSize) {
    }

    /** Per execution: the page in hand, and which of the four columns this query asked for. */
    static final class State {
        int[] projectedColumns;
        int nextPage;
        List<Event> page = List.of();
        int positionInPage;
        boolean exhausted;
    }

    static final class FeedTable implements DuckDBTableFunction<Bound, State, Object> {

        @Override
        public Bound bind(DuckDBTableFunctionBindInfo info) {
            int pageSize = 1_000;
            // A named parameter the query did not pass is not NULL - asking for it throws, and
            // there is no way to ask whether it was passed. So absence is the exception.
            try (DuckDBValue value = info.getNamedParameter("page_size")) {
                if (!value.isNull()) {
                    pageSize = value.getInt();
                }
            }
            catch (DuckDBFunctions.FunctionException notPassed) {
                // keep the default
            }
            info.addResultColumn("id", Long.class);
            info.addResultColumn("source", String.class);
            info.addResultColumn("level", String.class);
            info.addResultColumn("latency_ms", Double.class);
            info.setCardinality(EventFeed.TOTAL, false);
            return new Bound(pageSize);
        }

        @Override
        public State init(DuckDBTableFunctionInitInfo info) {
            // With projection pushdown on, DuckDB tells us which declared columns it needs, and
            // output vector i corresponds to the i-th of those - not to declared column i.
            State state = new State();
            state.projectedColumns = new int[(int) info.getColumnCount()];
            for (int i = 0; i < state.projectedColumns.length; i++) {
                state.projectedColumns[i] = (int) info.getColumnIndex(i);
            }
            return state;
        }

        @Override
        public long apply(DuckDBTableFunctionCallInfo call, DuckDBDataChunkWriter out) {
            int pageSize = call.<Bound>getBindData().pageSize();
            State state = call.getInitData();
            long written = 0;
            while (written < out.capacity() && !state.exhausted) {
                if (state.positionInPage == state.page.size()) {
                    // Only fetch the next page when this one is used up - and only when DuckDB is
                    // still asking for rows.
                    state.page = EventFeed.page(state.nextPage++, pageSize);
                    state.positionInPage = 0;
                    if (state.page.isEmpty()) {
                        state.exhausted = true;
                        break;
                    }
                }
                Event event = state.page.get(state.positionInPage++);
                for (int v = 0; v < state.projectedColumns.length; v++) {
                    switch (state.projectedColumns[v]) {
                        case 0 -> out.vector(v).setLong(written, event.id());
                        case 1 -> out.vector(v).setString(written, event.source());
                        case 2 -> out.vector(v).setString(written, event.level());
                        case 3 -> out.vector(v).setDouble(written, event.latencyMillis());
                        default -> throw new IllegalStateException("column " + state.projectedColumns[v]);
                    }
                    EventFeed.fieldsWritten.incrementAndGet();
                }
                written++;
            }
            return written;
        }
    }

    public static void main(String[] args) throws Exception {
        try (DuckDBConnection connection =
                     (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {

            try (DuckDBTableFunctionBuilder builder = DuckDBFunctions.tableFunction()) {
                builder.withName("event_feed")
                        .withNamedParameter("page_size", Integer.class)
                        .withProjectionPushdown()
                        .withFunction(new FeedTable())
                        .register(connection);
            }

            run(connection, "The whole feed, aggregated in SQL",
                    "SELECT source, count(*) AS events, sum(CASE WHEN level = 'ERROR' THEN 1 ELSE 0 END)"
                            + " AS errors, round(avg(latency_ms), 1) AS avg_ms"
                            + " FROM event_feed() GROUP BY 1 ORDER BY 1");

            run(connection, "A bare LIMIT stops the fetching early",
                    "SELECT id FROM event_feed() LIMIT 5");

            // Every one of the five rows below is in the first page. All 101 are fetched anyway:
            // the filter sits between the LIMIT and the scan, and the scan runs to the end. When a
            // page is a network call, filter inside the function (a parameter), not in WHERE.
            run(connection, "But a WHERE in front of the LIMIT fetches everything",
                    "SELECT id, level FROM event_feed() WHERE level = 'ERROR' LIMIT 5");

            run(connection, "A named parameter: smaller pages, same answer",
                    "SELECT count(*) AS events FROM event_feed(page_size := 250)");

            run(connection, "Projection pushdown: asking for one column fills only one",
                    "SELECT max(latency_ms) AS worst_ms FROM event_feed()");

            // Streams join like anything else. The feed is scanned once, not once per row.
            run(connection, "Joined against a lookup table",
                    "WITH owners(source, team) AS (VALUES ('api','platform'), ('worker','data'),"
                            + " ('web','frontend')) SELECT o.team, count(*) AS errors"
                            + " FROM event_feed() e JOIN owners o USING (source)"
                            + " WHERE e.level = 'ERROR' GROUP BY 1 ORDER BY 2 DESC");
        }
    }

    private static void run(DuckDBConnection connection, String title, String sql) throws SQLException {
        EventFeed.reset();
        System.out.println(title + ":");
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
        System.out.printf("   -> %,d pages fetched, %,d fields written%n%n",
                EventFeed.pagesFetched.get(), EventFeed.fieldsWritten.get());
    }
}

/*
 * Output (Apple Silicon, JDK 25, duckdb_jdbc 1.5.5.1):
 *
 * The whole feed, aggregated in SQL:
 *    source=api  events=25000  errors=1000  avg_ms=498.0
 *    source=web  events=50000  errors=1000  avg_ms=499.5
 *    source=worker  events=25000  errors=0  avg_ms=501.0
 *    -> 101 pages fetched, 300,000 fields written
 *
 * A bare LIMIT stops the fetching early:
 *    id=0
 *    id=1
 *    id=2
 *    id=3
 *    id=4
 *    -> 3 pages fetched, 2,048 fields written
 *
 * But a WHERE in front of the LIMIT fetches everything:
 *    id=0  level=ERROR
 *    id=50  level=ERROR
 *    id=100  level=ERROR
 *    id=150  level=ERROR
 *    id=200  level=ERROR
 *    -> 101 pages fetched, 200,000 fields written
 *
 * A named parameter: smaller pages, same answer:
 *    events=100000
 *    -> 401 pages fetched, 100,000 fields written
 *
 * Projection pushdown: asking for one column fills only one:
 *    worst_ms=999.0
 *    -> 101 pages fetched, 100,000 fields written
 *
 * Joined against a lookup table:
 *    team=platform  errors=1000
 *    team=frontend  errors=1000
 *    -> 101 pages fetched, 200,000 fields written
 */
