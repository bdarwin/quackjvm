package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBConnection;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.locks.Lock;

/**
 * Connection wrapping helpers.
 *
 * <p>CQEngine hands connections back to the persistence by calling {@link Connection#close()},
 * so a lock held for the duration of a request is released there. The wrapper keeps the
 * underlying {@link DuckDBConnection} reachable, because the Appender API needs the real
 * connection rather than a proxy.</p>
 */
public final class Connections {

    private Connections() {
    }

    /** Implemented by connection proxies, so the underlying connection can be recovered. */
    public interface Unwrappable {
        Connection getDelegate();
    }

    /**
     * Wraps a connection so that closing it returns it to the given pool, and releases the given
     * lock if one was passed. The wrapper is what CQEngine sees, so it is closed exactly once, at
     * the end of the request.
     */
    public static Connection managed(Connection target, ConnectionPool pool, Lock lockToRelease) {
        return (Connection) Proxy.newProxyInstance(
                Connections.class.getClassLoader(),
                new Class<?>[]{Connection.class, Unwrappable.class},
                new ManagedConnectionHandler(target, pool, lockToRelease));
    }

    /** Recovers the underlying DuckDB connection from a possibly-wrapped connection. */
    public static DuckDBConnection duckDB(Connection connection) {
        Connection current = connection;
        for (int i = 0; i < 8; i++) {
            if (current instanceof DuckDBConnection) {
                return (DuckDBConnection) current;
            }
            if (current instanceof Unwrappable) {
                current = ((Unwrappable) current).getDelegate();
                continue;
            }
            try {
                if (current.isWrapperFor(DuckDBConnection.class)) {
                    return current.unwrap(DuckDBConnection.class);
                }
            }
            catch (SQLException ignored) {
                break;
            }
            break;
        }
        throw new IllegalStateException("Expected a DuckDB connection but got: " + connection.getClass().getName());
    }

    private static final class ManagedConnectionHandler implements InvocationHandler {
        private final Connection target;
        private final ConnectionPool pool;
        private final Lock lockToRelease;
        private boolean released;

        ManagedConnectionHandler(Connection target, ConnectionPool pool, Lock lockToRelease) {
            this.target = target;
            this.pool = pool;
            this.lockToRelease = lockToRelease;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Unwrappable.class) {
                return target;
            }
            boolean isClose = "close".equals(method.getName()) && (args == null || args.length == 0);
            if (isClose) {
                if (!released) {
                    released = true;
                    try {
                        pool.release(target);
                    }
                    finally {
                        if (lockToRelease != null) {
                            lockToRelease.unlock();
                        }
                    }
                }
                return null;
            }
            try {
                return method.invoke(target, args);
            }
            catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
