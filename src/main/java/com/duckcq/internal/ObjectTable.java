package com.duckcq.internal;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The table which holds the objects of the collection: one row per object, keyed by the
 * primary key attribute.
 *
 * <p>The shape of the value columns is decided by the {@link RowCodec}: either a single BLOB
 * column holding the serialized object, or one typed column per field.</p>
 */
public final class ObjectTable<O, K> {

    /** Prefix of the table holding a collection's objects; the collection name follows. */
    public static final String TABLE_PREFIX = "cq_";
    public static final String KEY_COLUMN = "objectKey";

    /** The table a collection of the given name stores its objects in. */
    public static String tableNameFor(String collectionName) {
        return TABLE_PREFIX + Sql.sanitizeForTableName(collectionName);
    }

    private final String tableName;
    private final SimpleAttribute<O, K> primaryKeyAttribute;
    private final Class<K> keyType;
    private final DuckDBTypes.ColumnReader<K> keyReader;
    private final RowCodec<O> codec;
    private final TableWriter writer;
    private final List<ColumnDef> columns;
    private final ObjectCache<K, O> cache;
    private final String selectListForAlias;
    /**
     * Whether the table has been created and checked in this JVM. Writes call {@link #create} to be
     * safe against a collection used before init; re-issuing that DDL and its schema check on every
     * single add costs two round trips to DuckDB, which dominates the latency of a one-object add.
     */
    private volatile boolean tableReady;

    public ObjectTable(String collectionName, SimpleAttribute<O, K> primaryKeyAttribute, RowCodec<O> codec,
                       ObjectCache<K, O> cache, int appenderThreshold) {
        this(collectionName, primaryKeyAttribute, codec, cache, appenderThreshold,
                TableWriter.DEFAULT_STAGING_CHUNK_ROWS);
    }

    public ObjectTable(String collectionName, SimpleAttribute<O, K> primaryKeyAttribute, RowCodec<O> codec,
                       ObjectCache<K, O> cache, int appenderThreshold, int stagingChunkRows) {
        this.tableName = tableNameFor(collectionName);
        this.primaryKeyAttribute = primaryKeyAttribute;
        this.keyType = primaryKeyAttribute.getAttributeType();
        this.keyReader = DuckDBTypes.readerFor(keyType);
        this.codec = codec;
        this.cache = cache;
        List<ColumnDef> columns = new ArrayList<>();
        columns.add(new ColumnDef(KEY_COLUMN, keyType));
        columns.addAll(codec.columns());
        this.columns = List.copyOf(columns);
        this.writer = new TableWriter(tableName, this.columns, true, appenderThreshold, stagingChunkRows);
        this.selectListForAlias = buildSelectList("o");
    }

    public SimpleAttribute<O, K> getPrimaryKeyAttribute() {
        return primaryKeyAttribute;
    }

    public Class<K> getKeyType() {
        return keyType;
    }

    public ObjectCache<K, O> getCache() {
        return cache;
    }

    public String getTableName() {
        return tableName;
    }

    /** Number of columns {@link #selectList(String)} produces, including the key. */
    public int getColumnCount() {
        return columns.size();
    }

    /** The table's columns, key first - the names usable in raw SQL. */
    public List<ColumnDef> getColumns() {
        return columns;
    }

    /** True when objects are shredded into typed columns rather than stored as one blob. */
    public boolean isColumnar() {
        return !(codec instanceof BlobRowCodec);
    }

    /** The fully qualified key column, e.g. {@code "o"."objectKey"}. */
    public static String keyColumn(String alias) {
        return Sql.quote(alias) + "." + Sql.quote(KEY_COLUMN);
    }

    /** The select list needed to materialise an object, qualified with the given table alias. */
    public String selectList(String alias) {
        return "o".equals(alias) ? selectListForAlias : buildSelectList(alias);
    }

