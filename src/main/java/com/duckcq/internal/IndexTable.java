package com.duckcq.internal;

import com.googlecode.concurrenttrees.common.LazyIterator;
import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.Connection;
import java.util.Iterator;
import java.util.List;

/**
 * The {@code (objectKey, value)} table backing one attribute index.
 *
 * <p>This is where the storage saving over an on-heap index comes from: two columns of scalars,
 * stored by DuckDB with per-column dictionary, run-length and bit-packing compression, instead of
 * a tree of Java objects with per-entry object headers and references.</p>
 */
public final class IndexTable<K, A> {

    public static final String VALUE_COLUMN = "value";
    private static final String TABLE_PREFIX = "cqidx_";

    private final String tableName;
    private final TableWriter writer;
    private final boolean createArtIndexes;
    /** Set once the table exists, so that writes do not re-issue DDL on every call. */
    private volatile boolean tableReady;

    public IndexTable(String collectionName, String attributeName, String tableNameSuffix, Class<K> keyType,
                      Class<A> valueType, int appenderThreshold, boolean createArtIndexes) {
        this(collectionName, attributeName, tableNameSuffix, keyType, valueType, appenderThreshold,
                createArtIndexes, TableWriter.DEFAULT_STAGING_CHUNK_ROWS);
    }

    public IndexTable(String collectionName, String attributeName, String tableNameSuffix, Class<K> keyType,
                      Class<A> valueType, int appenderThreshold, boolean createArtIndexes, int stagingChunkRows) {
        this.createArtIndexes = createArtIndexes;
        this.tableName = TABLE_PREFIX + Sql.sanitizeForTableName(collectionName) + "_"
                + Sql.sanitizeForTableName(attributeName) + tableNameSuffix;
        List<ColumnDef> columns = List.of(
                new ColumnDef(ObjectTable.KEY_COLUMN, keyType),
                new ColumnDef(VALUE_COLUMN, valueType));
        this.writer = new TableWriter(tableName, columns, false, appenderThreshold, stagingChunkRows);
    }

    public String getTableName() {
        return tableName;
    }

    public boolean exists(Connection connection) {
        return writer.tableExists(connection);
    }

    /**
     * Creates the table, and optionally DuckDB ART indexes on its columns.
     *
     * <p>ART indexes are not created by default. They speed up point lookups into very large index
     * tables, but they cost several times more disk and memory than the two columns of data they
     * index, and DuckDB loads them into memory when the table is used - which works against the
     * reason for moving the collection off the heap in the first place. Without them, DuckDB
     * answers a lookup by scanning the compressed columns, which is a few milliseconds even for
     * tens of millions of rows.</p>
     */
    public void create(Connection connection) {
        if (tableReady) {
            return;
        }
        writer.createTable(connection, false);
        if (createArtIndexes) {
            Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote("cqidx_" + tableName + "_value")
                    + " ON " + Sql.quote(tableName) + " (" + Sql.quote(VALUE_COLUMN) + ")");
            Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote("cqidx_" + tableName + "_key")
                    + " ON " + Sql.quote(tableName) + " (" + Sql.quote(ObjectTable.KEY_COLUMN) + ")");
        }
        tableReady = true;
    }

    public void drop(Connection connection) {
        tableReady = false;
        Sql.execute(connection, "DROP INDEX IF EXISTS " + Sql.quote("cqidx_" + tableName + "_value"));
        Sql.execute(connection, "DROP INDEX IF EXISTS " + Sql.quote("cqidx_" + tableName + "_key"));
        writer.dropTable(connection);
    }

    public void clear(Connection connection) {
        writer.clear(connection);
    }

    public long count(Connection connection) {
        return writer.count(connection);
    }

    /**
     * Adds one row per attribute value of each object.
     *
     * @param bulkImport when true, rows already stored for these objects are not deleted first.
     *                   Only safe when the caller knows the objects are not already in the collection.
     * @return the number of rows written
     */
    public <O> long write(Connection connection, Iterable<O> objects, SimpleAttribute<O, K> primaryKeyAttribute,
                          Attribute<O, A> attribute, QueryOptions queryOptions, boolean bulkImport) {
        Iterator<Object[]> rows = rowIterator(objects, primaryKeyAttribute, attribute, queryOptions);
        return writer.write(connection, rows, !bulkImport).rowsWritten;
    }

    /**
     * Rewrites the table ordered by its value column.
     *
     * <p>DuckDB keeps a minimum and maximum per row group and skips groups which cannot match a
     * predicate. In a table written in primary-key order those ranges overlap almost completely,
     * so every group has to be read; sorting by value makes them disjoint, and a selective query
     * then touches a handful of groups instead of the whole table.</p>
     */
    public void optimize(Connection connection) {
        String sortedTable = tableName + "_sorted";
        Sql.execute(connection, "DROP TABLE IF EXISTS " + Sql.quote(sortedTable));
        Sql.execute(connection, "CREATE TABLE " + Sql.quote(sortedTable) + " AS SELECT "
                + Sql.quote(ObjectTable.KEY_COLUMN) + ", " + Sql.quote(VALUE_COLUMN)
                + " FROM " + Sql.quote(tableName) + " ORDER BY " + Sql.quote(VALUE_COLUMN));
        // Drop the old table with its indexes, then put the sorted copy in its place.
        boolean hadArtIndexes = createArtIndexes;
        drop(connection);
        Sql.execute(connection, "ALTER TABLE " + Sql.quote(sortedTable) + " RENAME TO " + Sql.quote(tableName));
        if (hadArtIndexes) {
            Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote("cqidx_" + tableName + "_value")
                    + " ON " + Sql.quote(tableName) + " (" + Sql.quote(VALUE_COLUMN) + ")");
            Sql.execute(connection, "CREATE INDEX IF NOT EXISTS " + Sql.quote("cqidx_" + tableName + "_key")
                    + " ON " + Sql.quote(tableName) + " (" + Sql.quote(ObjectTable.KEY_COLUMN) + ")");
        }
        tableReady = true;
    }

    /** Opens an appender which writes {@code (objectKey, value)} rows straight into this table. */
    public TableWriter.AppenderHandle openAppender(Connection connection) {
        return writer.openAppender(connection);
    }

    public int deleteKeys(Connection connection, Iterable<K> keys) {
        return writer.deleteKeys(connection, keys);
    }

    /** Flattens objects into {@code (objectKey, value)} rows, one row per attribute value. */
    static <O, K, A> Iterator<Object[]> rowIterator(Iterable<O> objects, SimpleAttribute<O, K> primaryKeyAttribute,
                                                    Attribute<O, A> attribute, QueryOptions queryOptions) {
        return new LazyIterator<>() {
            private final Iterator<O> objectIterator = objects.iterator();
            private Iterator<A> valueIterator = java.util.Collections.emptyIterator();
            private K currentKey;

            @Override
            protected Object[] computeNext() {
                while (!valueIterator.hasNext()) {
                    if (!objectIterator.hasNext()) {
                        return endOfData();
                    }
                    O object = objectIterator.next();
                    currentKey = primaryKeyAttribute.getValue(object, queryOptions);
                    valueIterator = attribute.getValues(object, queryOptions).iterator();
                }
                return new Object[]{currentKey, valueIterator.next()};
            }
        };
    }
}
