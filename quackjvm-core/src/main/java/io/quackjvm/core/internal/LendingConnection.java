package io.quackjvm.core.internal;

import io.quackjvm.core.duckdb.ConnectionPool;
import io.quackjvm.core.duckdb.Connections;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Connections the entry point hands to code that keeps a connection for life - a catalog, a guard,
 * a vector search - so that it never holds a real one.
 *
 * <p>{@link #lending} borrows a pooled connection for each statement and returns it when the
 * statement closes. A DuckDB connection runs one query at a time, so threads sharing one queue
 * behind each other: a quick query issued while a one-second query ran on the same connection
 * waited 1,028 ms, and 0 ms on a connection of its own. It refuses to start a transaction: one
 * statement per connection cannot hold one.</p>
 *
 * <p>{@link #bound} is one connection of a transaction the entry point owns, which code inside the
 * transaction may use but not end - closing it does nothing, and committing, rolling back or turning
 * auto-commit back on is refused, since that is the entry point's to do.</p>
 */
public final class LendingConnection {

    private LendingConnection() {
    }

    /** A connection that borrows from {@code pool} for each statement, until {@code closed} says otherwise. */
    public static Connection lending(ConnectionPool pool, BooleanSupplier closed) {
        return (Connection) Proxy.newProxyInstance(LendingConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new Lending(pool, closed));
    }

    /** One transaction's connection, usable until {@code ended} says the transaction is over. */
    public static Connection bound(Connection connection, BooleanSupplier ended) {
        return (Connection) Proxy.newProxyInstance(LendingConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class, Connections.Unwrappable.class}, new Bound(connection, ended));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class Lending implements InvocationHandler {
        private final ConnectionPool pool;
        private final BooleanSupplier closed;

        Lending(ConnectionPool pool, BooleanSupplier closed) {
            this.pool = pool;
            this.closed = closed;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close":
                case "commit":
                case "rollback":
                case "clearWarnings":
                    return null;
                case "isClosed":
                    return closed.getAsBoolean();
                case "isValid":
                    return !closed.getAsBoolean();
                case "getAutoCommit":
                    return true;
                case "setAutoCommit":
                    if (!(Boolean) args[0]) {
                        throw new UnsupportedOperationException("This connection borrows a new one for every"
                                + " statement, so it cannot hold a transaction - use inTransaction(...)");
                    }
                    return null;
                case "createStatement":
                case "prepareStatement":
                case "prepareCall":
                    return borrowFor(proxy, method, args);
                case "isWrapperFor":
                    return false;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "LendingConnection";
                default:
                    break;
            }
            // Anything else - metadata, schema, warnings - is answered by a connection borrowed just for it.
            Connection borrowed = pool.borrow();
            try {
                return LendingConnection.invoke(borrowed, method, args);
            }
            finally {
                pool.release(borrowed);
            }
        }

        private Object borrowFor(Object proxy, Method method, Object[] args) throws Throwable {
            if (closed.getAsBoolean()) {
                throw new SQLException("The database this connection belonged to has been closed");
            }
            Connection borrowed = pool.borrow();
            Statement statement;
            try {
                statement = (Statement) LendingConnection.invoke(borrowed, method, args);
            }
            catch (Throwable e) {
                pool.release(borrowed);
                throw e;
            }
            Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(LendingConnection.class.getClassLoader(), new Class<?>[]{type},
                    new Returning(statement, borrowed, pool, (Connection) proxy));
        }
    }

    /** A statement on a borrowed connection; closing it gives the connection back, once. */
    private static final class Returning implements InvocationHandler {
        private final Statement statement;
        private final Connection borrowed;
        private final ConnectionPool pool;
        private final Connection owner;
        private final AtomicBoolean returned = new AtomicBoolean();

        Returning(Statement statement, Connection borrowed, ConnectionPool pool, Connection owner) {
            this.statement = statement;
            this.borrowed = borrowed;
            this.pool = pool;
            this.owner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close":
                    try {
                        statement.close();
                    }
                    finally {
                        if (returned.compareAndSet(false, true)) {
                            pool.release(borrowed);
                        }
                    }
                    return null;
                case "getConnection":
                    return owner;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return LendingConnection.invoke(statement, method, args);
            }
        }
    }

    private static final class Bound implements InvocationHandler {
        private final Connection connection;
        private final BooleanSupplier ended;

        Bound(Connection connection, BooleanSupplier ended) {
            this.connection = connection;
            this.ended = ended;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "getDelegate":
                    checkOpen();
                    return connection;
                case "close":
                    return null;
                case "isClosed":
                    return ended.getAsBoolean() || connection.isClosed();
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "TransactionConnection";
                case "commit":
                case "rollback":
                case "setSavepoint":
                case "releaseSavepoint":
                    throw new UnsupportedOperationException("The transaction is the entry point's to end:"
                            + " return from the work to commit it, or throw to roll it back");
                case "setAutoCommit":
                    if ((Boolean) args[0]) {
                        throw new UnsupportedOperationException("Turning auto-commit on would commit the"
                                + " transaction from inside it; return from the work instead");
                    }
                    return null;
                default:
                    checkOpen();
                    return LendingConnection.invoke(connection, method, args);
            }
        }

        private void checkOpen() {
            if (ended.getAsBoolean()) {
                throw new IllegalStateException("This transaction has ended; its connection cannot be used now");
            }
        }
    }
}
