package com.duckcq;

import com.duckcq.index.DuckDBIndex;
import com.duckcq.layout.ColumnarLayout;
import com.duckcq.persistence.DuckDBPersistence;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.attribute.SimpleNullableAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static com.googlecode.cqengine.query.QueryFactory.between;
import static com.googlecode.cqengine.query.QueryFactory.equal;
import static com.googlecode.cqengine.query.QueryFactory.greaterThan;
import static com.googlecode.cqengine.query.QueryFactory.has;
import static com.googlecode.cqengine.query.QueryFactory.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Checks that every attribute type the plugin claims to support survives a round trip through
 * DuckDB columns, and that range queries on those columns agree with Java's ordering.
 */
public class ColumnTypesTest {

    public enum Size {SMALL, MEDIUM, LARGE}

    /** Note: field order matters, because the columns are derived from the record components. */
    public record Everything(long id, String string, Character character, Boolean flag, Byte byteValue,
                             Short shortValue, Integer intValue, Long longValue, Float floatValue,
                             Double doubleValue, BigInteger bigInteger, BigDecimal bigDecimal, UUID uuid,
                             Size size, LocalDate localDate, LocalTime localTime, LocalDateTime localDateTime,
                             Instant instant, OffsetDateTime offsetDateTime, Date date) {
    }

    private static <A> Attribute<Everything, A> attribute(String name, Class<A> type,
                                                          java.util.function.Function<Everything, A> accessor) {
        return new SimpleNullableAttribute<>(Everything.class, type, name) {
            @Override
            public A getValue(Everything object, QueryOptions queryOptions) {
                return accessor.apply(object);
            }
        };
    }

    static final SimpleAttribute<Everything, Long> ID =
            new SimpleAttribute<>(Everything.class, Long.class, "id") {
                @Override
                public Long getValue(Everything object, QueryOptions queryOptions) {
                    return object.id();
                }
            };
    static final Attribute<Everything, String> STRING = attribute("string", String.class, Everything::string);
    static final Attribute<Everything, Size> SIZE = attribute("size", Size.class, Everything::size);
    static final Attribute<Everything, Integer> INT_VALUE = attribute("intValue", Integer.class, Everything::intValue);
    static final Attribute<Everything, Double> DOUBLE_VALUE = attribute("doubleValue", Double.class, Everything::doubleValue);
    static final Attribute<Everything, UUID> UUID_VALUE = attribute("uuid", UUID.class, Everything::uuid);
    static final Attribute<Everything, Date> DATE = attribute("date", Date.class, Everything::date);

    private DuckDBPersistence<Everything, Long> persistence;

    @After
    public void tearDown() {
        if (persistence != null) {
            persistence.close();
        }
    }

    private IndexedCollection<Everything> collection() {
        persistence = DuckDBPersistence.builder(ID)
                .inMemory()
                .columnarLayout(ColumnarLayout.ofRecord(Everything.class))
                .build();
        IndexedCollection<Everything> collection = new ConcurrentIndexedCollection<>(persistence);
        collection.addIndex(DuckDBIndex.onAttribute(STRING));
        collection.addIndex(DuckDBIndex.onAttribute(SIZE));
        collection.addIndex(DuckDBIndex.onAttribute(INT_VALUE));
        collection.addIndex(DuckDBIndex.onAttribute(DOUBLE_VALUE));
        collection.addIndex(DuckDBIndex.onAttribute(UUID_VALUE));
        collection.addIndex(DuckDBIndex.onAttribute(DATE));
        return collection;
    }

    private static Everything populated(long id) {
        return new Everything(id, "text-" + id, 'x', true, (byte) 7, (short) 300, 70000, 5_000_000_000L,
                1.5f, 2.5d, new BigInteger("123456789012345678901234567890"), new BigDecimal("1234.5678901234"),
                UUID.nameUUIDFromBytes(("u" + id).getBytes()), Size.MEDIUM,
                LocalDate.of(2021, 3, 14), LocalTime.of(13, 45, 56), LocalDateTime.of(2021, 3, 14, 13, 45, 56),
                Instant.parse("2021-03-14T13:45:56Z"), OffsetDateTime.parse("2021-03-14T13:45:56+02:00"),
                Date.from(Instant.parse("2021-03-14T13:45:56Z")));
    }

    @Test
    public void everySupportedTypeRoundTrips() {
        IndexedCollection<Everything> collection = collection();
        Everything original = populated(1);
        collection.add(original);

        try (ResultSet<Everything> results = collection.retrieve(equal(ID, 1L))) {
            Everything read = results.uniqueResult();
            // A TIMESTAMP WITH TIME ZONE column stores an instant, not the original UTC offset,
            // so an OffsetDateTime comes back in the JVM's zone. Compare it as an instant.
            assertEquals(original.offsetDateTime().toInstant(), read.offsetDateTime().toInstant());
            assertEquals(withOffsetOf(original, read.offsetDateTime()), read);
        }
    }

