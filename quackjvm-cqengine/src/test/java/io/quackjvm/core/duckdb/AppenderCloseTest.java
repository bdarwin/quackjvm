package io.quackjvm.core.duckdb;

import org.duckdb.DuckDBConnection;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DuckDB's Appender, in 1.5.5, reports a failed flush from {@code flush()} but not from
 * {@code close()}: close returns normally and the whole chunk is discarded - the good rows along
 * with the bad one. Anything of ours that closes an appender must flush it explicitly first.
 */
public class AppenderCloseTest {

    @Test
    public void closingAHandleReportsAFailedFlushInsteadOfDroppingTheRows() throws Exception {
        try (DuckDBConnection connection = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            TableWriter writer = new TableWriter("t", List.of(new ColumnDef("k", Integer.class)), false,
                    TableWriter.DEFAULT_APPENDER_THRESHOLD);
            writer.createTable(connection, true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO t VALUES (1)");
            }

            try {
                try (TableWriter.AppenderHandle appender = writer.openAppender(connection)) {
                    appender.appendRow(new Object[]{2});
                    appender.appendRow(new Object[]{1});   // already there
                    appender.appendRow(new Object[]{3});
                }   // close() is the only flush
                fail("a failed flush must be reported by close(), not swallowed");
            }
            catch (IllegalStateException expected) {
                assertTrue(String.valueOf(expected.getCause()).toLowerCase().contains("key"));
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM t")) {
                rows.next();
                assertEquals("the failed chunk is discarded - and now at least someone was told", 1, rows.getLong(1));
            }
        }
    }
}
