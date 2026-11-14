package io.quackjvm.core;

/**
 * What {@link Quack#tidy} did to a table.
 *
 * @param filesMerged    small data files rewritten into larger ones
 * @param filesWritten   the files they were rewritten into
 * @param snapshotsRemoved snapshots older than the time kept, removed from the table's history
 * @param filesDeleted   files no remaining snapshot refers to, deleted from storage
 * @param bytesDeleted   their size
 * @param filesKept      files no snapshot refers to but too new to delete: possibly another writer's,
 *                       not yet committed; or in storage this cannot delete from
 */
public record Tidied(long filesMerged, long filesWritten, int snapshotsRemoved, int filesDeleted, long bytesDeleted,
                     int filesKept) {
}
