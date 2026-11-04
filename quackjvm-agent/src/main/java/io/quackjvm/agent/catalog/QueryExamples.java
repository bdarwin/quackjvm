package io.quackjvm.agent.catalog;

import io.quackjvm.agent.catalog.internal.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ready-made queries for a table, written from its columns alone - for a person who has just opened a
 * file, or an agent that needs a start. Nothing is read from the table to write them.
 *
 * <pre>
 * for (QueryExamples.Example example : catalog.examples("log")) {
 *     System.out.println("-- " + example.title() + "\n" + example.sql());
 * }
 * </pre>
 *
 * <p>Which ones come back depends on the columns' kinds: a count and the first rows always; the range
 * of a number; counts by a text column; rows over time for a date or timestamp; a text search. A
 * family of numbered columns - {@code cpu_0001} to {@code cpu_1500} - is queried all at once with
 * {@code COLUMNS('^cpu_[0-9]+$')} rather than column by column, and left out of the others. When
 * another table has a column of the same name and kind, there is a join over it.</p>
 */
public final class QueryExamples {

    /** One query, and what it shows. */
    public record Example(String title, String sql) {
    }

    /** At least this many numbered columns of the same name make a family. */
    static final int FAMILY = 3;

    /** Wider than this, the first rows show only the first columns. */
    static final int WIDE = 20;

    private static final int JOINS = 3;

    private static final Pattern NUMBERED = Pattern.compile("(.*?[^0-9])([0-9]+)");

    /** Names a text column to search more likely holds free text under. */
    private static final Set<String> TEXTY = Set.of("message", "msg", "text", "line", "body", "description",
            "comment", "content", "detail", "details", "summary", "title");

    private enum Kind { NUMBER, TIME, DATE, TEXT, OTHER }

    private QueryExamples() {
    }

