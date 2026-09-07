package io.quackjvm.core.duckdb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An optional bounded LRU cache of materialised objects, keyed by primary key.
 *
 * <p>DuckDB answers a single-row point lookup in a couple of hundred microseconds, which is
 * far slower than an on-heap map. Applications which repeatedly fetch the same few objects by
 * primary key can trade a bounded amount of heap back for that latency. It is disabled by
 * default, because keeping objects off the heap is the point of this persistence.</p>
 */
public final class ObjectCache<K, O> {

    private static final ObjectCache<?, ?> DISABLED = new ObjectCache<>(0);

    private final int capacity;
    private final Map<K, O> entries;

    public ObjectCache(int capacity) {
        this.capacity = capacity;
        this.entries = capacity <= 0 ? null : new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, O> eldest) {
                return size() > ObjectCache.this.capacity;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <K, O> ObjectCache<K, O> disabled() {
        return (ObjectCache<K, O>) DISABLED;
    }

    public boolean isEnabled() {
        return entries != null;
    }

    public O get(K key) {
        if (entries == null) return null;
        synchronized (this) {
            return entries.get(key);
        }
    }

    public void put(K key, O object) {
        if (entries == null || object == null) return;
        synchronized (this) {
            entries.put(key, object);
        }
    }

    public void invalidate(K key) {
        if (entries == null) return;
        synchronized (this) {
            entries.remove(key);
        }
    }

    public void invalidateAll() {
        if (entries == null) return;
        synchronized (this) {
            entries.clear();
        }
    }
}
