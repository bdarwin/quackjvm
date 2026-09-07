package io.quackjvm.cqengine.index;

import com.googlecode.cqengine.index.support.indextype.NonHeapTypeIndex;

/**
 * Marker for indexes which store their data in a DuckDB database, and which therefore require a
 * {@link io.quackjvm.cqengine.persistence.DuckDBPersistence} to be configured on the collection.
 *
 * <p>It plays the same role for DuckDB that
 * {@link com.googlecode.cqengine.index.support.indextype.DiskTypeIndex} plays for CQEngine's
 * SQLite-backed disk persistence.</p>
 */
public interface DuckDBTypeIndex extends NonHeapTypeIndex {
}
