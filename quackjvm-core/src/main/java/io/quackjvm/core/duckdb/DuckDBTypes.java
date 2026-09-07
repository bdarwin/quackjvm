package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBAppender;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Maps Java attribute types onto DuckDB column types, and converts values in both directions.
 *
 * <p>Types are mapped to the narrowest DuckDB type which preserves Java's {@link Comparable}
 * ordering, so that CQEngine range queries ({@code lessThan}, {@code between}, ...) pushed down
 * into SQL return exactly what an on-heap index would return.</p>
 *
 * <p><b>Enums</b> are stored as their {@code ordinal()} in an INTEGER column. This preserves
 * Java's natural ordering for enums (which is ordinal order, not name order), at the cost of
 * making the persisted data sensitive to the declaration order of the enum constants.</p>
 */
public final class DuckDBTypes {

    /** Scale used for BigDecimal columns. */
    public static final int DECIMAL_SCALE = 10;

    private DuckDBTypes() {
    }

    public static String sqlTypeFor(Class<?> type) {
        String sqlType = sqlTypeForOrNull(type);
        if (sqlType == null) {
            throw new IllegalArgumentException("No DuckDB column type is defined for Java type: " + type.getName()
                    + ". Supported types are the primitive wrappers, String, BigInteger, BigDecimal, UUID, byte[], "
                    + "enums, java.util.Date, java.sql date/time types, and the java.time types "
                    + "LocalDate/LocalTime/LocalDateTime/Instant/OffsetDateTime.");
        }
        return sqlType;
    }

    public static boolean isSupported(Class<?> type) {
        return sqlTypeForOrNull(type) != null;
    }

