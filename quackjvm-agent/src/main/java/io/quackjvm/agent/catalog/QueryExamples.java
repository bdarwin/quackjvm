package io.quackjvm.agent.catalog;

import io.quackjvm.agent.catalog.internal.SqlNames;

import java.util.ArrayList;
import java.util.HashSet;
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
 * <p>Which ones come back depends on the columns' kinds: a count, the first rows and a summary of
 * every column always; the range, percentiles and highest rows of a number; counts by a group column;
 * rows over time for a date or timestamp; a text search. A family of numbered columns - {@code
 * cpu_0001} to {@code cpu_1500} - is queried all at once with {@code COLUMNS('^cpu_[0-9]+$')} rather
 * than column by column, and left out of the others. A table with a {@code level} and a {@code
 * message} is a log, and gets examples for logs. When another table has a column of the same name and
 * kind, there is a join over it.</p>
 *
 * <p>The group column is a text column with few values. Told how many distinct values each column
 * holds - from {@link Profile#distinctValues()}, or counted by the application - the examples choose
 * one that has few; told nothing, they go by its name ({@code level}, {@code host}, {@code status}
 * ...).</p>
 */
public final class QueryExamples {

    /** One query, and what it shows. */
    public record Example(String title, String sql) {
    }

    /** At least this many numbered columns of the same name make a family. */
    static final int FAMILY = 3;

    /** Wider than this, the first rows show only the first columns. */
    static final int WIDE = 20;

    /** A group column has at most this many distinct values, when the counts are known. */
    static final long GROUPS = 1_000;

    private static final int JOINS = 3;

    private static final int SHOWN = 10;

    private static final Pattern NUMBERED = Pattern.compile("(.*?[^0-9])([0-9]+)");

    /** Names a text column to search more likely holds free text under. */
    private static final Set<String> TEXTY = Set.of("message", "msg", "text", "line", "body", "description",
            "comment", "content", "detail", "details", "summary", "title");

    /** Names a text column with few values is likely to have, in the order they are preferred. */
    private static final List<String> GROUPY = List.of("level", "severity", "host", "hostname", "status", "state",
            "kind", "type", "category", "service", "source", "region", "zone", "logger", "component", "thread",
            "method", "mode", "unit", "group");

    /** Names of a number that counts rows rather than measures anything. */
    private static final Set<String> COUNTERS = Set.of("line", "lineno", "line_number", "id", "row", "rowid",
            "row_number", "seq", "sequence", "index", "idx", "n", "num", "number", "offset", "position");

    private static final String ERROR = "upper(level) = 'ERROR'";

    private static final String WARNING = "upper(level) IN ('WARN', 'WARNING')";

    /** A time in milliseconds written in a message, and the same with the number captured. */
    private static final String DURATION = "[0-9]+(\\.[0-9]+)? ?ms\\b";

    private static final String DURATION_NUMBER = "([0-9]+(\\.[0-9]+)?) ?ms\\b";

    private enum Kind { NUMBER, TIME, DATE, TEXT, OTHER }

    private QueryExamples() {
    }

    /** The examples for a table; joins are offered to the others that share a column with it. */
    public static List<Example> of(TableInfo table, List<TableInfo> others) {
        return of(table, others, Map.of());
    }

    /**
     * As {@link #of(TableInfo, List)}, told roughly how many distinct values the columns hold - by
     * name, any case; a column left out is judged by its name.
     */
    public static List<Example> of(TableInfo table, List<TableInfo> others, Map<String, Long> distinctValues) {
        return new Writer(table, distinctValues).write(others);
    }

    private static final class Writer {

        private final TableInfo table;
        private final String from;
        private final List<ColumnInfo> columns;
        private final Map<String, Long> distinct = new LinkedHashMap<>();
        private final Map<String, List<ColumnInfo>> families;
        private final Set<String> inFamily = new HashSet<>();
        private final List<Example> examples = new ArrayList<>();

        private final ColumnInfo measure;
        private final ColumnInfo time;
        private final ColumnInfo group;
        private final ColumnInfo searched;
        private final Log log;

        Writer(TableInfo table, Map<String, Long> distinctValues) {
            this.table = table;
            this.from = SqlNames.write(table.name());
            this.columns = table.columns();
            distinctValues.forEach((name, count) -> {
                if (name != null && count != null) {
                    distinct.put(name.toLowerCase(Locale.ROOT), count);
                }
            });
            this.families = families(columns);
            for (List<ColumnInfo> family : families.values()) {
                for (ColumnInfo column : family) {
                    inFamily.add(column.name());
                }
            }
            this.measure = measure();
            ColumnInfo when = first(Kind.TIME);
            this.time = when != null ? when : first(Kind.DATE);
            this.log = Log.of(this);
            this.group = group();
            this.searched = searched();
        }

        List<Example> write(List<TableInfo> others) {
            add("How many rows", "SELECT count(*) AS rows FROM " + from);
            if (columns.size() > WIDE) {
                String shown = firstColumns();
                add("The first rows, first " + SHOWN + " of " + columns.size() + " columns",
                        "SELECT " + shown + " FROM " + from + " LIMIT 10");
                add("A summary of the first " + SHOWN + " columns: range, distinct values, nulls",
                        "SUMMARIZE SELECT " + shown + " FROM " + from);
                add("Columns whose name has a word in it", "SELECT column_name, column_type FROM (DESCRIBE " + from
                        + ") WHERE column_name ILIKE " + text("%" + nameFragment() + "%"));
            }
            else {
                add("The first rows", "SELECT * FROM " + from + " LIMIT 10");
                add("A summary of every column: range, distinct values, nulls", "SUMMARIZE " + from);
            }
            if (measure != null) {
                // a number that counts rows (line, id...) has no range worth showing
                String m = SqlNames.write(measure.name());
                add("The range of " + measure.name(),
                        "SELECT min(" + m + ") AS lowest, max(" + m + ") AS highest, avg(" + m + ") AS mean FROM " + from);
                add("The median and percentiles of " + measure.name(), "SELECT quantile_cont(" + m + ", 0.5) AS median,"
                        + " quantile_cont(" + m + ", 0.95) AS p95, quantile_cont(" + m + ", 0.99) AS p99 FROM " + from);
                add("The rows with the highest " + measure.name(),
                        "SELECT * FROM " + from + " ORDER BY " + m + " DESC NULLS LAST LIMIT 10");
            }
            if (group != null && (log == null || group != log.level)) {
                String g = SqlNames.write(group.name());
                String also = "";
                if (measure != null) {
                    String m = SqlNames.write(measure.name());
                    String plain = plain(measure.name());
                    also = ", avg(" + m + ") AS mean_" + plain + ", max(" + m + ") AS highest_" + plain;
                }
                add("Rows for each " + group.name(), "SELECT " + g + ", count(*) AS rows" + also + " FROM " + from
                        + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20");
            }
            if (time != null) {
                String unit = kind(time) == Kind.DATE ? "month" : "hour";
                String t = SqlNames.write(time.name());
                String mean = measure == null ? "" : ", avg(" + SqlNames.write(measure.name()) + ") AS mean_"
                        + plain(measure.name());
                // rows without a time would be a group of their own, first or last: they're left out
                add("Rows each " + unit + " by " + time.name() + (measure == null ? "" : ", with the mean "
                        + measure.name()), "SELECT date_trunc('" + unit + "', " + t + ") AS " + unit + ", count(*) AS rows"
                        + mean + " FROM " + from + " WHERE " + t + " IS NOT NULL GROUP BY 1 ORDER BY 1");
            }
            if (searched != null) {
                add("Rows whose " + searched.name() + " mentions a word", "SELECT * FROM " + from + " WHERE "
                        + SqlNames.write(searched.name()) + " ILIKE '%error%' LIMIT 50");
            }
            if (log != null) {
                log.write();
            }
            for (Map.Entry<String, List<ColumnInfo>> family : families.entrySet()) {
                family(family.getKey(), family.getValue());
            }
            int joins = 0;
            for (TableInfo other : others) {
                if (joins == JOINS || other.name().equalsIgnoreCase(table.name())) {
                    continue;
                }
                ColumnInfo shared = shared(other.columns());
                if (shared != null) {
                    String them = SqlNames.write(other.name());
                    add("Joined to " + other.name() + " by " + shared.name(), "SELECT * FROM " + from + " JOIN " + them
                            + " USING (" + SqlNames.write(shared.name()) + ") LIMIT 10");
                    add("The first rows of " + other.name(), "SELECT * FROM " + them + " LIMIT 10");
                    joins++;
                }
            }
            return List.copyOf(examples);
        }

        private void family(String prefix, List<ColumnInfo> members) {
            String all = "COLUMNS(" + text("^" + SqlNames.regexLiteral(prefix) + "[0-9]+$") + ")";
            String named = members.size() + " " + prefix + "N columns";
            if (kind(members.get(0)) != Kind.NUMBER) {
                add("The " + named, "SELECT " + all + " FROM " + from + " LIMIT 10");
                if (sameType(members)) {
                    add("The " + named + " as rows", unpivot(all));
                }
                return;
            }
            add("The mean of each of " + named, "SELECT avg(" + all + ") FROM " + from);
            if (group != null) {
                String g = SqlNames.write(group.name());
                add("The mean of each of " + named + " for each " + group.name(),
                        "SELECT " + g + ", avg(" + all + ") FROM " + from + " GROUP BY 1 ORDER BY 1 LIMIT 20");
            }
            if (time != null && kind(time) == Kind.TIME) {
                String t = SqlNames.write(time.name());
                add("The mean of each of " + named + " each minute", "SELECT date_trunc('minute', " + t
                        + ") AS minute, avg(" + all + ") FROM " + from + " WHERE " + t + " IS NOT NULL GROUP BY 1 ORDER BY 1");
            }
            String lead = group != null ? SqlNames.write(group.name()) + ", "
                    : time != null ? SqlNames.write(time.name()) + ", " : "";
            add("The total of the " + named + " in each row", "SELECT " + lead + "list_sum([*" + all + "]) AS "
                    + plain(prefix) + "_total FROM " + from + " LIMIT 10");
            add("The rows with the highest value in any of the " + named, "SELECT " + lead + "greatest(*" + all
                    + ") AS highest FROM " + from + " ORDER BY highest DESC NULLS LAST LIMIT 10");
            if (sameType(members)) {
                // turned into rows and summed up per column: which of them run highest
                add("Each of the " + named + ": its mean and highest, highest mean first", "SELECT series, avg(value)"
                        + " AS mean, max(value) AS highest FROM (UNPIVOT (SELECT " + all + " FROM " + from
                        + ") ON COLUMNS(*) INTO NAME series VALUE value) GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 20");
            }
        }

        private String unpivot(String all) {
            return "SELECT * FROM (UNPIVOT " + from + " ON " + all + " INTO NAME series VALUE value) LIMIT 20";
        }

        // ---------- choosing columns ----------

        private ColumnInfo first(Kind kind) {
            for (ColumnInfo column : columns) {
                if (!inFamily.contains(column.name()) && kind(column) == kind) {
                    return column;
                }
            }
            return null;
        }

        /** A number that measures something: the first one not named like a counter of rows. */
        private ColumnInfo measure() {
            for (ColumnInfo column : columns) {
                if (!inFamily.contains(column.name()) && kind(column) == Kind.NUMBER && !isCounter(column.name())) {
                    return column;
                }
            }
            return null;
        }

        /**
         * A text column with few values: with counts, the first with at most {@link #GROUPS}; without,
         * the first named like one, else the first not named like free text. A column with a count
         * is judged by it.
         */
        private ColumnInfo group() {
            ColumnInfo fallback = null;
            ColumnInfo byName = null;
            int rank = Integer.MAX_VALUE;
            for (ColumnInfo column : columns) {
                if (inFamily.contains(column.name()) || kind(column) != Kind.TEXT) {
                    continue;
                }
                String lower = column.name().toLowerCase(Locale.ROOT);
                Long count = distinct.get(lower);
                if (count != null) {
                    if (count >= 2 && count <= GROUPS) {
                        return column;
                    }
                    continue;
                }
                int at = GROUPY.indexOf(lower);
                if (at >= 0 && at < rank) {
                    byName = column;
                    rank = at;
                }
                if (fallback == null && !TEXTY.contains(lower)) {
                    fallback = column;
                }
            }
            if (!distinct.isEmpty() && byName == null) {
                return null;
            }
            return byName != null ? byName : fallback;
        }

        /** The text column most likely to hold free text: one with such a name, else the last text column. */
        private ColumnInfo searched() {
            ColumnInfo last = null;
            for (ColumnInfo column : columns) {
                if (inFamily.contains(column.name()) || kind(column) != Kind.TEXT) {
                    continue;
                }
                if (TEXTY.contains(column.name().toLowerCase(Locale.ROOT))) {
                    return column;
                }
                last = column;
            }
            return last;
        }

        private ColumnInfo shared(List<ColumnInfo> theirs) {
            for (ColumnInfo column : columns) {
                if (inFamily.contains(column.name()) || kind(column) == Kind.OTHER) {
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

        ColumnInfo named(String name, Kind... kinds) {
            for (ColumnInfo column : columns) {
                if (column.name().equalsIgnoreCase(name)) {
                    for (Kind kind : kinds) {
                        if (kind(column) == kind) {
                            return column;
                        }
                    }
                }
            }
            return null;
        }

        private String firstColumns() {
            List<String> names = new ArrayList<>();
            for (ColumnInfo column : columns.subList(0, SHOWN)) {
                names.add(SqlNames.write(column.name()));
            }
            return String.join(", ", names);
        }

        /** A word to look for in the column names: a family's name, else a column's past the first ones shown. */
        private String nameFragment() {
            for (String prefix : families.keySet()) {
                String word = prefix.replaceAll("[^A-Za-z0-9]+$", "");
                if (!word.isEmpty()) {
                    return word;
                }
            }
            return columns.get(SHOWN).name();
        }

        void add(String title, String sql) {
            examples.add(new Example(title, sql));
        }
    }

    /** The examples a log gets: a table with a text {@code level} and {@code message}. */
    private static final class Log {

        private final Writer writer;
        private final ColumnInfo level;
        private final ColumnInfo message;
        private final ColumnInfo time;
        private final ColumnInfo logger;
        private final ColumnInfo line;

        private Log(Writer writer, ColumnInfo level, ColumnInfo message) {
            this.writer = writer;
            this.level = level;
            this.message = message;
            this.time = writer.named("time", Kind.TIME) != null ? writer.named("time", Kind.TIME) : writer.time;
            this.logger = writer.named("logger", Kind.TEXT);
            this.line = writer.named("line", Kind.NUMBER);
        }

        static Log of(Writer writer) {
            ColumnInfo level = writer.named("level", Kind.TEXT);
            ColumnInfo message = writer.named("message", Kind.TEXT);
            return level == null || message == null ? null : new Log(writer, level, message);
        }

        void write() {
            String from = writer.from;
            String lv = SqlNames.write(level.name());
            String msg = SqlNames.write(message.name());
            String error = ERROR.replace("level", lv);
            // lines with no level (a stack trace's) aren't a level of their own
            writer.add("Lines per level", "SELECT " + lv + ", count(*) AS lines FROM " + from + " WHERE " + lv
                    + " IS NOT NULL GROUP BY 1 ORDER BY 2 DESC");
            if (time != null && kind(time) == Kind.TIME) {
                writer.add("Errors per minute", "SELECT date_trunc('minute', " + SqlNames.write(time.name())
                        + ") AS minute, count(*) AS errors FROM " + from + " WHERE " + error + " GROUP BY 1 ORDER BY 1");
            }
            writer.add("The most frequent errors, numbers blanked", "SELECT regexp_replace(" + msg
                    + ", '[0-9]+', '#', 'g') AS error, count(*) AS times FROM " + from + " WHERE " + error
                    + " GROUP BY 1 ORDER BY 2 DESC LIMIT 20");
            if (logger != null) {
                writer.add("Errors and warnings by " + logger.name(), "SELECT " + SqlNames.write(logger.name())
                        + ", count(*) FILTER (WHERE " + error + ") AS errors, count(*) FILTER (WHERE "
                        + WARNING.replace("level", lv) + ") AS warnings FROM " + from + " WHERE " + error + " OR "
                        + WARNING.replace("level", lv) + " GROUP BY 1 ORDER BY 2 DESC, 3 DESC LIMIT 20");
            }
            StringBuilder lead = new StringBuilder();
            for (ColumnInfo column : new ColumnInfo[] {line, time}) {
                if (column != null) {
                    lead.append(SqlNames.write(column.name())).append(", ");
                }
            }
            writer.add("Durations in the messages (\"... 345 ms\"), slowest first", "SELECT " + lead + msg
                    + ", CAST(regexp_extract(" + msg + ", '" + DURATION_NUMBER + "', 1) AS DOUBLE)"
                    + " AS ms FROM " + from + " WHERE regexp_matches(" + msg + ", '" + DURATION + "')"
                    + " ORDER BY ms DESC LIMIT 20");
        }
    }

    // ---------- the columns' kinds and names ----------

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

    private static boolean sameType(List<ColumnInfo> members) {
        String type = members.get(0).sqlType();
        return members.stream().allMatch(c -> c.sqlType() != null && c.sqlType().equalsIgnoreCase(type));
    }

    private static boolean isCounter(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return COUNTERS.contains(lower) || lower.endsWith("_id") || name.matches(".*[a-z]Id");
    }

    /** A SQL string literal. */
    private static String text(String value) {
        return "'" + value.replace("'", "''") + "'";
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
