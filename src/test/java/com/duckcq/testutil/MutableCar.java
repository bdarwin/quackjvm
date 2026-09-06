package com.duckcq.testutil;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.util.Objects;

/**
 * Test object: an ordinary class with private final fields and no no-arg constructor, used to
 * check that ColumnarLayout.reflective() can store and rebuild arbitrary POJOs.
 */
public class MutableCar {

    private final long id;
    private final String name;
    private final Integer year;

    public MutableCar(long id, String name, Integer year) {
        this.id = id;
        this.name = name;
        this.year = year;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getYear() {
        return year;
    }

    public static final SimpleAttribute<MutableCar, Long> ID =
            new SimpleAttribute<>(MutableCar.class, Long.class, "id") {
                @Override
                public Long getValue(MutableCar car, QueryOptions queryOptions) {
                    return car.getId();
                }
            };

    public static final Attribute<MutableCar, String> NAME =
            new SimpleAttribute<>(MutableCar.class, String.class, "name") {
                @Override
                public String getValue(MutableCar car, QueryOptions queryOptions) {
                    return car.getName();
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MutableCar)) return false;
        MutableCar that = (MutableCar) o;
        return id == that.id && Objects.equals(name, that.name) && Objects.equals(year, that.year);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, year);
    }

    @Override
    public String toString() {
        return "MutableCar{" + id + ", " + name + ", " + year + "}";
    }
}