    private static String sqlTypeForOrNull(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class
                || CharSequence.class.isAssignableFrom(type)) {
            return "VARCHAR";
        }
        if (type == Boolean.class || type == boolean.class) return "BOOLEAN";
        if (type == Byte.class || type == byte.class) return "TINYINT";
        if (type == Short.class || type == short.class) return "SMALLINT";
        if (type == Integer.class || type == int.class) return "INTEGER";
        if (type == Long.class || type == long.class) return "BIGINT";
        if (type == Float.class || type == float.class) return "FLOAT";
        if (type == Double.class || type == double.class) return "DOUBLE";
        if (type == BigInteger.class) return "HUGEINT";
        if (type == BigDecimal.class) return "DECIMAL(38," + DECIMAL_SCALE + ")";
        if (type == UUID.class) return "UUID";
        if (type == byte[].class) return "BLOB";
        if (type.isEnum()) return "INTEGER";
        if (type == java.sql.Date.class || type == LocalDate.class) return "DATE";
        if (type == java.sql.Time.class || type == LocalTime.class) return "TIME";
        if (type == java.sql.Timestamp.class || type == java.util.Date.class
                || type == LocalDateTime.class || type == Instant.class) {
            return "TIMESTAMP";
        }
        if (type == OffsetDateTime.class) return "TIMESTAMP WITH TIME ZONE";
        return null;
    }

    /**
     * Converts a Java attribute value into the value which the DuckDB JDBC driver expects
     * for the column type returned by {@link #sqlTypeFor(Class)}.
     *
     * <p>Date and time values are all normalised to {@code java.time} types, so that a value
     * written through a prepared statement and the same value written through the Appender end up
     * identical in the database.</p>
     */
    public static Object toSqlValue(Object value) {
        if (value == null) return null;
        if (value instanceof Enum) return ((Enum<?>) value).ordinal();
        if (value instanceof Character) return String.valueOf(((Character) value).charValue());
        if (value instanceof Instant) return LocalDateTime.ofInstant((Instant) value, ZoneOffset.UTC);
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        if (value instanceof java.sql.Time) return ((java.sql.Time) value).toLocalTime();
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        if (value instanceof java.util.Date) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(((java.util.Date) value).getTime()),
                    ZoneId.systemDefault());
        }
        if (value instanceof CharSequence && !(value instanceof String)) return value.toString();
        return value;
    }

    public static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        statement.setObject(index, toSqlValue(value));
    }

    /**
     * Appends a value to a {@link DuckDBAppender}, which is the fastest way to bulk-load DuckDB.
     */
    public static void append(DuckDBAppender appender, Object value, Class<?> javaType) throws SQLException {
        if (value == null) {
            appender.appendNull();
            return;
        }
        Object v = toSqlValue(value);
        if (v instanceof String) appender.append((String) v);
        else if (v instanceof Integer) appender.append((Integer) v);
        else if (v instanceof Long) appender.append((Long) v);
        else if (v instanceof Boolean) appender.append((Boolean) v);
        else if (v instanceof Short) appender.append((Short) v);
        else if (v instanceof Byte) appender.append((Byte) v);
        else if (v instanceof Float) appender.append((Float) v);
        else if (v instanceof Double) appender.append((Double) v);
        else if (v instanceof byte[]) appender.append((byte[]) v);
        else if (v instanceof BigDecimal) appender.append(((BigDecimal) v).setScale(DECIMAL_SCALE, RoundingMode.HALF_UP));
        else if (v instanceof BigInteger) appender.append((BigInteger) v);
        else if (v instanceof UUID) appender.append((UUID) v);
        else if (v instanceof LocalDateTime) appender.append((LocalDateTime) v);
        else if (v instanceof LocalDate) appender.append((LocalDate) v);
        else if (v instanceof LocalTime) appender.append((LocalTime) v);
        else if (v instanceof OffsetDateTime) appender.append((OffsetDateTime) v);
        else {
            throw new IllegalStateException("Cannot append value of type " + v.getClass().getName()
                    + " (declared attribute type " + javaType.getName() + ") to DuckDB");
        }
    }

    /** Reads one column of a JDBC row as a Java value of a known type. */
    public interface ColumnReader<T> {
        T read(ResultSet resultSet, int index) throws SQLException;
    }

    private static final ClassValue<ColumnReader<?>> READERS = new ClassValue<>() {
        @Override
        protected ColumnReader<?> computeValue(Class<?> type) {
            return createReader(type);
        }
    };

    /**
     * Returns a reader for the given Java type. Resolving the type once and reusing the reader
     * keeps object materialisation off the type-dispatch path, which matters when a query
     * materialises hundreds of thousands of objects column by column.
     */
    @SuppressWarnings("unchecked")
    public static <T> ColumnReader<T> readerFor(Class<T> type) {
        return (ColumnReader<T>) READERS.get(type);
    }

    /**
     * Reads column {@code index} of the given JDBC result set back as the given Java type.
     *
     * <p>The DuckDB driver's {@code getObject(index, Class)} does not support every conversion
     * (notably not {@code LocalTime}), so values are read with {@code getObject(index)} and
     * converted here.</p>
     */
    public static <T> T read(ResultSet resultSet, int index, Class<T> type) throws SQLException {
        return DuckDBTypes.<T>readerFor(type).read(resultSet, index);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ColumnReader<?> createReader(Class<?> type) {
        if (type == String.class) return ResultSet::getString;
        if (type == Boolean.class || type == boolean.class) return (rs, i) -> nullable(rs, rs.getBoolean(i));
        if (type == Byte.class || type == byte.class) return (rs, i) -> nullable(rs, rs.getByte(i));
        if (type == Short.class || type == short.class) return (rs, i) -> nullable(rs, rs.getShort(i));
        if (type == Integer.class || type == int.class) return (rs, i) -> nullable(rs, rs.getInt(i));
        if (type == Long.class || type == long.class) return (rs, i) -> nullable(rs, rs.getLong(i));
        if (type == Float.class || type == float.class) return (rs, i) -> nullable(rs, rs.getFloat(i));
        if (type == Double.class || type == double.class) return (rs, i) -> nullable(rs, rs.getDouble(i));
        if (type == byte[].class) return ResultSet::getBytes;
        if (type == BigDecimal.class) return ResultSet::getBigDecimal;
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return (rs, i) -> {
                int ordinal = rs.getInt(i);
                return rs.wasNull() ? null : constants[ordinal];
            };
        }
        // Timestamp columns are read with the typed getter rather than getObject(), which avoids
        // several intermediate conversions per value when materialising many objects.
        if (type == java.util.Date.class) {
            return (rs, i) -> {
                java.sql.Timestamp timestamp = rs.getTimestamp(i);
                return timestamp == null ? null : new java.util.Date(timestamp.getTime());
            };
        }
        if (type == java.sql.Timestamp.class) return ResultSet::getTimestamp;
        if (type == LocalDateTime.class) {
            return (rs, i) -> {
                java.sql.Timestamp timestamp = rs.getTimestamp(i);
                return timestamp == null ? null : timestamp.toLocalDateTime();
            };
        }
        return (rs, i) -> fromSqlValue(rs.getObject(i), type);
    }

    /** Converts a value as returned by the DuckDB driver back into the declared Java type. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object fromSqlValue(Object value, Class<?> type) {
        if (value == null) {
            return null;
        }
        if (type == Character.class || type == char.class) {
            String text = value.toString();
            return text.isEmpty() ? null : text.charAt(0);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[((Number) value).intValue()];
        }
        if (type == BigInteger.class) {
            if (value instanceof BigInteger) return value;
            if (value instanceof BigDecimal) return ((BigDecimal) value).toBigInteger();
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (type == UUID.class) {
            return value instanceof UUID ? value : UUID.fromString(value.toString());
        }
        if (type == LocalDate.class) {
            return value instanceof java.sql.Date ? ((java.sql.Date) value).toLocalDate() : value;
        }
        if (type == LocalTime.class) {
            return value instanceof java.sql.Time ? ((java.sql.Time) value).toLocalTime() : value;
        }
        if (type == LocalDateTime.class) {
            return toLocalDateTime(value);
        }
        if (type == Instant.class) {
            return toLocalDateTime(value).toInstant(ZoneOffset.UTC);
        }
        if (type == OffsetDateTime.class) {
            return value instanceof OffsetDateTime
                    ? value
                    : toLocalDateTime(value).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (type == java.sql.Date.class) {
            return java.sql.Date.valueOf(value instanceof LocalDate ? (LocalDate) value : toLocalDateTime(value).toLocalDate());
        }
        if (type == java.sql.Time.class) {
            return java.sql.Time.valueOf(value instanceof LocalTime ? (LocalTime) value : toLocalDateTime(value).toLocalTime());
        }
        if (type == java.sql.Timestamp.class) {
            return java.sql.Timestamp.valueOf(toLocalDateTime(value));
        }
        if (type == java.util.Date.class) {
            return java.util.Date.from(toLocalDateTime(value).atZone(ZoneId.systemDefault()).toInstant());
        }
        if (CharSequence.class.isAssignableFrom(type)) {
            return value.toString();
        }
        if (type.isInstance(value)) {
            return value;
        }
        throw new IllegalArgumentException("Cannot convert " + value.getClass().getName()
                + " read from DuckDB into " + type.getName());
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        if (value instanceof OffsetDateTime) return ((OffsetDateTime) value).toLocalDateTime();
        if (value instanceof LocalDate) return ((LocalDate) value).atStartOfDay();
        throw new IllegalArgumentException("Cannot read a timestamp from " + value.getClass().getName());
    }

    private static Object nullable(ResultSet resultSet, Object primitiveValue) throws SQLException {
        return resultSet.wasNull() ? null : primitiveValue;
    }
}