    private String buildSelectList(String alias) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(Sql.quote(alias)).append('.').append(Sql.quote(columns.get(i).getName()));
        }
        return sb.toString();
    }

    /** Reads a row produced by {@link #selectList(String)}: the key first, then the object's state. */
    public O readObject(ResultSet resultSet, int startIndex) throws SQLException {
        K key = keyReader.read(resultSet, startIndex);
        if (cache.isEnabled()) {
            O cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            O object = codec.readObject(resultSet, startIndex + 1);
            cache.put(key, object);
            return object;
        }
        return codec.readObject(resultSet, startIndex + 1);
    }

    public K readKey(ResultSet resultSet, int index) throws SQLException {
        return keyReader.read(resultSet, index);
    }

    public void create(Connection connection) {
        if (tableReady) {
            return;
        }
        writer.createTable(connection, true);
        writer.validateSchema(connection);
        tableReady = true;
    }

    public boolean exists(Connection connection) {
        return writer.tableExists(connection);
    }

    public long count(Connection connection) {
        return writer.count(connection);
    }

    public void clear(Connection connection) {
        writer.clear(connection);
        cache.invalidateAll();
    }

    public void drop(Connection connection) {
        writer.dropTable(connection);
        tableReady = false;
        cache.invalidateAll();
    }

    /**
     * Stores the given objects, replacing any object already stored under the same key.
     *
     * @return how many rows were written, and how many previously stored objects they replaced
     */
    public TableWriter.WriteResult write(Connection connection, Iterable<O> objects, QueryOptions queryOptions,
                                         boolean bulkImport) {
        Iterator<Object[]> rows = rowIterator(objects.iterator(), queryOptions);
        TableWriter.WriteResult result = writer.write(connection, rows, !bulkImport);
        if (cache.isEnabled()) {
            // Simplest correct thing to do; writes are rare relative to reads when a cache is in use.
            cache.invalidateAll();
        }
        return result;
    }

    public int delete(Connection connection, Iterable<K> keys) {
        if (cache.isEnabled()) {
            for (K key : keys) {
                cache.invalidate(key);
            }
        }
        return writer.deleteKeys(connection, keys);
    }

    /** Turns one object into a row: the primary key, then its state in column order. */
    public Object[] toRow(O object, QueryOptions queryOptions) {
        Object[] row = new Object[columns.size()];
        row[0] = primaryKeyAttribute.getValue(object, queryOptions);
        if (row[0] == null) {
            throw new NullPointerException("Primary key attribute "
                    + primaryKeyAttribute.getAttributeName() + " returned null for object: " + object);
        }
        codec.writeRow(object, row, 1, queryOptions);
        return row;
    }

    /** Opens an appender which writes objects straight into the object table. */
    public TableWriter.AppenderHandle openAppender(Connection connection) {
        return writer.openAppender(connection);
    }

    private Iterator<Object[]> rowIterator(Iterator<O> objects, QueryOptions queryOptions) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return objects.hasNext();
            }

            @Override
            public Object[] next() {
                return toRow(objects.next(), queryOptions);
            }
        };
    }

    /**
     * Reads a page of objects ordered by primary key, for rebuilding an index over a collection
     * which already holds data.
     *
     * <p>Paging by key rather than holding one cursor open for the whole scan means no result set
     * stays open across the writes which the caller performs between pages.</p>
     *
     * @param afterKeyExclusive the last key of the previous page, or null to start at the beginning
     */
    public List<O> readPage(Connection connection, K afterKeyExclusive, int limit) {
        String sql = "SELECT " + selectList("o") + " FROM " + Sql.quote(tableName) + " o"
                + (afterKeyExclusive == null ? "" : " WHERE " + keyColumn("o") + " > ?")
                + " ORDER BY " + keyColumn("o") + " LIMIT " + limit;
        List<O> page = new ArrayList<>(Math.min(limit, 1024));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (afterKeyExclusive != null) {
                DuckDBTypes.bind(statement, 1, afterKeyExclusive);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    page.add(readObject(resultSet, 1));
                }
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to read a page of objects", e);
        }
        return page;
    }

    /** Fetches a single object by primary key, or null if there is no such object. */
    public O get(Connection connection, K key) {
        O cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String sql = "SELECT " + selectList("o") + " FROM " + Sql.quote(tableName) + " o WHERE "
                + keyColumn("o") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            DuckDBTypes.bind(statement, 1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readObject(resultSet, 1) : null;
            }
        }
        catch (SQLException e) {
            throw new IllegalStateException("Failed to look up object with key: " + key, e);
        }
    }
}
