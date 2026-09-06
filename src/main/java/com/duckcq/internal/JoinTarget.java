package com.duckcq.internal;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;

/**
 * What a join needs to know about a collection stored in DuckDB: where its objects live, and which
 * of its attributes have index tables that can be joined against.
 */
public interface JoinTarget<O> {

    /** The table holding this collection's objects. */
    String objectTableName();

    /** The object table itself, for reading whole objects out of a join's result set. */
    ObjectTable<O, ?> objectTable();

    /** The attribute which identifies each object, and which the object table is keyed by. */
    SimpleAttribute<O, ?> primaryKeyAttribute();

    /**
     * The {@code (objectKey, value)} table for the given attribute, or null when the attribute is
     * not indexed in DuckDB and therefore cannot take part in a pushed-down join.
     */
    String indexTableFor(Attribute<?, ?> attribute);
}
