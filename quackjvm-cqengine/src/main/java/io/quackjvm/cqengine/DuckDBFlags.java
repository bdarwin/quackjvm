package io.quackjvm.cqengine;

import com.googlecode.cqengine.index.sqlite.support.SQLiteIndexFlags;
import com.googlecode.cqengine.query.option.FlagsEnabled;
import com.googlecode.cqengine.query.option.QueryOptions;

/**
 * Flags which can be set in the {@link QueryOptions} of a request to change how DuckDB
 * persistence handles it.
 *
 * <pre>
 * QueryOptions options = new QueryOptions();
 * FlagsEnabled.enableFlags(options, DuckDBFlags.BULK_IMPORT);
 * collection.addAll(millionsOfObjects, options);
 * </pre>
 */
public final class DuckDBFlags {

    /**
     * Tells the persistence that the objects being added are known not to be in the collection
     * already, so it can skip the delete-before-insert step which makes re-adding idempotent.
     *
     * <p>This is the same flag constant CQEngine uses for its SQLite indexes, so code which
     * already sets {@code SQLiteIndexFlags.BULK_IMPORT} keeps working unchanged.</p>
     */
    public static final String BULK_IMPORT = SQLiteIndexFlags.BULK_IMPORT;

    private DuckDBFlags() {
    }

    public static boolean isBulkImport(QueryOptions queryOptions) {
        return FlagsEnabled.isFlagEnabled(queryOptions, BULK_IMPORT);
    }
}
