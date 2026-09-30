package io.quackjvm.core.live;

import io.quackjvm.core.duckdb.Sql;
import io.quackjvm.core.layout.ColumnarLayout;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A registered {@link LiveTables} table: what SQL calls it, and the way to take it away again.
 *
 * <p>Closing it drops the view and detaches the data, so the name stops answering. The table function
 * behind it stays registered, because DuckDB 1.5.5 has no way to unregister one - a query written
 * against the function directly after that says the table is gone rather than returning stale rows.</p>
 */
public final class LiveTable implements AutoCloseable {

    private final Connection connection;
    private final String name;
    private final String functionName;
    private final ColumnarLayout<?> layout;
    private final AtomicLong rowsRead = new AtomicLong();
    private volatile boolean open = true;

    LiveTable(Connection connection, String name, String functionName, ColumnarLayout<?> layout) {
        this.connection = connection;
        this.name = name;
        this.functionName = functionName;
        this.layout = layout;
    }

    /** What SQL calls this: {@code SELECT * FROM <name>}. */
    public String getName() {
        return name;
    }

    /** The table function underneath, for a query that would rather call it directly. */
    public String getFunctionName() {
        return functionName;
    }

    public ColumnarLayout<?> getLayout() {
        return layout;
    }

    /** How many rows have been read out of the objects since this was registered. */
    public long getRowsRead() {
        return rowsRead.get();
    }

    void countRows(long rows) {
        rowsRead.addAndGet(rows);
    }

    public boolean isOpen() {
        return open;
    }

    /** Drops the view and stops the name answering. */
    public void unregister() {
        if (!open) {
            return;
        }
        open = false;
        Sql.execute(connection, "DROP VIEW IF EXISTS " + Sql.quote(name));
        LiveTableFunction.forget(functionName);
    }

    @Override
    public void close() {
        unregister();
    }

    @Override
    public String toString() {
        return "LiveTable[" + name + " of " + layout.getObjectType().getSimpleName()
                + (open ? "" : ", closed") + "]";
    }
}
