package com.duckcq;

import com.duckcq.index.DuckDBIndex;
import com.duckcq.persistence.DuckDBPersistence;
import com.duckcq.testutil.Car;
import com.duckcq.testutil.Cars;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.googlecode.cqengine.query.QueryFactory.equal;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConcurrencyTest {

    private DuckDBPersistence<Car, Integer> persistence;

    @After
    public void tearDown() {
        if (persistence != null) {
            persistence.close();
        }
    }

    @Test
    public void readersAndWritersDoNotInterfere() throws Exception {
        persistence = DuckDBPersistence.onPrimaryKeyInMemory(Car.CAR_ID);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        List<Car> initial = Cars.generate(500, 21);
        cars.addAll(initial);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(5);

        Runnable reader = () -> {
            started.countDown();
            try {
                while (!stop.get()) {
                    try (ResultSet<Car> results = cars.retrieve(equal(Car.MANUFACTURER, "Ford"))) {
                        int count = 0;
                        for (Car ignored : results) {
                            count++;
                        }
                        assertTrue(count >= 0);
                    }
                }
            }
            catch (Throwable t) {
                failures.add(t);
            }
        };

        Thread[] readers = new Thread[4];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(reader, "reader-" + i);
            readers[i].start();
        }

        Thread writer = new Thread(() -> {
            started.countDown();
            try {
                for (Car car : Cars.generate(200, 22)) {
                    Car shifted = new Car(car.carId() + 10_000, car.manufacturer(), car.model(), car.color(),
                            car.doors(), car.price(), car.description(), car.registered());
                    cars.add(shifted);
                }
            }
            catch (Throwable t) {
                failures.add(t);
            }
        }, "writer");
        writer.start();

        assertTrue(started.await(30, TimeUnit.SECONDS));
        writer.join(60_000);
        stop.set(true);
        for (Thread thread : readers) {
            thread.join(30_000);
        }

        assertEquals("concurrent access should not fail: " + failures, List.of(), failures);
        assertEquals(700, cars.size());
    }

    @Test
    public void writesFromSeveralThreadsAreSerialised() throws Exception {
        persistence = DuckDBPersistence.onPrimaryKeyInMemory(Car.CAR_ID);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] writers = new Thread[4];
        for (int t = 0; t < writers.length; t++) {
            final int offset = t * 1000;
            writers[t] = new Thread(() -> {
                try {
                    for (Car car : Cars.generate(100, 30 + offset)) {
                        cars.add(new Car(car.carId() + offset, car.manufacturer(), car.model(), car.color(),
                                car.doors(), car.price(), car.description(), car.registered()));
                    }
                }
                catch (Throwable e) {
                    failures.add(e);
                }
            });
            writers[t].start();
        }
        for (Thread writer : writers) {
            writer.join(120_000);
        }

        assertEquals("concurrent writes should not conflict: " + failures, List.of(), failures);
        assertEquals(400, cars.size());
    }
}
