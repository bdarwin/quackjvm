package io.quackjvm.agent.guard;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A query stopped by a flag the application already has - issue #14. */
public class CancelSignalPollingTest {

    /** Would run for hours; DuckDB checks for a cancel all through it. */
    private static final String LONG = "SELECT count(*) FROM range(100000000000) t(x) WHERE x % 7 = 3";

    private Connection connection;
    private final AtomicInteger statementsCreated = new AtomicInteger();
    private Connection counted;

    @Before
    public void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        counted = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createStatement") || method.getName().equals("prepareStatement")) {
                        statementsCreated.incrementAndGet();
                    }
                    try {
                        return method.invoke(connection, args);
                    }
                    catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private GuardedQuery guard() {
        return GuardedQuery.on(counted, QueryPolicy.readOnly().timeout(Duration.ofMinutes(1)));
    }

    @Test
    public void aSupplierThatIsAlreadyTrueStopsTheQueryBeforeAnythingRuns() {
        CancelSignal signal = CancelSignal.from(() -> true);
        try {
            // A table that is not there: had anything run, this would be a QueryFailed.
            guard().run("SELECT * FROM not_there", signal);
            fail();
        }
        catch (QueryCancelled expected) {
            assertEquals("nothing was parsed or run", 0, statementsCreated.get());
            assertTrue(signal.isCancelled());
        }
    }

    @Test
    public void aSupplierThatTurnsTrueStopsALongQueryWithinAPoll() throws Exception {
        GuardedQuery guard = guard();
        long worst = 0;
        for (int i = 0; i < 5; i++) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong flipped = new AtomicLong();
            Thread flipper = new Thread(() -> {
                try {
                    Thread.sleep(200);
                }
                catch (InterruptedException e) {
                    return;
                }
                flipped.set(System.nanoTime());
                stop.set(true);
            });
            flipper.start();
            try {
                guard.run(LONG, CancelSignal.from(stop::get));
                fail("finished");
            }
            catch (QueryCancelled expected) {
                long after = (System.nanoTime() - flipped.get()) / 1_000_000;
                worst = Math.max(worst, after);
            }
            flipper.join();
        }
        assertTrue("stopped " + worst + " ms after the flag turned", worst < 100);
        // The connection is still usable afterwards.
        assertEquals(1, guard.run("SELECT 42").getRowCount());
    }

    @Test
    public void aShorterPollStopsSooner() {
        AtomicBoolean stop = new AtomicBoolean();
        long[] flipped = new long[1];
        new Thread(() -> {
            try {
                Thread.sleep(150);
            }
            catch (InterruptedException ignored) {
                return;
            }
            flipped[0] = System.nanoTime();
            stop.set(true);
        }).start();
        try {
            guard().run(LONG, CancelSignal.polling(stop::get, Duration.ofMillis(5)));
            fail();
        }
        catch (QueryCancelled expected) {
            long after = (System.nanoTime() - flipped[0]) / 1_000_000;
            assertTrue("stopped " + after + " ms after", after < 100);
        }
    }

    @Test
    public void theSupplierIsNoLongerAskedOnceTheQueryHasFinished() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CancelSignal signal = CancelSignal.polling(() -> {
            asked.incrementAndGet();
            return false;
        }, Duration.ofMillis(2));
        guard().run("SELECT count(*) FROM range(20000000)", signal);
        int then = asked.get();
        Thread.sleep(100);
        assertEquals("asked again after the run", then, asked.get());
    }

    @Test
    public void oneSignalServesRunAfterRunUntilItsSupplierSaysStop() {
        AtomicBoolean stop = new AtomicBoolean();
        CancelSignal signal = CancelSignal.from(stop::get);
        GuardedQuery guard = guard();
        assertEquals(1, guard.run("SELECT 1", signal).getRowCount());
        assertEquals(1, guard.run("SELECT 2", signal).getRowCount());
        stop.set(true);
        try {
            guard.run("SELECT 3", signal);
            fail();
        }
        catch (QueryCancelled expected) {
            assertTrue(signal.isCancelled());
        }
    }

    @Test
    public void aSupplierThatThrowsDoesNotStopTheQuery() {
        CancelSignal signal = CancelSignal.polling(() -> {
            throw new IllegalStateException("broken flag");
        }, Duration.ofMillis(1));
        assertEquals(1, guard().run("SELECT count(*) FROM range(5000000)", signal).getRowCount());
        assertFalse(signal.isCancelled());
    }

    @Test
    public void cancelStillWorksOnASignalFromASupplier() throws Exception {
        CancelSignal signal = CancelSignal.from(() -> false);
        new Thread(() -> {
            try {
                Thread.sleep(150);
            }
            catch (InterruptedException ignored) {
                return;
            }
            signal.cancel();
        }).start();
        try {
            guard().run(LONG, signal);
            fail();
        }
        catch (QueryCancelled expected) {
            // stopped
        }
    }

    @Test
    public void thePollIntervalMustBePositive() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofMillis(-1)}) {
            try {
                CancelSignal.polling(() -> false, bad);
                fail(bad.toString());
            }
            catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("positive"));
            }
        }
        try {
            CancelSignal.from(null);
            fail();
        }
        catch (IllegalArgumentException expected) {
            // no supplier
        }
    }

    @Test
    public void aPollingThreadIsADaemonSoItNeverKeepsTheJvmUp() throws Exception {
        AtomicBoolean seen = new AtomicBoolean();
        CancelSignal signal = CancelSignal.polling(() -> {
            seen.set(Thread.currentThread().isDaemon() || seen.get());
            return false;
        }, Duration.ofMillis(1));
        guard().run("SELECT count(*) FROM range(50000000) t(x) WHERE x % 3 = 1", signal);
        assertTrue(seen.get());
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().equals("quackjvm-cancel-poll")) {
                assertTrue(thread.isDaemon());
            }
        }
    }

}
