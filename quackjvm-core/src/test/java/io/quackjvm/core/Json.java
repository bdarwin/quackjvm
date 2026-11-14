package io.quackjvm.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the test catalog: objects as maps, arrays as lists, whole numbers as longs. */
final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw new IllegalArgumentException("Trailing text at " + json.at + ": " + text);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        return (Map<String, Object>) parse(text);
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        }
        else if (value instanceof String s) {
            out.append('"');
            for (char c : s.toCharArray()) {
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
            out.append('"');
        }
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(first ? "" : ",");
                first = false;
                write(out, entry.getKey().toString());
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        }
        else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ",");
                write(out, list.get(i));
            }
            out.append(']');
        }
        else {
            out.append(value);
        }
    }

    private Object value() {
        space();
        char c = text.charAt(at);
        switch (c) {
            case '{' -> {
                at++;
                Map<String, Object> map = new LinkedHashMap<>();
                space();
                if (text.charAt(at) == '}') {
                    at++;
                    return map;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value());
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }
            case '[' -> {
                at++;
                List<Object> list = new ArrayList<>();
                space();
                if (text.charAt(at) == ']') {
                    at++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    space();
                    if (text.charAt(at) == ',') {
                        at++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }
            case '"' -> {
                return string();
            }
            case 't' -> {
                at += 4;
                return true;
            }
            case 'f' -> {
                at += 5;
                return false;
            }
            case 'n' -> {
                at += 4;
                return null;
            }
            default -> {
                int start = at;
                while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                    at++;
                }
                String number = text.substring(start, at);
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char e = text.charAt(at++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(e);
                }
            }
            else {
                out.append(c);
            }
        }
    }

    private void expect(char c) {
        if (text.charAt(at) != c) {
            throw new IllegalArgumentException("Expected " + c + " at " + at + ": " + text);
        }
        at++;
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}
