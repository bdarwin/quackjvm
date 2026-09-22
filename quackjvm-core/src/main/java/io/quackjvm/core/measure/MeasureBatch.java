package io.quackjvm.core.measure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Values on their way into a {@link MeasureTable}: each belongs to a record, and is identified by
 * one value per field of the table's key.
 *
 * <pre>
 * MeasureBatch batch = table.batch()
 *         .record(42)
 *             .put(1.25, "x", "y", "z", "5y", "U1")
 *             .put(0.97, "x", "y", "z", "5y", "U2")
 *         .record(43)
 *             .put(3.10, "x", "y", "z", "5y", "U1")
 *         .build();
 * </pre>
 *
 * <p>Held as flat arrays, with each distinct key kept once for the whole batch: an {@code int} per
 * value saying which key, and a {@code double} saying what it is. A map of key parts per value would
 * cost about ten times that.</p>
 */
public final class MeasureBatch {

    private final List<String> fields;
    private final long[] recordIds;
    /** Values of record {@code i} are at positions {@code [offsets[i], offsets[i + 1])}. */
    private final int[] offsets;
    /** The distinct keys of this batch, each one value per field, laid end to end. */
    private final String[] keyParts;
    private final int[] keyOfValue;
    private final double[] values;

    private MeasureBatch(List<String> fields, long[] recordIds, int[] offsets, String[] keyParts,
                         int[] keyOfValue, double[] values) {
        this.fields = fields;
        this.recordIds = recordIds;
        this.offsets = offsets;
        this.keyParts = keyParts;
        this.keyOfValue = keyOfValue;
        this.values = values;
    }

    public List<String> getFields() {
        return fields;
    }

    public int recordCount() {
        return recordIds.length;
    }

    public int valueCount() {
        return values.length;
    }

    /** How many distinct keys this batch uses - what has to be looked up in the dictionary. */
    public int keyCount() {
        return keyParts.length / fields.size();
    }

    public long recordId(int record) {
        return recordIds[record];
    }

    long[] recordIds() {
        return recordIds;
    }

    int valueStart(int record) {
        return offsets[record];
    }

    int valueEnd(int record) {
        return offsets[record + 1];
    }

    int keyOfValue(int position) {
        return keyOfValue[position];
    }

    public double value(int position) {
        return values[position];
    }

    /** One part of one of the batch's distinct keys. */
    String keyPart(int key, int field) {
        return keyParts[key * fields.size() + field];
    }

    public static final class Builder {

        private final List<String> fields;
        private long[] recordIds = new long[16];
        private int[] offsets = new int[17];
        private int records;

        private int[] keyOfValue = new int[64];
        private double[] values = new double[64];
        private int count;

        private final Map<List<String>, Integer> keyIndexes = new HashMap<>();
        private final List<String> keyParts = new ArrayList<>();
        /** Per distinct key, the last record it was put into - to reject a repeat within one. */
        private int[] lastRecordOfKey = new int[16];

        Builder(List<String> fields) {
            this.fields = fields;
        }

        /** Starts a record. Values put after this belong to it, until the next {@code record}. */
        public Builder record(long recordId) {
            if (records == recordIds.length) {
                recordIds = Arrays.copyOf(recordIds, records * 2);
                offsets = Arrays.copyOf(offsets, records * 2 + 1);
            }
            recordIds[records] = recordId;
            offsets[records] = count;
            records++;
            offsets[records] = count;
            return this;
        }

        /**
         * Adds one value, with one key part per field of the table, in the order the fields were
         * declared.
         */
        public Builder put(double value, String... key) {
            if (records == 0) {
                throw new IllegalStateException("Call record(id) before put(...)");
            }
            if (key.length != fields.size()) {
                throw new IllegalArgumentException("This measure's key is " + fields + ", so put() takes "
                        + fields.size() + " parts, not " + key.length + ": " + Arrays.toString(key));
            }
            List<String> parts = List.of(key);
            for (int i = 0; i < key.length; i++) {
                if (key[i] == null || key[i].isEmpty()) {
                    throw new IllegalArgumentException("Key part '" + fields.get(i) + "' is missing: "
                            + Arrays.toString(key));
                }
            }
            Integer index = keyIndexes.get(parts);
            if (index == null) {
                index = keyIndexes.size();
                keyIndexes.put(parts, index);
                keyParts.addAll(parts);
                if (index == lastRecordOfKey.length) {
                    lastRecordOfKey = Arrays.copyOf(lastRecordOfKey, index * 2);
                }
                lastRecordOfKey[index] = -1;
            }
            if (lastRecordOfKey[index] == records) {
                throw new IllegalArgumentException("Record " + recordIds[records - 1] + " already has a value for "
                        + parts);
            }
            lastRecordOfKey[index] = records;
            if (count == values.length) {
                keyOfValue = Arrays.copyOf(keyOfValue, count * 2);
                values = Arrays.copyOf(values, count * 2);
            }
            keyOfValue[count] = index;
            values[count] = value;
            count++;
            offsets[records] = count;
            return this;
        }

        /**
         * @throws IllegalArgumentException if a record id appears twice - its values would be two
         *                                  partial copies of one record, and a replace could not
         *                                  say which is meant
         */
        public MeasureBatch build() {
            long[] ids = Arrays.copyOf(recordIds, records);
            long[] sorted = ids.clone();
            Arrays.sort(sorted);
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] == sorted[i - 1]) {
                    throw new IllegalArgumentException("Record " + sorted[i] + " appears twice in one batch");
                }
            }
            return new MeasureBatch(fields, ids, Arrays.copyOf(offsets, records + 1),
                    keyParts.toArray(new String[0]), Arrays.copyOf(keyOfValue, count), Arrays.copyOf(values, count));
        }
    }
}
