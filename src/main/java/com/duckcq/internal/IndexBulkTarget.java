package com.duckcq.internal;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.sql.Connection;

/**
 * One attribute index, as seen by a bulk writer: the table to append to, and the attribute whose
 * values become the rows.
 */
public final class IndexBulkTarget<O> {

    private final IndexTable<?, ?> indexTable;
    private final Attribute<O, ?> attribute;

    public IndexBulkTarget(IndexTable<?, ?> indexTable, Attribute<O, ?> attribute) {
        this.indexTable = indexTable;
        this.attribute = attribute;
    }

    public String getTableName() {
        return indexTable.getTableName();
    }

    public Attribute<O, ?> getAttribute() {
        return attribute;
    }

    public void create(Connection connection) {
        indexTable.create(connection);
    }

    public TableWriter.AppenderHandle openAppender(Connection connection) {
        return indexTable.openAppender(connection);
    }

    public void optimize(Connection connection) {
        indexTable.optimize(connection);
    }

    /**
     * Appends one row per value the attribute holds for this object - several for a multi-valued
     * attribute, none at all when the attribute has no value.
     */
    public void appendRowsFor(TableWriter.AppenderHandle appender, O object, Object key, QueryOptions queryOptions) {
        for (Object value : attribute.getValues(object, queryOptions)) {
            appender.appendRow(new Object[]{key, value});
        }
    }
}
