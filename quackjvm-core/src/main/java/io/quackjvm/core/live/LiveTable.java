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
    private final String token;
    private final ColumnarLayout<?> layout;
    private final AtomicLong rowsRead = new AtomicLong();
    private volatile boolean open = true;

    LiveTable(Connection connection, String name, String functionName, String token, ColumnarLayout<?> layout) {
        this.connection = connection;
        this.name = name;
        this.functionName = functionName;
        this.token = token;
        this.layout = layout;
    }

    /** What SQL calls this: {@code SELECT * FROM <name>}. */
    public String getName() {
        return name;
    }

    /** The table function underneath. Calling it needs this registration's argument - see {@link #getCall()}. */
    public String getFunctionName() {
        return functionName;
    }

    /** The call the view makes, {@code quack_live_car('<token>')}, for a query that would rather use it directly. */
    public String getCall() {
        return Sql.quote(functionName) + "('" + token + "')";
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

    /**
     * Stops this registration answering, and drops the view - unless the name has since been registered
     * again, in which case the view belongs to the newer registration and is left alone.
     */
    public void unregister() {
        if (!open) {
            return;
        }
        open = false;
        LiveTableFunction.forget(token);
        if (LiveTableFunction.viewStillReads(connection, name, token)) {
            Sql.execute(connection, "DROP VIEW IF EXISTS " + Sql.quote(name));
        }
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
