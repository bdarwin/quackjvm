package io.quackjvm.core.sparse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A batch of sparse records on its way to a {@link SparseTable}: each record carries a few of a
 * large, open-ended set of numeric data points.
 *
 * <p>Held as flat primitive arrays rather than a map per record. A {@code Map<String, Double>} per
 * record costs about 68 bytes a value on the heap even when the name strings are shared - an entry,
 * a boxed double, a table slot - which at a million records of a hundred values is 6.4 GB. This is
 * about 12 bytes a value: an {@code int} saying which name, a {@code double} saying what value, and
 * each distinct name held once for the whole batch.</p>
 *
 * <pre>
 * SparseBatch batch = SparseBatch.builder()
 *         .record(1).put("temperature", 21.5).put("pressure", 101.3)
 *         .record(2).put("temperature", 22.1).put("humidity", 40.0)
 *         .record(3)                      // no values: with replace(), removes record 3
 *         .build();
 * </pre>
 *
 * <p>Immutable once built.</p>
 */
public final class SparseBatch {

    private final long[] recordIds;
    /** Values of record {@code i} are at positions {@code [offsets[i], offsets[i + 1])}. */
    private final int[] offsets;
    /** The distinct data-point names this batch uses, each once. */
    private final String[] names;
    /** Per value, an index into {@link #names}. */
    private final int[] nameIndexes;
    private final double[] values;

    private SparseBatch(long[] recordIds, int[] offsets, String[] names, int[] nameIndexes, double[] values) {
        this.recordIds = recordIds;
        this.offsets = offsets;
        this.names = names;
        this.nameIndexes = nameIndexes;
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int recordCount() {
        return recordIds.length;
    }

    public int valueCount() {
        return values.length;
    }

    public long recordId(int record) {
        return recordIds[record];
    }

    /** Position of the first value of this record; values run up to {@code valueEnd(record)}. */
    public int valueStart(int record) {
        return offsets[record];
    }

    public int valueEnd(int record) {
        return offsets[record + 1];
    }

    /** The data-point name of the value at this position. */
    public String name(int position) {
        return names[nameIndexes[position]];
    }

    public double value(int position) {
        return values[position];
    }

    /** The distinct data-point names in this batch - what has to be looked up in the dictionary. */
    public List<String> distinctNames() {
        return List.of(names);
    }

    /** For the table writer: which distinct name the value at this position uses. */
    int nameIndex(int position) {
        return nameIndexes[position];
    }

    long[] recordIds() {
        return recordIds;
    }

    public static final class Builder {

        private long[] recordIds = new long[16];
        private int[] offsets = new int[17];
        private int records;

        private int[] nameIndexes = new int[64];
        private double[] values = new double[64];
        private int count;

        private final Map<String, Integer> indexOfName = new HashMap<>();
        private final List<String> names = new ArrayList<>();
        /** Per distinct name, the last record it was put into - to reject a repeat within one. */
        private int[] lastRecordOfName = new int[16];

        private Builder() {
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

        public Builder put(String name, double value) {
            if (records == 0) {
                throw new IllegalStateException("Call record(id) before put(" + name + ", ...)");
            }
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("A data point needs a name");
            }
            Integer index = indexOfName.get(name);
            if (index == null) {
                index = names.size();
                names.add(name);
                indexOfName.put(name, index);
                if (index == lastRecordOfName.length) {
                    lastRecordOfName = Arrays.copyOf(lastRecordOfName, index * 2);
                }
                lastRecordOfName[index] = -1;
            }
            // `records` is the 1-based number of the current record; a new name starts at -1.
            if (lastRecordOfName[index] == records) {
                throw new IllegalArgumentException("Record " + recordIds[records - 1]
                        + " already has a value for '" + name + "'");
            }
            lastRecordOfName[index] = records;
            if (count == values.length) {
                nameIndexes = Arrays.copyOf(nameIndexes, count * 2);
                values = Arrays.copyOf(values, count * 2);
            }
            nameIndexes[count] = index;
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
        public SparseBatch build() {
            long[] ids = Arrays.copyOf(recordIds, records);
            long[] sorted = ids.clone();
            Arrays.sort(sorted);
            for (int i = 1; i < sorted.length; i++) {
                if (sorted[i] == sorted[i - 1]) {
                    throw new IllegalArgumentException("Record " + sorted[i] + " appears twice in one batch");
                }
            }
            return new SparseBatch(ids, Arrays.copyOf(offsets, records + 1), names.toArray(new String[0]),
                    Arrays.copyOf(nameIndexes, count), Arrays.copyOf(values, count));
        }
    }
}
