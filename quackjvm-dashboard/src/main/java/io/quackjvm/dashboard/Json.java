package io.quackjvm.dashboard;

import java.util.Collection;
import java.util.Map;

/** Just enough JSON writing for the dashboard's one endpoint, so the module needs no library. */
final class Json {

    private final StringBuilder out = new StringBuilder(8192);
    private boolean needsComma;

    Json object() {
        separate();
        out.append('{');
        needsComma = false;
        return this;
    }

    Json end() {
        out.append('}');
        needsComma = true;
        return this;
    }

    Json array() {
        separate();
        out.append('[');
        needsComma = false;
        return this;
    }

    Json endArray() {
        out.append(']');
        needsComma = true;
        return this;
    }

    Json key(String key) {
        separate();
        string(key);
        out.append(':');
        needsComma = false;
        return this;
    }

    Json value(String value) {
        separate();
        if (value == null) {
            out.append("null");
        }
        else {
            string(value);
        }
        needsComma = true;
        return this;
    }

    Json value(double value) {
        separate();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append("null");
        }
        else if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            out.append((long) value);
        }
        else {
            out.append(String.format(java.util.Locale.ROOT, "%.4g", value));
        }
        needsComma = true;
        return this;
    }

    Json value(boolean value) {
        separate();
        out.append(value);
        needsComma = true;
        return this;
    }

    Json field(String key, String value) {
        return key(key).value(value);
    }

    Json field(String key, double value) {
        return key(key).value(value);
    }

    Json field(String key, boolean value) {
        return key(key).value(value);
    }

    Json numbers(String key, Collection<? extends Number> values) {
        key(key).array();
        for (Number number : values) {
            value(number == null ? Double.NaN : number.doubleValue());
        }
        return endArray();
    }

    Json numbers(String key, Map<String, Double> values) {
        key(key).object();
        values.forEach(this::field);
        return end();
    }

    private void separate() {
        if (needsComma) {
            out.append(',');
            needsComma = false;
        }
    }

    private void string(String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // Never let a statement's text close a <script> or start a comment if this is
                // ever embedded in a page.
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
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

    @Override
    public String toString() {
        return out.toString();
    }
}
