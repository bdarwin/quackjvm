package io.quackjvm.core.live.internal;

import io.quackjvm.core.duckdb.DuckDBTypes;
import org.duckdb.DuckDBWritableVector;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Writing a Java value into one row of a DuckDB vector, chosen once per column rather than per value.
 *
 * <p>DuckDB's Java chunk writer covers most of what quackjvm can store, but not all of it: in 1.5.5
 * there is no setter for {@code UUID}, {@code BLOB} or {@code TIME}. A layout with one of those is
 * refused when the live table is registered, naming the column, rather than failing on the first row.</p>
 */
public final class ChunkWriters {

    /** Writes one value into one row of one vector. */
    public interface ChunkWriter {
        void write(DuckDBWritableVector vector, long row, Object value);
    }

    private ChunkWriters() {
    }

    /** Why this type cannot be written into a chunk, or null when it can. */
    public static String unsupportedReason(Class<?> type) {
        if (type == java.util.UUID.class) {
            return "DuckDB's Java chunk writer has no way to write a UUID in 1.5.5 - map it to String in"
                    + " the layout, or store the objects in a table instead";
        }
        if (type == byte[].class) {
            return "DuckDB's Java chunk writer has no way to write a BLOB in 1.5.5 - map it to String in"
                    + " the layout, or store the objects in a table instead";
        }
        if (type == java.time.LocalTime.class || type == java.sql.Time.class) {
            return "DuckDB's Java chunk writer has no way to write a TIME in 1.5.5 - map it to String, or"
                    + " to a LocalDateTime, in the layout";
        }
        if (!DuckDBTypes.isSupported(type)) {
            return "quackjvm has no DuckDB type for " + type.getName();
        }
        return null;
    }

    /** The writer for a column of this Java type. */
    public static ChunkWriter forType(Class<?> type) {
        String unsupported = unsupportedReason(type);
        if (unsupported != null) {
            throw new IllegalArgumentException(unsupported);
        }
        if (type == Character.class) {
            return (vector, row, value) -> vector.setString(row, String.valueOf(((Character) value).charValue()));
        }
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
            return (vector, row, value) -> vector.setString(row, value.toString());
        }
        if (type == Boolean.class) {
            return (vector, row, value) -> vector.setBoolean(row, (Boolean) value);
        }
        if (type == Byte.class) {
            return (vector, row, value) -> vector.setByte(row, (Byte) value);
        }
        if (type == Short.class) {
            return (vector, row, value) -> vector.setShort(row, (Short) value);
        }
        if (type == Integer.class) {
            return (vector, row, value) -> vector.setInt(row, (Integer) value);
        }
        if (type == Long.class) {
            return (vector, row, value) -> vector.setLong(row, (Long) value);
        }
        if (type == Float.class) {
            return (vector, row, value) -> vector.setFloat(row, (Float) value);
        }
        if (type == Double.class) {
            return (vector, row, value) -> vector.setDouble(row, (Double) value);
        }
        if (type == BigInteger.class) {
            return (vector, row, value) -> vector.setHugeInt(row, (BigInteger) value);
        }
        if (type == BigDecimal.class) {
            return (vector, row, value) -> vector.setBigDecimal(row,
                    ((BigDecimal) value).setScale(DuckDBTypes.DECIMAL_SCALE, RoundingMode.HALF_UP));
        }
        if (type.isEnum()) {
            // The ordinal, exactly as a table would store it.
            return (vector, row, value) -> vector.setInt(row, ((Enum<?>) value).ordinal());
        }
        if (type == LocalDate.class) {
            return (vector, row, value) -> vector.setDate(row, (LocalDate) value);
        }
        if (type == java.sql.Date.class) {
            return (vector, row, value) -> vector.setDate(row, ((java.sql.Date) value).toLocalDate());
        }
        if (type == LocalDateTime.class) {
            return (vector, row, value) -> vector.setTimestamp(row, (LocalDateTime) value);
        }
        if (type == Instant.class) {
            return (vector, row, value) -> vector.setTimestamp(row,
                    LocalDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
        }
        if (type == java.sql.Timestamp.class) {
            return (vector, row, value) -> vector.setTimestamp(row, ((java.sql.Timestamp) value).toLocalDateTime());
        }
        if (type == java.util.Date.class) {
            return (vector, row, value) -> vector.setTimestamp(row, LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(((java.util.Date) value).getTime()), ZoneId.systemDefault()));
        }
        if (type == OffsetDateTime.class) {
            return (vector, row, value) -> vector.setOffsetDateTime(row, (OffsetDateTime) value);
        }
        throw new IllegalArgumentException("No way to write " + type.getName() + " into a DuckDB chunk");
    }
}
