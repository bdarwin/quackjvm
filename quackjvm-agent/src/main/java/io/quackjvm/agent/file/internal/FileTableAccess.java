package io.quackjvm.agent.file.internal;

import io.quackjvm.agent.file.FileTable;
import io.quackjvm.agent.guard.CancelSignal;
import io.quackjvm.core.Quack;
import io.quackjvm.core.duckdb.ColumnDef;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * What a {@link io.quackjvm.agent.session.QuerySession} needs of a {@link FileTable} it hosts, and
 * no application does: moving it into the session's database and back, and what its queries need
 * first. Not part of the API.
 */
public interface FileTableAccess {

    /** Moves the table into this database, its cache attached as {@code catalog}. */
    void moveTo(FileTable table, Quack database, String catalog, Runnable release);

    /** Moves it back to a database of its own. */
    void moveHome(FileTable table);

    /** Lets go of the database it is in, without going anywhere. */
    void detach(FileTable table);

    boolean isHosted(FileTable table);

    /** The files DuckDB must be let use for the cache to be attached. */
    List<String> attachedFiles(FileTable table);

    /** All its columns: the parser's, then those the application computes. */
    List<ColumnDef> columns(FileTable table);

    /** Changes when its columns change - a computed one added - for the session to plan again. */
    int columnChanges(FileTable table);

    boolean isLazy(FileTable table);

    /** Stores these lazy columns, if they are not; refuses more than a query may add. */
    void store(FileTable table, Set<String> columns, String sql, CancelSignal signal);

    /** Why storing these would be refused; null if it would not. */
    String whyNotStore(FileTable table, Set<String> columns);

    /** The table's Java functions, as registrations to run on a connection. */
    List<Consumer<Connection>> functions(FileTable table);

    /** A query that may call the table's functions starts, under this signal. */
    void enter(FileTable table, CancelSignal signal);

    void leave(FileTable table, CancelSignal signal);

    /** Waits until no cancelled query is running the table's functions. */
    void awaitClear(FileTable table, CancelSignal signal);

    /** Whether a failure is a function stopped for another query's cancel. */
    boolean stoppedForAnother(Throwable failure);

    static FileTableAccess get() {
        return Holder.access;
    }

    /** Set once, by {@link FileTable}. */
    static void set(FileTableAccess access) {
        if (Holder.access != null) {
            throw new IllegalStateException("Already set");
        }
        Holder.access = access;
    }

    final class Holder {
        private static FileTableAccess access;

        private Holder() {
        }
    }
}
