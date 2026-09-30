package io.quackjvm.core.guard.internal;

import java.util.Locale;

/**
 * The little bit of reading text that the parser cannot be asked to do.
 *
 * <p>Used for two things only: finding the leading keyword of a statement, and - for the one
 * statement kind DuckDB will not serialize - deciding whether there is more than one statement.
 * Everything else is left to {@link ParserGate}, because a scanner is a worse parser than the
 * parser.</p>
 *
 * <p>It knows what a quote is: single-quoted strings with doubled quotes inside, double-quoted
 * identifiers, dollar-quoted strings with their tags, line comments and block comments, which nest
 * in DuckDB. A semicolon inside any of those is not a statement separator.</p>
 */
public final class SqlScanner {

    private SqlScanner() {
    }

    /** Whether a statement separator appears outside quotes and comments. */
    public static boolean hasStatementSeparator(String sql) {
        int end = scanTo(sql, ';');
        if (end < 0) {
            return false;
        }
        // A semicolon at the very end, with only whitespace and comments after it, is a terminator
        // rather than a separator.
        return !isBlankTail(sql.substring(end + 1));
    }

    /** The first word of the statement, upper-cased, skipping whitespace and leading comments. */
    public static String leadingKeyword(String sql) {
        int i = skipBlanks(sql, 0);
        int start = i;
        while (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    /** What follows the leading keyword, with the keyword removed. */
    public static String afterLeadingKeyword(String sql) {
        int i = skipBlanks(sql, 0);
        while (i < sql.length() && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return sql.substring(i).trim();
    }

    /** Whether what is left is only whitespace and comments. */
    public static boolean isBlankTail(String sql) {
        return skipBlanks(sql, 0) >= sql.length();
    }

    /** The position of the first occurrence of a character outside quotes and comments, or -1. */
    private static int scanTo(String sql, char looking) {
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == looking) {
                return i;
            }
            int skipped = skipOne(sql, i);
            i = skipped > i ? skipped : i + 1;
        }
        return -1;
    }

    /** Whitespace and comments from this position on. */
    private static int skipBlanks(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int afterComment = skipComment(sql, i);
            if (afterComment == i) {
                return i;
            }
            i = afterComment;
        }
        return i;
    }

    /** If a quoted run or a comment starts here, the position after it; otherwise where we are. */
    private static int skipOne(String sql, int at) {
        int afterComment = skipComment(sql, at);
        if (afterComment != at) {
            return afterComment;
        }
        char c = sql.charAt(at);
        if (c == '\'') {
            return skipQuoted(sql, at, '\'');
        }
        if (c == '"') {
            return skipQuoted(sql, at, '"');
        }
        if (c == '$') {
            return skipDollarQuoted(sql, at);
        }
        return at;
    }

    private static int skipComment(String sql, int at) {
        if (sql.startsWith("--", at)) {
            int newline = sql.indexOf('\n', at);
            return newline < 0 ? sql.length() : newline + 1;
        }
        if (sql.startsWith("/*", at)) {
            // DuckDB's block comments nest, so count them.
            int depth = 0;
            int i = at;
            while (i < sql.length()) {
                if (sql.startsWith("/*", i)) {
                    depth++;
                    i += 2;
                }
                else if (sql.startsWith("*/", i)) {
                    depth--;
                    i += 2;
                    if (depth == 0) {
                        return i;
                    }
                }
                else {
                    i++;
                }
            }
            return sql.length();
        }
        return at;
    }

    private static int skipQuoted(String sql, int at, char quote) {
        int i = at + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                // Two in a row is an escaped quote, not the end.
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    /** {@code $tag$ ... $tag$}, where the tag may be empty. */
    private static int skipDollarQuoted(String sql, int at) {
        int close = sql.indexOf('$', at + 1);
        if (close < 0) {
            return at;
        }
        String tag = sql.substring(at, close + 1);
        for (int i = 1; i < tag.length() - 1; i++) {
            char c = tag.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                // Not a dollar-quote tag after all, just a dollar sign.
                return at;
            }
        }
        int end = sql.indexOf(tag, close + 1);
        return end < 0 ? sql.length() : end + tag.length();
    }
}
