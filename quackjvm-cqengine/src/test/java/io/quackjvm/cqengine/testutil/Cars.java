package io.quackjvm.cqengine.testutil;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generates deterministic test data. */
public final class Cars {

    private static final String[] MANUFACTURERS = {"Ford", "Honda", "Toyota", "BMW", "Tesla"};
    private static final String[] WORDS = {"fast", "cheap", "hybrid", "coupe", "sunroof", "diesel"};

    private Cars() {
    }

    public static Date toDate(LocalDate date) {
        return Date.from(date.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public static List<Car> generate(int count, long seed) {
        Random random = new Random(seed);
        List<Car> cars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            StringBuilder description = new StringBuilder();
            int words = random.nextInt(3);
            for (int w = 0; w <= words; w++) {
                if (w > 0) description.append(' ');
                description.append(WORDS[random.nextInt(WORDS.length)]);
            }
            cars.add(new Car(
                    i,
                    MANUFACTURERS[random.nextInt(MANUFACTURERS.length)],
                    "model" + random.nextInt(50),
                    Car.Color.values()[random.nextInt(Car.Color.values().length)],
                    random.nextBoolean() ? 3 : 5,
                    Math.round(random.nextDouble() * 5000000) / 100.0,
                    description.toString(),
                    // Every seventh car has no registration date, to exercise null handling.
                    i % 7 == 0 ? null : toDate(LocalDate.of(2000 + random.nextInt(25),
                            1 + random.nextInt(12), 1 + random.nextInt(28)))));
        }
        return cars;
    }
}
