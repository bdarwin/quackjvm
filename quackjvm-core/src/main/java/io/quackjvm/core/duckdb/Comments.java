package io.quackjvm.core.duckdb;

import io.quackjvm.core.json.JsonReader;

import java.util.Map;

/**
 * What a DuckDB comment holds: a description, and sometimes a unit.
 *
 * <p>A comment is one string, so a unit is stored by writing the pair as a small JSON object. A
 * comment that is not one of those - written by hand, or by a migration that knew nothing about this -
 * is read as a plain description, which is what it is.</p>
 */
public record Comments(String description, String unit) {

    /** Reads a comment as it was written into the database. */
    public static Comments parse(String comment) {
        if (comment == null || comment.isBlank()) {
            return new Comments(null, null);
        }
        String trimmed = comment.trim();
        if (trimmed.startsWith("{")) {
            try {
                Object parsed = JsonReader.parse(trimmed);
                if (parsed instanceof Map<?, ?> object && (object.containsKey("description") || object.containsKey("unit"))) {
                    return new Comments(text(object.get("description")), text(object.get("unit")));
                }
            }
            catch (RuntimeException notJson) {
                // A comment that happens to start with a brace. Take it as it is.
            }
        }
        return new Comments(trimmed, null);
    }

    /** How this is written into the database: plain text, or JSON when there is a unit to keep. */
    public String toComment() {
        if (unit == null || unit.isBlank()) {
            return description == null ? "" : description;
        }
        return "{\"description\":" + json(description) + ",\"unit\":" + json(unit) + "}";
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** {@code COMMENT ON COLUMN t.c IS '...'}, or null when there is nothing to say. */
    public static String columnStatement(String table, ColumnDef column) {
        if (column.getDescription() == null && column.getUnit() == null) {
            return null;
        }
        return "COMMENT ON COLUMN " + Sql.quote(table) + "." + Sql.quote(column.getName()) + " IS "
                + literal(new Comments(column.getDescription(), column.getUnit()).toComment());
    }

    /** {@code COMMENT ON TABLE t IS '...'}. */
    public static String tableStatement(String table, String description) {
        return "COMMENT ON TABLE " + Sql.quote(table) + " IS " + literal(description);
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
