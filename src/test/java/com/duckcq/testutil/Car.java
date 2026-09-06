package com.duckcq.testutil;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.MultiValueAttribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.attribute.SimpleNullableAttribute;
import com.googlecode.cqengine.query.option.QueryOptions;

import java.util.Date;
import java.util.Arrays;
import java.util.List;

/**
 * Test object: a record with only scalar fields, so it can be stored either as a BLOB or
 * shredded into columns.
 */
public record Car(int carId, String manufacturer, String model, Car.Color color, int doors,
                  double price, String description, Date registered) {

    public enum Color {RED, GREEN, BLUE, BLACK, WHITE}

    public static final SimpleAttribute<Car, Integer> CAR_ID =
            new SimpleAttribute<>(Car.class, Integer.class, "carId") {
                @Override
                public Integer getValue(Car car, QueryOptions queryOptions) {
                    return car.carId();
                }
            };

    public static final Attribute<Car, String> MANUFACTURER =
            new SimpleAttribute<>(Car.class, String.class, "manufacturer") {
                @Override
                public String getValue(Car car, QueryOptions queryOptions) {
                    return car.manufacturer();
                }
            };

    public static final Attribute<Car, String> MODEL =
            new SimpleAttribute<>(Car.class, String.class, "model") {
                @Override
                public String getValue(Car car, QueryOptions queryOptions) {
                    return car.model();
                }
            };

    public static final Attribute<Car, Color> COLOR =
            new SimpleAttribute<>(Car.class, Color.class, "color") {
                @Override
                public Color getValue(Car car, QueryOptions queryOptions) {
                    return car.color();
                }
            };

    public static final Attribute<Car, Integer> DOORS =
            new SimpleAttribute<>(Car.class, Integer.class, "doors") {
                @Override
                public Integer getValue(Car car, QueryOptions queryOptions) {
                    return car.doors();
                }
            };

    public static final Attribute<Car, Double> PRICE =
            new SimpleAttribute<>(Car.class, Double.class, "price") {
                @Override
                public Double getValue(Car car, QueryOptions queryOptions) {
                    return car.price();
                }
            };

    /** Nullable, to exercise attributes which have no value for some objects. */
    public static final Attribute<Car, Date> REGISTERED =
            new SimpleNullableAttribute<>(Car.class, Date.class, "registered") {
                @Override
                public Date getValue(Car car, QueryOptions queryOptions) {
                    return car.registered();
                }
            };

    /** Multi-valued: one index row per word of the description. */
    public static final Attribute<Car, String> FEATURES =
            new MultiValueAttribute<>(Car.class, String.class, "features") {
                @Override
                public List<String> getValues(Car car, QueryOptions queryOptions) {
                    return car.description() == null || car.description().isEmpty()
                            ? List.of()
                            : Arrays.asList(car.description().split(" "));
                }
            };
}
