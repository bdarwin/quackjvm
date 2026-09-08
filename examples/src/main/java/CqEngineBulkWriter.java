/*
 * CQEngine: DuckDBBulkWriter, for loading objects which arrive as a stream rather than as a list.
 *
 * Run it with:
 *
 *   cd examples
 *   mvn -q generate-resources
 *   java --enable-native-access=ALL-UNNAMED \
 *        -cp "$(cat target/classpath.txt)" \
 *        src/main/java/CqEngineBulkWriter.java [objectCount]
 */

import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import io.quackjvm.core.layout.ColumnarLayout;
import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBBulkWriter;
import io.quackjvm.cqengine.persistence.DuckDBIndexedCollection;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.googlecode.cqengine.query.QueryFactory.and;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;

public class CqEngineBulkWriter {

    public record Event(long eventId, String source, String level, double latencyMillis) {
        static final SimpleAttribute<Event, Long> EVENT_ID =
                new SimpleAttribute<>(Event.class, Long.class, "eventId") {
                    public Long getValue(Event event, QueryOptions options) {
                        return event.eventId();
                    }
                };
        static final Attribute<Event, String> SOURCE =
                new SimpleAttribute<>(Event.class, String.class, "source") {
                    public String getValue(Event event, QueryOptions options) {
                        return event.source();
                    }
                };
        static final Attribute<Event, String> LEVEL =
                new SimpleAttribute<>(Event.class, String.class, "level") {
                    public String getValue(Event event, QueryOptions options) {
                        return event.level();
                    }
                };
        static final Attribute<Event, Double> LATENCY =
                new SimpleAttribute<>(Event.class, Double.class, "latencyMillis") {
                    public Double getValue(Event event, QueryOptions options) {
                        return event.latencyMillis();
                    }
                };
    }

    private static final String[] SOURCES = {"api", "worker", "cron", "web"};
    private static final String[] LEVELS = {"INFO", "WARN", "ERROR"};

    public static void main(String[] args) {
        int objectCount = args.length > 0 ? Integer.parseInt(args[0]) : 500_000;

        try (DuckDBPersistence<Event, Long> persistence = DuckDBPersistence.builder(Event.EVENT_ID)
                .columnarLayout(ColumnarLayout.ofRecord(Event.class))
                .memoryLimit("256MB")
                .build()) {

            IndexedCollection<Event> events = new DuckDBIndexedCollection<>(persistence);

            // Indexes must exist before the writer opens: it appends to the index tables it finds
            // at that moment.
            events.addIndex(DuckDBIndex.onAttribute(Event.SOURCE));
            events.addIndex(DuckDBIndex.onAttribute(Event.LEVEL));

            long startedAt = System.nanoTime();
            // The writer holds the write lock for its lifetime and flushes on close. Objects have
            // to be new: rows are appended, not merged, so a repeated primary key fails at flush.
            try (DuckDBBulkWriter<Event> writer = persistence.bulkWriter();
                 Stream<Event> incoming = events(objectCount)) {

                incoming.forEach(writer::add);
                // Nothing is guaranteed visible until a flush; close() does one.
                writer.flush();
                System.out.printf("Writer accepted %,d objects%n", writer.getObjectsWritten());
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            System.out.printf("Streamed %,d events in %.2f s (%.2f us each)%n",
                    objectCount, elapsedNanos / 1e9, elapsedNanos / 1000.0 / objectCount);
            System.out.printf("Collection size: %,d, DuckDB using %,d MB%n",
                    events.size(), persistence.getBytesUsed() / 1024 / 1024);

            // The indexes were populated as the objects streamed past, so queries work immediately.
            try (ResultSet<Event> results = events.retrieve(
                    and(equal(Event.LEVEL, "ERROR"), equal(Event.SOURCE, "worker"),
                            greaterThan(Event.LATENCY, 900.0)))) {
                System.out.printf("Slow worker errors: %,d, for instance %s%n",
                        results.size(), results.iterator().next());
            }
        }
    }

    /** Stands in for a cursor, a file being parsed, or anything else which is not already a list. */
    private static Stream<Event> events(int objectCount) {
        return IntStream.range(0, objectCount).mapToObj(i ->
                new Event(i, SOURCES[i % SOURCES.length], LEVELS[(i / 7) % LEVELS.length],
                        (i * 7919L) % 1000 / 1.0));
    }
}

/*
 * Output (Apple Silicon, JDK 25, default 500,000 objects):
 *
 * Writer accepted 500,000 objects
 * Streamed 500,000 events in 0.73 s (1.46 us each)
 * Collection size: 500,000, DuckDB using 37 MB
 * Slow worker errors: 4,165, for instance Event[eventId=161, source=worker, level=ERROR, latencyMillis=959.0]
 */
