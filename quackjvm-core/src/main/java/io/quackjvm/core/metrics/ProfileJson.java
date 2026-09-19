package io.quackjvm.core.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON that DuckDB's profiler produces into maps, lists, strings, doubles and booleans.
 * Just enough JSON for that - the core module takes no JSON library.
 */
final class ProfileJson {

    private final String text;
    private int at;

    private ProfileJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        ProfileJson reader = new ProfileJson(text);
        Object value = reader.value();
        reader.whitespace();
        if (reader.at != text.length()) {
            throw reader.error("trailing characters");
        }
        return value;
    }

    private Object value() {
        whitespace();
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        at++;
        whitespace();
        if (peek() == '}') {
            at++;
            return map;
        }
        while (true) {
            whitespace();
            String key = string();
            whitespace();
            expect(':');
            map.put(key, value());
            whitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        at++;
        whitespace();
        if (peek() == ']') {
            at++;
            return list;
        }
        while (true) {
            list.add(value());
            whitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = next();
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (at + 4 > text.length()) {
                        throw error("short \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> out.append(escaped);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character");
        }
        return Double.valueOf(text.substring(start, at));
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("expected " + word);
        }
        at += word.length();
        return value;
    }

    private void whitespace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private char next() {
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(at++);
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("expected " + c);
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Unreadable profile JSON at " + at + ": " + what);
    }
}
