package io.quackjvm.agent.catalog.internal;

import io.quackjvm.core.duckdb.Sql;

import java.util.Set;
import java.util.regex.Pattern;

/** Which names SQL can write without quotes, and writing the others with them. */
public final class SqlNames {

    /**
     * DuckDB's reserved words and the ones that cannot name a table without quotes - checked against
     * {@code duckdb_keywords()} by a test, so a new DuckDB that adds one is noticed.
     */
    public static final Set<String> KEYWORDS = Set.of(("all analyse analyze and any array as asc asymmetric both case"
            + " cast check collate column constraint create default deferrable desc describe distinct do else end"
            + " except false fetch for foreign from group having in initially intersect into lambda lateral leading"
            + " limit not null offset on only or order pivot pivot_longer pivot_wider placing primary qualify"
            + " references returning select show some summarize symmetric table then to trailing true union unique"
            + " unpivot using variadic when where window with"
            + " anti asof at authorization binary by collation columns concurrently cross freeze full generated"
            + " glob ilike inner is isnull join left like map natural notnull outer overlaps positional right semi"
            + " similar struct tablesample try_cast tuple unpack verbose").split(" "));

    private static final Pattern PLAIN = Pattern.compile("[a-z_][a-z0-9_]*");

    private SqlNames() {
    }

    /** Whether the name can be written as it is: lower case letters, digits and {@code _}, and no keyword. */
    public static boolean isPlain(String name) {
        return PLAIN.matcher(name).matches() && !KEYWORDS.contains(name);
    }

    /** The name as SQL should write it: as it is when plain, in double quotes otherwise. */
    public static String write(String name) {
        return isPlain(name) ? name : Sql.quote(name);
    }

    /** A regular expression that matches exactly this text, for {@code COLUMNS('...')}. */
    public static String regexLiteral(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

}
