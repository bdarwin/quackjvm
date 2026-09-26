package io.quackjvm.core.measure;

import io.quackjvm.core.json.JsonReader;

import java.util.List;
import java.util.Map;

/** Reading a measure's definition out of the JSON that {@code MeasureTable} writes into a file. */
final class MeasureJson {

    private MeasureJson() {
    }

    static Map<String, Object> parseObject(String json) {
        Object parsed = JsonReader.parse(json);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("A measure definition should be a JSON object: " + json);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) parsed;
        return object;
    }

    /** The value as a map, or an empty one when it is absent. */
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** The value as a list, or an empty one when it is absent. */
    static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
