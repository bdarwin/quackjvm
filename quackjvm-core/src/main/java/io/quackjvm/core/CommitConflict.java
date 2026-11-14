package io.quackjvm.core;

/**
 * A transaction on an {@link IcebergCatalog}'s table was not committed, because another writer
 * committed to the table after this one read it. Nothing of it was written; running the same work
 * again reads the table as it now is. {@link Quack#retrying} does that.
 */
public final class CommitConflict extends IllegalStateException {

    CommitConflict(String message, Throwable cause) {
        super(message, cause);
    }
}
