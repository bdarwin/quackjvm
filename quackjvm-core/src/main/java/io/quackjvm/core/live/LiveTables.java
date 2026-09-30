package io.quackjvm.core.live;

import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.util.function.Supplier;

/**
 * Java objects queried as a table, with no load step and nothing copied.
 *
 * <pre>
 * List&lt;Car&gt; cars = ...;                                 // an ordinary list, still yours
 * LiveTable table = LiveTables.register(connection, "car", cars, ColumnarLayout.ofRecord(Car.class));
 *
 * // SQL now has a table called car
 * Rows.of(connection, "SELECT make, count(*) FROM car GROUP BY 1");
 * Rows.of(connection, "SELECT c.make, sum(s.price) FROM car c JOIN sale s ON s.carId = c.carId GROUP BY 1");
 * </pre>
 *
 * <p>The list is read where it lies, a chunk at a time, when a query asks for it. Nothing is written
 * into the database, so there is nothing to keep in step: add an object and the next query sees it.</p>
 *
 * <h2>What it is made of</h2>
 *
 * <p>A DuckDB table function, which the Java client gained in 1.5, plus a view over it so that SQL can
 * say {@code car} rather than {@code car()}. The columns come from a {@link ColumnarLayout}, so the
 * same description that stores objects in a table also exposes them from the heap.</p>
 *
 * <h2>What to expect of it</h2>
 *
 * <ul>
 *   <li><b>Live, not a snapshot.</b> Each query iterates the collection as it is then. A change made
 *       while a query is running behaves as it would for any other iteration of that collection - an
 *       {@code ArrayList} throws {@code ConcurrentModificationException}, a
 *       {@code CopyOnWriteArrayList} does not. Use {@link #snapshot} when a query must see one
 *       consistent state.</li>
 *   <li><b>One thread per scan.</b> A Java {@code Iterable} cannot be split safely in general, so a
 *       live table is scanned by one thread. Everything DuckDB does with the rows afterwards - joins,
 *       grouping, sorting - is still parallel.</li>
 *   <li><b>Only the columns asked for</b> are read from the objects, when DuckDB pushes the projection
 *       down, which it does for a plain scan.</li>
 *   <li><b>Types.</b> Every type {@link io.quackjvm.core.duckdb.DuckDBTypes} maps, except three that
 *       DuckDB's Java chunk writer has no way to write in 1.5.5: {@code UUID}, {@code byte[]} and
 *       {@code LocalTime}. Registering a layout with one of those is refused, saying which column and
 *       what to do instead.</li>
 * </ul>
 */
public final class LiveTables {

    private LiveTables() {
    }

    /**
     * Makes a collection queryable as {@code name}. The collection is read where it lies, so it stays
     * live: what the next query sees is what it holds then.
     */
    public static <O> LiveTable register(Connection connection, String name, Iterable<O> rows,
                                         ColumnarLayout<O> layout) {
        return register(connection, name, () -> rows, layout);
    }

    /**
     * The same, where what is queryable is whatever the supplier returns at the time - a collection
     * that gets replaced wholesale, a cache's current contents, a filtered view of something larger.
     */
    public static <O> LiveTable register(Connection connection, String name, Supplier<Iterable<O>> rows,
                                         ColumnarLayout<O> layout) {
        return LiveTableFunction.register(connection, name, rows, layout, false);
    }

    /**
     * Makes a copy of the collection at registration time and queries that, so every query sees the
     * same rows however the original changes afterwards. Costs the memory of the copy.
     */
    public static <O> LiveTable snapshot(Connection connection, String name, Iterable<O> rows,
                                         ColumnarLayout<O> layout) {
        return LiveTableFunction.register(connection, name, () -> rows, layout, true);
    }
}
