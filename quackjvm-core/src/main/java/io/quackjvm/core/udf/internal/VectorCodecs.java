package io.quackjvm.core.udf.internal;

import io.quackjvm.core.duckdb.DuckDBTypes;
import org.duckdb.DuckDBReadableVector;
import org.duckdb.DuckDBWritableVector;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Reading an argument out of a DuckDB vector and writing a result back, chosen once per function
 * rather than once per row.
 *
 * <p>The types are the ones {@link DuckDBTypes} maps, minus the ones DuckDB's Java vectors have no
 * accessor for in 1.5.5 - {@code UUID}, {@code BLOB} and {@code TIME} - which are refused when the
 * function is registered rather than at the first row.</p>
 */
public final class VectorCodecs {

    /** Reads one row of one vector as a Java value. */
    public interface Reader {
        Object read(DuckDBReadableVector vector, long row);
    }

    /** Writes one Java value into one row of a vector. */
    public interface Writer {
        void write(DuckDBWritableVector vector, long row, Object value);
    }

    private VectorCodecs() {
    }

    /** The class DuckDB should be told the type is, which is not always the Java type. */
    public static Class<?> declaredAs(Class<?> type) {
        if (type == Character.class || type == char.class) {
            return String.class;
        }
        if (type == Instant.class) {
            return LocalDateTime.class;
        }
        if (type.isEnum()) {
            return Integer.class;
        }
        return box(type);
    }

    public static void checkReadable(Class<?> type) {
        String reason = unsupported(type);
        if (reason != null) {
            throw new IllegalArgumentException("A function cannot take a " + type.getSimpleName() + ": " + reason);
        }
    }

    public static void checkWritable(Class<?> type) {
        String reason = unsupported(type);
        if (reason != null) {
            throw new IllegalArgumentException("A function cannot return a " + type.getSimpleName() + ": " + reason);
        }
    }

    private static String unsupported(Class<?> type) {
        if (type == java.util.UUID.class || type == byte[].class || type == java.time.LocalTime.class
                || type == java.sql.Time.class) {
            return "DuckDB's Java vectors have no accessor for it in 1.5.5 - use a String instead";
        }
        if (!DuckDBTypes.isSupported(box(type))) {
            return "quackjvm has no DuckDB type for it";
        }
        return null;
    }

    /** How to read an argument of this Java type. */
    public static Reader readerFor(Class<?> type) {
        Class<?> boxed = box(type);
        if (boxed == String.class || CharSequence.class.isAssignableFrom(boxed)) {
            return DuckDBReadableVector::getString;
        }
        if (boxed == Character.class) {
            return (vector, row) -> {
                String text = vector.getString(row);
                return text == null || text.isEmpty() ? null : Character.valueOf(text.charAt(0));
            };
        }
        if (boxed == Boolean.class) {
            return DuckDBReadableVector::getBoolean;
        }
        if (boxed == Byte.class) {
            return DuckDBReadableVector::getByte;
        }
        if (boxed == Short.class) {
            return DuckDBReadableVector::getShort;
        }
        if (boxed == Integer.class) {
            return DuckDBReadableVector::getInt;
        }
        if (boxed == Long.class) {
            return DuckDBReadableVector::getLong;
        }
        if (boxed == Float.class) {
            return DuckDBReadableVector::getFloat;
        }
        if (boxed == Double.class) {
            return DuckDBReadableVector::getDouble;
        }
        if (boxed == BigInteger.class) {
            return DuckDBReadableVector::getHugeInt;
        }
        if (boxed == BigDecimal.class) {
            return DuckDBReadableVector::getBigDecimal;
        }
        if (boxed.isEnum()) {
            Object[] constants = boxed.getEnumConstants();
            return (vector, row) -> constants[vector.getInt(row)];
        }
        if (boxed == LocalDate.class) {
            return DuckDBReadableVector::getLocalDate;
        }
        if (boxed == java.sql.Date.class) {
            return DuckDBReadableVector::getDate;
        }
        if (boxed == LocalDateTime.class) {
            return DuckDBReadableVector::getLocalDateTime;
        }
        if (boxed == java.sql.Timestamp.class) {
            return DuckDBReadableVector::getTimestamp;
        }
        if (boxed == java.util.Date.class) {
            return (vector, row) -> {
                java.sql.Timestamp timestamp = vector.getTimestamp(row);
                return timestamp == null ? null : new java.util.Date(timestamp.getTime());
            };
        }
        if (boxed == Instant.class) {
            return (vector, row) -> {
                LocalDateTime moment = vector.getLocalDateTime(row);
                return moment == null ? null : moment.toInstant(ZoneOffset.UTC);
            };
        }
        if (boxed == OffsetDateTime.class) {
            return DuckDBReadableVector::getOffsetDateTime;
        }
        throw new IllegalArgumentException("No way to read " + type.getName() + " out of a DuckDB vector");
    }

