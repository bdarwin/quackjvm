package io.quackjvm.core.catalog;

/**
 * A column, with what it means.
 *
 * @param name         the column's name
 * @param sqlType      its DuckDB type, as DuckDB spells it
 * @param javaType     the Java type quackjvm maps it to, or null for a type it does not map
 * @param nullable     whether it accepts nulls
 * @param description  what it holds, from the comment, or null
 * @param unit         what it is measured in, from the comment, or null
 * @param defaultValue its default, as SQL, or null
 */
public record ColumnInfo(String name, String sqlType, Class<?> javaType, boolean nullable, String description,
                         String unit, String defaultValue) {

    /** One line: name, type, unit, whether it can be null, and what it means. */
    public String toText() {
        StringBuilder out = new StringBuilder(name).append(' ').append(sqlType);
        if (unit != null) {
            out.append(" in ").append(unit);
        }
        if (!nullable) {
            out.append(" not null");
        }
        if (description != null) {
            out.append(" - ").append(description);
        }
        return out.toString();
    }
}
