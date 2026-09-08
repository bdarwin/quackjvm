package io.quackjvm.core.arrow;

/**
 * Whether the Arrow columnar read path can be used in this JVM.
 *
 * <p>DuckDB keeps query results as columnar chunks, but the JDBC driver only lets you read them one
 * boxed value at a time: {@code DuckDBVector} is package-private, so every value costs a virtual
 * call and a boxing allocation. Exporting the same result as Arrow hands over the columns whole,
 * which measured roughly seventeen times faster on a million rows of four columns.</p>
 *
 * <p>Arrow is an optional dependency. When it is absent - or when the JVM was started without the
 * module access Arrow needs - everything falls back to the JDBC row path, which is slower but works
 * everywhere. Nothing in quackjvm requires Arrow to be present.</p>
 */
public final class ArrowSupport {

    private static final boolean AVAILABLE;
    private static final String UNAVAILABLE_REASON;

    static {
        boolean available = false;
        String reason = null;
        try {
            Class.forName("org.apache.arrow.memory.RootAllocator");
            Class.forName("org.apache.arrow.c.ArrowArrayStream");
            Class.forName("org.apache.arrow.vector.VectorSchemaRoot");
            available = true;
        }
        catch (Throwable e) {
            reason = "Apache Arrow is not on the classpath (" + e + "). Add org.apache.arrow:arrow-vector, "
                    + "arrow-c-data and arrow-memory-unsafe to use the columnar read path.";
        }
        AVAILABLE = available;
        UNAVAILABLE_REASON = reason;
    }

    private ArrowSupport() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Why Arrow cannot be used, or null when it can. */
    public static String getUnavailableReason() {
        return UNAVAILABLE_REASON;
    }

    /**
     * Arrow needs access to {@code java.nio} internals, which Java 17 and later do not grant by
     * default. Without it Arrow throws when it first allocates.
     *
     * @return the JVM flag an application must add, for use in an error message
     */
    public static String getRequiredJvmFlag() {
        return "--add-opens=java.base/java.nio=ALL-UNNAMED";
    }
}
