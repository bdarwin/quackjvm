package io.quackjvm.core.sparse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * One record read back from a {@link SparseTable}: the data points it has, and nothing for the ones
 * it does not. Ordered by name.
 */
public final class SparseRecord {

    private final long recordId;
    private final String[] names;
    private final double[] values;

    SparseRecord(long recordId, String[] names, double[] values) {
        this.recordId = recordId;
        this.names = names;
        this.values = values;
    }

    public long recordId() {
        return recordId;
    }

    /** How many data points this record has. Zero for a record the table does not hold. */
    public int size() {
        return values.length;
    }

    public boolean isEmpty() {
        return values.length == 0;
    }

    public String name(int i) {
        return names[i];
    }

    public double value(int i) {
        return values[i];
    }

    /** The value of one data point, or empty if this record does not have it. */
    public OptionalDouble get(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return OptionalDouble.of(values[i]);
            }
        }
        return OptionalDouble.empty();
    }

    /** A copy as a map - convenient, and exactly the per-value boxing the rest of this avoids. */
    public Map<String, Double> toMap() {
        Map<String, Double> map = new LinkedHashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            map.put(names[i], values[i]);
        }
        return map;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SparseRecord[").append(recordId).append(": ");
        for (int i = 0; i < names.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(names[i]).append('=').append(values[i]);
        }
        return sb.append(']').toString();
    }
}
