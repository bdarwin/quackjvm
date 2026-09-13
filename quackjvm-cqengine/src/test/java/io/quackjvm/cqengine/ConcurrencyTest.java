package io.quackjvm.cqengine;

import io.quackjvm.cqengine.index.DuckDBIndex;
import io.quackjvm.cqengine.persistence.DuckDBPersistence;
import io.quackjvm.cqengine.testutil.Car;
import io.quackjvm.cqengine.testutil.Cars;
import com.googlecode.cqengine.ConcurrentIndexedCollection;
import com.googlecode.cqengine.IndexedCollection;
import com.googlecode.cqengine.query.option.QueryOptions;
import com.googlecode.cqengine.resultset.ResultSet;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    /**
     * A {@code QueryOptions} hoisted out of a loop and shared between threads used to hand them all
     * one pooled connection, which deadlocks inside DuckDB's JDBC driver - an ABBA cycle between
     * {@code DuckDBConnection.close()} and {@code DuckDBPreparedStatement.close()} that no timeout
     * recovers from. It must now be reported instead.
     */
    @Test(timeout = 120_000)
    public void aQueryOptionsSharedBetweenThreadsIsReportedRatherThanDeadlocking() throws Exception {
        persistence = DuckDBPersistence.onPrimaryKeyInMemory(Car.CAR_ID);
        IndexedCollection<Car> cars = new ConcurrentIndexedCollection<>(persistence);
        cars.addIndex(DuckDBIndex.onAttribute(Car.MANUFACTURER));
        cars.addAll(Cars.generate(500, 42));

        QueryOptions shared = new QueryOptions();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                ready.await();
                try {
                    for (int i = 0; i < 50; i++) {
                        try (ResultSet<Car> results =
                                     cars.retrieve(equal(Car.MANUFACTURER, "Ford"), shared)) {
                            results.size();
                        }
                    }
                    return true;                      // this thread owned the options
                }
                catch (IllegalStateException expected) {
                    assertTrue("the message must say what to do: " + expected.getMessage(),
                            expected.getMessage().contains("two threads at once"));
                    return false;                     // correctly rejected
                }
            }));
        }
        pool.shutdown();
        assertTrue("sharing a QueryOptions must not hang",
                pool.awaitTermination(90, TimeUnit.SECONDS));
        int completed = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                completed++;
            }
        }
        assertEquals("exactly one thread should own the shared options", 1, completed);
    }
}
