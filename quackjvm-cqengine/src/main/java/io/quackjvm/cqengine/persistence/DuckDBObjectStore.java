package io.quackjvm.cqengine.persistence;

import com.googlecode.cqengine.persistence.support.sqlite.SQLiteObjectStore;
import com.googlecode.cqengine.query.option.QueryOptions;
import io.quackjvm.cqengine.internal.WriteHints;

import java.util.Collection;
import java.util.Collections;

/**
 * The object store of a DuckDB-backed collection.
 *
 * <p>It behaves exactly as CQEngine's {@link SQLiteObjectStore}, which it extends, and differs only
 * in telling the identity index that this request's write has already happened.</p>
 *
 * <p>CQEngine writes every object to the object table twice: {@code doAddAll} calls the object
 * store, which is backed by the identity index, and then hands the same objects to the index
 * engine, which fans them out over every index - the identity index included. That costs SQLite
 * microseconds and DuckDB two further statements, which on a one-object add is most of the
 * latency. Marking the write here lets the identity index recognise the second pass as redundant
 * rather than repeat it; see {@link WriteHints#markObjectTableWritten}.</p>
 */
class DuckDBObjectStore<O, A extends Comparable<A>> extends SQLiteObjectStore<O, A> {

    DuckDBObjectStore(DuckDBPersistence<O, A> persistence) {
        super(persistence);
    }

    @Override
    public boolean add(O object, QueryOptions queryOptions) {
        return addAll(Collections.singleton(object), queryOptions);
    }

    @Override
    public boolean addAll(Collection<? extends O> objects, QueryOptions queryOptions) {
        boolean modified = super.addAll(objects, queryOptions);
        WriteHints.markObjectTableWritten(queryOptions, modified);
        return modified;
    }

    @Override
    public boolean remove(Object object, QueryOptions queryOptions) {
        return removeAll(Collections.singleton(object), queryOptions);
    }

    @Override
    public boolean removeAll(Collection<?> objects, QueryOptions queryOptions) {
        boolean modified = super.removeAll(objects, queryOptions);
        WriteHints.markObjectTableDeleted(queryOptions, modified);
        return modified;
    }
}
