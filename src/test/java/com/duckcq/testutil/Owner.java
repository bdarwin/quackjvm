package com.duckcq.testutil;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

/** A second object type, for testing collections which share a database and are joined. */
public record Owner(int ownerId, String name, String country, int age) {

    public static final SimpleAttribute<Owner, Integer> OWNER_ID =
            new SimpleAttribute<>(Owner.class, Integer.class, "ownerId") {
                @Override
                public Integer getValue(Owner owner, QueryOptions queryOptions) {
                    return owner.ownerId();
                }
            };

    public static final Attribute<Owner, String> NAME =
            new SimpleAttribute<>(Owner.class, String.class, "name") {
                @Override
                public String getValue(Owner owner, QueryOptions queryOptions) {
                    return owner.name();
                }
            };

    public static final Attribute<Owner, String> COUNTRY =
            new SimpleAttribute<>(Owner.class, String.class, "country") {
                @Override
                public String getValue(Owner owner, QueryOptions queryOptions) {
                    return owner.country();
                }
            };

    public static final Attribute<Owner, Integer> AGE =
            new SimpleAttribute<>(Owner.class, Integer.class, "age") {
                @Override
                public Integer getValue(Owner owner, QueryOptions queryOptions) {
                    return owner.age();
                }
            };
}