    /** The examples for a table; joins are offered to the others that share a column with it. */
    public static List<Example> of(TableInfo table, List<TableInfo> others) {
        String from = SqlNames.write(table.name());
        List<ColumnInfo> columns = table.columns();

        Map<String, List<ColumnInfo>> families = families(columns);
        Set<String> inFamily = new java.util.HashSet<>();
        for (List<ColumnInfo> family : families.values()) {
            for (ColumnInfo column : family) {
                inFamily.add(column.name());
            }
        }
        ColumnInfo number = first(columns, Kind.NUMBER, inFamily);
        ColumnInfo time = first(columns, Kind.TIME, inFamily);
        if (time == null) {
            time = first(columns, Kind.DATE, inFamily);
        }
        ColumnInfo text = first(columns, Kind.TEXT, inFamily);
        ColumnInfo searched = searched(columns, inFamily);

        List<Example> examples = new ArrayList<>();
        examples.add(new Example("How many rows", "SELECT count(*) AS rows FROM " + from));
        if (columns.size() > WIDE) {
            List<String> firstColumns = new ArrayList<>();
            for (ColumnInfo column : columns.subList(0, 10)) {
                firstColumns.add(SqlNames.write(column.name()));
            }
            examples.add(new Example("The first rows, first 10 of " + columns.size() + " columns",
                    "SELECT " + String.join(", ", firstColumns) + " FROM " + from + " LIMIT 10"));
        }
        else {
            examples.add(new Example("The first rows", "SELECT * FROM " + from + " LIMIT 10"));
        }
        if (number != null) {
            String n = SqlNames.write(number.name());
            examples.add(new Example("The range of " + number.name(),
                    "SELECT min(" + n + ") AS lowest, max(" + n + ") AS highest, avg(" + n + ") AS mean FROM " + from));
        }
        if (text != null) {
            String t = SqlNames.write(text.name());
            String also = number == null ? "" : ", avg(" + SqlNames.write(number.name()) + ") AS mean_"
                    + plain(number.name());
            examples.add(new Example("Rows for each " + text.name(), "SELECT " + t + ", count(*) AS rows" + also
                    + " FROM " + from + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20"));
        }
        if (time != null) {
            String unit = kind(time) == Kind.DATE ? "month" : "hour";
            examples.add(new Example("Rows each " + unit + " by " + time.name(),
                    "SELECT date_trunc('" + unit + "', " + SqlNames.write(time.name()) + ") AS " + unit
                            + ", count(*) AS rows FROM " + from + " GROUP BY 1 ORDER BY 1"));
        }
        if (searched != null) {
            examples.add(new Example("Rows whose " + searched.name() + " mentions a word",
                    "SELECT * FROM " + from + " WHERE " + SqlNames.write(searched.name())
                            + " ILIKE '%error%' LIMIT 50"));
        }
        for (Map.Entry<String, List<ColumnInfo>> family : families.entrySet()) {
            String pattern = "^" + SqlNames.regexLiteral(family.getKey()) + "[0-9]+$";
            List<ColumnInfo> members = family.getValue();
            if (kind(members.get(0)) == Kind.NUMBER) {
                examples.add(new Example("The mean of each of " + members.size() + " " + family.getKey() + "N columns",
                        "SELECT avg(COLUMNS('" + pattern + "')) FROM " + from));
            }
            else {
                examples.add(new Example("The " + members.size() + " " + family.getKey() + "N columns",
                        "SELECT COLUMNS('" + pattern + "') FROM " + from + " LIMIT 10"));
            }
        }
        int joins = 0;
        for (TableInfo other : others) {
            if (joins == JOINS || other.name().equalsIgnoreCase(table.name())) {
                continue;
            }
            ColumnInfo shared = shared(columns, other.columns(), inFamily);
            if (shared != null) {
                examples.add(new Example("Joined to " + other.name() + " by " + shared.name(),
                        "SELECT * FROM " + from + " JOIN " + SqlNames.write(other.name()) + " USING ("
                                + SqlNames.write(shared.name()) + ") LIMIT 10"));
                joins++;
            }
        }
        return examples;
    }

    /** Numbered columns of the same name, in the order the first of each appears; three or more of a kind. */
    private static Map<String, List<ColumnInfo>> families(List<ColumnInfo> columns) {
        Map<String, List<ColumnInfo>> byPrefix = new LinkedHashMap<>();
        for (ColumnInfo column : columns) {
            Matcher m = NUMBERED.matcher(column.name());
            if (m.matches()) {
                byPrefix.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(column);
            }
        }
        Map<String, List<ColumnInfo>> families = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnInfo>> entry : byPrefix.entrySet()) {
            List<ColumnInfo> members = entry.getValue();
            Kind kind = kind(members.get(0));
            if (members.size() >= FAMILY && members.stream().allMatch(c -> kind(c) == kind)
                    && everyColumnLikeItIsAMember(columns, entry.getKey(), members.size())) {
                families.put(entry.getKey(), members);
            }
        }
        return families;
    }

    /** {@code COLUMNS('^cpu_[0-9]+$')} must pick exactly the family, and nothing else that looks like it. */
    private static boolean everyColumnLikeItIsAMember(List<ColumnInfo> columns, String prefix, int members) {
        Pattern pattern = Pattern.compile(Pattern.quote(prefix) + "[0-9]+");
        return columns.stream().filter(c -> pattern.matcher(c.name()).matches()).count() == members;
    }

    private static ColumnInfo first(List<ColumnInfo> columns, Kind kind, Set<String> skip) {
        for (ColumnInfo column : columns) {
            if (!skip.contains(column.name()) && kind(column) == kind) {
                return column;
            }
        }
        return null;
    }

    /** The text column most likely to hold free text: one with such a name, else the last text column. */
    private static ColumnInfo searched(List<ColumnInfo> columns, Set<String> skip) {
        ColumnInfo last = null;
        for (ColumnInfo column : columns) {
            if (skip.contains(column.name()) || kind(column) != Kind.TEXT) {
                continue;
            }
            if (TEXTY.contains(column.name().toLowerCase(Locale.ROOT))) {
                return column;
            }
            last = column;
        }
        return last;
    }

    private static ColumnInfo shared(List<ColumnInfo> mine, List<ColumnInfo> theirs, Set<String> skip) {
        for (ColumnInfo column : mine) {
            if (skip.contains(column.name()) || kind(column) == Kind.OTHER) {
                continue;
            }
            for (ColumnInfo other : theirs) {
                if (other.name().equalsIgnoreCase(column.name()) && kind(other) == kind(column)) {
                    return column;
                }
            }
        }
        return null;
    }

    private static String plain(String name) {
        String plain = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return plain.isEmpty() ? "value" : plain;
    }

    private static Kind kind(ColumnInfo column) {
        String type = column.sqlType() == null ? "" : column.sqlType().toUpperCase(Locale.ROOT);
        int bracket = type.indexOf('(');
        if (bracket > 0) {
            type = type.substring(0, bracket);
        }
        switch (type.trim()) {
            case "TINYINT": case "SMALLINT": case "INTEGER": case "BIGINT": case "HUGEINT":
            case "UTINYINT": case "USMALLINT": case "UINTEGER": case "UBIGINT": case "UHUGEINT":
            case "FLOAT": case "DOUBLE": case "DECIMAL":
                return Kind.NUMBER;
            case "TIMESTAMP": case "TIMESTAMP WITH TIME ZONE": case "TIMESTAMP_S": case "TIMESTAMP_MS":
            case "TIMESTAMP_NS":
                return Kind.TIME;
            case "DATE":
                return Kind.DATE;
            case "VARCHAR":
                return Kind.TEXT;
            default:
                return Kind.OTHER;
        }
    }
}