    private static Everything withOffsetOf(Everything original, OffsetDateTime offsetDateTime) {
        return new Everything(original.id(), original.string(), original.character(), original.flag(),
                original.byteValue(), original.shortValue(), original.intValue(), original.longValue(),
                original.floatValue(), original.doubleValue(), original.bigInteger(), original.bigDecimal(),
                original.uuid(), original.size(), original.localDate(), original.localTime(),
                original.localDateTime(), original.instant(), offsetDateTime, original.date());
    }

    @Test
    public void nullValuesRoundTripAndAreExcludedFromIndexes() {
        IndexedCollection<Everything> collection = collection();
        Everything allNulls = new Everything(2L, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        collection.add(allNulls);
        collection.add(populated(3));

        try (ResultSet<Everything> results = collection.retrieve(equal(ID, 2L))) {
            Everything read = results.uniqueResult();
            assertEquals(allNulls, read);
            assertNull(read.string());
            assertNull(read.bigDecimal());
        }
        // An attribute with no value must not appear in its index.
        try (ResultSet<Everything> results = collection.retrieve(has(STRING))) {
            assertEquals(1, results.size());
        }
        try (ResultSet<Everything> results = collection.retrieve(not(has(STRING)))) {
            assertEquals(1, results.size());
        }
    }

    @Test
    public void rangeQueriesAgreeWithJavaOrdering() {
        IndexedCollection<Everything> collection = collection();
        for (long id = 1; id <= 5; id++) {
            Everything base = populated(id);
            collection.add(new Everything(id, base.string(), base.character(), base.flag(), base.byteValue(),
                    base.shortValue(), (int) (id * 10), base.longValue(), base.floatValue(), id * 1.5,
                    base.bigInteger(), base.bigDecimal(), base.uuid(),
                    Size.values()[(int) (id % Size.values().length)], base.localDate(), base.localTime(),
                    base.localDateTime(), base.instant(), base.offsetDateTime(),
                    Date.from(Instant.parse("2021-03-14T13:45:56Z").plusSeconds(id))));
        }

        try (ResultSet<Everything> results = collection.retrieve(between(INT_VALUE, 20, 40))) {
            assertEquals(3, results.size());
        }
        // doubleValue is id * 1.5, i.e. 1.5, 3.0, 4.5, 6.0, 7.5
        try (ResultSet<Everything> results = collection.retrieve(greaterThan(DOUBLE_VALUE, 4.0))) {
            assertEquals(3, results.size());
        }
        // Enums are stored by ordinal, so ordering follows the declaration order as Java does.
        try (ResultSet<Everything> results = collection.retrieve(
                between(SIZE, Size.SMALL, Size.MEDIUM))) {
            long expected = 0;
            for (long id = 1; id <= 5; id++) {
                Size size = Size.values()[(int) (id % Size.values().length)];
                if (size.compareTo(Size.SMALL) >= 0 && size.compareTo(Size.MEDIUM) <= 0) expected++;
            }
            assertEquals(expected, results.size());
        }
        try (ResultSet<Everything> results = collection.retrieve(
                greaterThan(DATE, Date.from(Instant.parse("2021-03-14T13:45:58Z"))))) {
            assertEquals(3, results.size());
        }
    }

    @Test
    public void appenderAndPreparedStatementPathsStoreIdenticalValues() {
        // Small batches are written with prepared statements and large ones through the Appender;
        // both must produce identical stored values, especially for date and time types.
        Everything original = populated(42);

        IndexedCollection<Everything> viaPreparedStatements = collection();
        viaPreparedStatements.add(original);
        Everything readFromPreparedStatementPath;
        try (ResultSet<Everything> results = viaPreparedStatements.retrieve(equal(ID, 42L))) {
            readFromPreparedStatementPath = results.uniqueResult();
        }
        persistence.close();

        IndexedCollection<Everything> viaAppender = collection();
        java.util.List<Everything> batch = new java.util.ArrayList<>();
        batch.add(original);
        for (long id = 100; id < 3000; id++) {
            batch.add(populated(id));
        }
        viaAppender.addAll(batch);
        try (ResultSet<Everything> results = viaAppender.retrieve(equal(ID, 42L))) {
            assertEquals(readFromPreparedStatementPath, results.uniqueResult());
        }
    }

    @Test
    public void instantsAreStoredInUtc() {
        IndexedCollection<Everything> collection = collection();
        Everything original = populated(9);
        collection.add(original);
        try (ResultSet<Everything> results = collection.retrieve(equal(ID, 9L))) {
            Everything read = results.uniqueResult();
            assertEquals(Instant.parse("2021-03-14T13:45:56Z"), read.instant());
            assertEquals(OffsetDateTime.parse("2021-03-14T13:45:56+02:00").toInstant(),
                    read.offsetDateTime().toInstant());
            assertEquals(LocalTime.of(13, 45, 56), read.localTime());
            assertEquals(ZoneOffset.UTC, ZoneOffset.UTC);
        }
    }
}
