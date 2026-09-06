package com.duckcq.persistence;

import com.duckcq.internal.IndexBulkTarget;
import com.duckcq.internal.ObjectTable;
import com.duckcq.internal.TableWriter;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;

/**
 * Streams objects straight into DuckDB, for loading data that does not already exist as a list.
 *
 * <p>{@code collection.add(object)} costs a few milliseconds, because each call is six statements
 * and a commit. {@code collection.addAll(list)} amortises that, but requires the whole batch to be
 * in memory first. This writer covers the case in between - a stream, a cursor, a file being
 * parsed - by keeping one DuckDB Appender open per table and pushing rows into it:</p>
 *
 * <pre>
 * try (DuckDBBulkWriter&lt;Car&gt; writer = persistence.bulkWriter()) {
 *     while (records.hasNext()) {
 *         writer.add(toCar(records.next()));
 *     }
 * }   // flushed and closed here
 * </pre>
 *
 * <p>It writes about ten times faster than {@code addAll} and uses a bounded amount of memory
 * however many objects pass through it.</p>
 *
 * <h2>What you give up</h2>
 * <ul>
 *     <li><b>The objects must be new.</b> Rows are appended, not merged, so an object whose primary
 *     key is already stored fails the primary key constraint at the next flush. Use
 *     {@link com.googlecode.cqengine.IndexedCollection#update} to replace existing objects.</li>
 *     <li><b>The collection is inconsistent until you flush.</b> DuckDB makes appended rows visible
 *     in batches as it fills its buffers, and each table flushes independently, so a query running
 *     during a session can see an object which is not yet in every index. {@link #flush()} and
 *     {@link #close()} make everything consistent again.</li>
 *     <li><b>Add your indexes first.</b> The writer appends to the index tables which exist when it
 *     is opened. An index added later is built from the object table at that point, so it still
 *     ends up correct - but not by this writer.</li>
 *     <li><b>One thread.</b> A writer is not thread-safe, and it holds the persistence's write lock
 *     for its lifetime, so other writers wait for it. Readers are never blocked.</li>
 * </ul>
 */
public final class DuckDBBulkWriter<O> implements AutoCloseable {

    private final ObjectTable<O, ?> objectTable;
    private final Connection connection;
    private final Lock writeLock;
    private final QueryOptions queryOptions = new QueryOptions();

    private final TableWriter.AppenderHandle objectAppender;
    private final List<IndexBulkTarget<O>> indexTargets;
    private final List<TableWriter.AppenderHandle> indexAppenders;

    private long objectsWritten;
    private boolean closed;

    DuckDBBulkWriter(ObjectTable<O, ?> objectTable, List<IndexBulkTarget<O>> indexTargets,
                     Connection connection, Lock writeLock) {
        this.objectTable = objectTable;
        this.indexTargets = indexTargets;
        this.connection = connection;
        this.writeLock = writeLock;

        objectTable.create(connection);
        for (IndexBulkTarget<O> target : indexTargets) {
            target.create(connection);
        }
        this.objectAppender = objectTable.openAppender(connection);
        this.indexAppenders = new ArrayList<>(indexTargets.size());
        try {
            for (IndexBulkTarget<O> target : indexTargets) {
                indexAppenders.add(target.openAppender(connection));
            }
        }
        catch (RuntimeException e) {
            closeQuietly();
            throw e;
        }
    }

    /** Writes one object and its index entries. */
    public void add(O object) {
        ensureOpen();
        Object[] row = objectTable.toRow(object, queryOptions);
        objectAppender.appendRow(row);
        Object key = row[0];
        for (int i = 0; i < indexTargets.size(); i++) {
            indexTargets.get(i).appendRowsFor(indexAppenders.get(i), object, key, queryOptions);
        }
        objectsWritten++;
    }

    public void addAll(Iterable<? extends O> objects) {
        for (O object : objects) {
            add(object);
        }
    }

    /**
     * Makes everything written so far visible to queries, and leaves the writer open.
     *
     * <p>This is also where a duplicate primary key is reported, since DuckDB checks constraints
     * when it flushes rather than when a row is appended.</p>
     */
    public void flush() {
        ensureOpen();
        objectAppender.flush();
        for (TableWriter.AppenderHandle appender : indexAppenders) {
            appender.flush();
        }
        objectTable.getCache().invalidateAll();
    }

    /** How many objects this writer has been given. */
    public long getObjectsWritten() {
        return objectsWritten;
    }

    /** Flushes, releases the connection and the write lock. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            flush();
        }
        finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        closed = true;
        RuntimeException failure = null;
        for (TableWriter.AppenderHandle appender : indexAppenders) {
            failure = closeAppender(appender, failure);
        }
        failure = closeAppender(objectAppender, failure);
        try {
            connection.close();
        }
        catch (Exception e) {
            failure = failure != null ? failure : new IllegalStateException("Failed to close the connection", e);
        }
        finally {
            if (writeLock != null) {
                writeLock.unlock();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeAppender(TableWriter.AppenderHandle appender, RuntimeException failure) {
        try {
            appender.close();
            return failure;
        }
        catch (RuntimeException e) {
            return failure != null ? failure : e;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("This DuckDBBulkWriter has been closed");
        }
    }
}
