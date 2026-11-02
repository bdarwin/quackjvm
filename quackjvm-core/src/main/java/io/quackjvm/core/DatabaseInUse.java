package io.quackjvm.core;

import java.sql.SQLException;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Another process has the database file open, so this one cannot.
 *
 * <p>DuckDB lets one process open a file for writing, or any number open it read-only - never a
 * writer and anyone else. Measured on duckdb_jdbc 2.0.0.0, macOS: while a process holds the file
 * for writing, a second one is refused whether it asks to write or only to read; while a process
 * holds it read-only, a second reader is let in and a writer is refused. Opening the same file
 * twice in one JVM is not this: the driver shares the database between the two.</p>
 *
 * <pre>
 * try {
 *     db = Quack.open(path);
 * }
 * catch (DatabaseInUse e) {
 *     // e.holderPid(): who has it, when DuckDB says
 *     db = Quack.open(somewhereElse);
 * }
 * </pre>
 *
 * <p>It is told apart from every other failure to open - a file that is not a database, a folder
 * that does not exist - by DuckDB's own message, so that callers need not match text themselves.
 * The macOS and Linux message ("Could not set lock on file") is measured. On Windows DuckDB reports
 * the operating system's sharing violation ("being used by another process") instead, which is
 * matched too but has not been measured here.</p>
 */
public class DatabaseInUse extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private static final Pattern PID = Pattern.compile("\\(PID (\\d+)\\)");

    private final String path;
    private final long holderPid;

    DatabaseInUse(String path, long holderPid, SQLException cause) {
        super("Another process has " + path + " open"
                + (holderPid > 0 ? " (PID " + holderPid + ")" : "")
                + ", and DuckDB lets one process write a file or any number read it, never both."
                + " Open another file, wait for it to close, or open it read-only alongside other"
                + " readers only.", cause);
        this.path = path;
        this.holderPid = holderPid;
    }

    /** The file that could not be opened. */
    public String getPath() {
        return path;
    }

    /** The process holding the file, when DuckDB says which; it does on macOS and Linux. */
    public OptionalLong holderPid() {
        return holderPid > 0 ? OptionalLong.of(holderPid) : OptionalLong.empty();
    }

    /** This failure as a DatabaseInUse if that is what it is, or null. */
    static DatabaseInUse from(String path, SQLException e) {
        String message = String.valueOf(e.getMessage());
        if (!message.contains("Could not set lock on file") && !message.contains("being used by another process")) {
            return null;
        }
        Matcher pid = PID.matcher(message);
        return new DatabaseInUse(path, pid.find() ? Long.parseLong(pid.group(1)) : -1, e);
    }
}