    /** How to write a result of this Java type. */
    public static Writer writerFor(Class<?> type) {
        Class<?> boxed = box(type);
        if (boxed == Character.class) {
            return (vector, row, value) -> vector.setString(row, String.valueOf(((Character) value).charValue()));
        }
        if (boxed == String.class || CharSequence.class.isAssignableFrom(boxed)) {
            return (vector, row, value) -> vector.setString(row, value.toString());
        }
        if (boxed == Boolean.class) {
            return (vector, row, value) -> vector.setBoolean(row, (Boolean) value);
        }
        if (boxed == Byte.class) {
            return (vector, row, value) -> vector.setByte(row, (Byte) value);
        }
        if (boxed == Short.class) {
            return (vector, row, value) -> vector.setShort(row, (Short) value);
        }
        if (boxed == Integer.class) {
            return (vector, row, value) -> vector.setInt(row, (Integer) value);
        }
        if (boxed == Long.class) {
            return (vector, row, value) -> vector.setLong(row, (Long) value);
        }
        if (boxed == Float.class) {
            return (vector, row, value) -> vector.setFloat(row, (Float) value);
        }
        if (boxed == Double.class) {
            return (vector, row, value) -> vector.setDouble(row, (Double) value);
        }
        if (boxed == BigInteger.class) {
            return (vector, row, value) -> vector.setHugeInt(row, (BigInteger) value);
        }
        if (boxed == BigDecimal.class) {
            return (vector, row, value) -> vector.setBigDecimal(row,
                    ((BigDecimal) value).setScale(DuckDBTypes.DECIMAL_SCALE, RoundingMode.HALF_UP));
        }
        if (boxed.isEnum()) {
            return (vector, row, value) -> vector.setInt(row, ((Enum<?>) value).ordinal());
        }
        if (boxed == LocalDate.class) {
            return (vector, row, value) -> vector.setDate(row, (LocalDate) value);
        }
        if (boxed == java.sql.Date.class) {
            return (vector, row, value) -> vector.setDate(row, ((java.sql.Date) value).toLocalDate());
        }
        if (boxed == LocalDateTime.class) {
            return (vector, row, value) -> vector.setTimestamp(row, (LocalDateTime) value);
        }
        if (boxed == java.sql.Timestamp.class) {
            return (vector, row, value) -> vector.setTimestamp(row, ((java.sql.Timestamp) value).toLocalDateTime());
        }
        if (boxed == java.util.Date.class) {
            return (vector, row, value) -> vector.setTimestamp(row,
                    new java.sql.Timestamp(((java.util.Date) value).getTime()).toLocalDateTime());
        }
        if (boxed == Instant.class) {
            return (vector, row, value) -> vector.setTimestamp(row,
                    LocalDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
        }
        if (boxed == OffsetDateTime.class) {
            return (vector, row, value) -> vector.setOffsetDateTime(row, (OffsetDateTime) value);
        }
        throw new IllegalArgumentException("No way to write " + type.getName() + " into a DuckDB vector");
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
