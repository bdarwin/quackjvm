package io.quackjvm.agent.guard;

import io.quackjvm.core.duckdb.Sql;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** DuckDB's whole error, told apart from the guard's own refusal - issue #10. */
public class GuardedQueryFailedTest {

    private Connection connection;
    private GuardedQuery guard;

    @Before
    public void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        Sql.execute(connection, "CREATE TABLE log (line BIGINT, level VARCHAR, message VARCHAR)");
        guard = GuardedQuery.on(connection, QueryPolicy.readOnly());
    }

    @After
    public void close() throws Exception {
        connection.close();
    }

    private QueryFailed failure(String sql) {
        try {
            guard.run(sql);
            fail("ran: " + sql);
            return null;
        }
        catch (QueryFailed failed) {
            return failed;
        }
    }

    /** The column the ^ marks, on the line after the one that starts with the given text. */
    private static int markedColumn(String message, String lineStart) {
        String[] lines = message.split("\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].startsWith(lineStart)) {
                return lines[i + 1].indexOf('^');
            }
        }
        throw new AssertionError("no line starting " + lineStart + " in:\n" + message);
    }

    @Test
    public void duckDbsWholeMessageComesBackWithTheCallersLineNumbers() {
        QueryFailed failed = failure("SELECT levle FROM log");

        String message = failed.getMessage();
        assertTrue(message, message.contains("Candidate bindings: \"level\""));
        assertTrue(message, message.contains("LINE 1: SELECT levle FROM log"));
        assertFalse(message, message.contains("LINE 2:"));
        assertFalse(message, message.contains("quackjvm_guarded"));
        assertEquals("the ^ is still under levle", "LINE 1: SELECT ".length(), markedColumn(message, "LINE 1:"));

        assertTrue(failed.getCause() instanceof SQLException);
        assertEquals("Binder", failed.getErrorType());
        assertEquals("SELECT levle FROM log", failed.getSql());
    }

    @Test
    public void anErrorFurtherDownSaysWhichOfTheCallersLinesItIsOn() {
        QueryFailed failed = failure("SELECT level,\n       count(*)\nFROM log\nWHERE levle = 'x'\nGROUP BY 1");
        assertTrue(failed.getMessage(), failed.getMessage().contains("LINE 4: WHERE levle = 'x'"));
        assertEquals("LINE 4: WHERE ".length(), markedColumn(failed.getMessage(), "LINE 4:"));
    }

    @Test
    public void theMarkStaysInPlaceWhenTheLineNumberLosesADigit() {
        StringBuilder sql = new StringBuilder("SELECT\n");
        for (int i = 0; i < 8; i++) {
            sql.append("  line,\n");
        }
        sql.append("  levle\nFROM log");   // the tenth line; the wrapper makes it the eleventh
        QueryFailed failed = failure(sql.toString());
        assertTrue(failed.getMessage(), failed.getMessage().contains("LINE 10:   levle"));
        assertEquals("LINE 10:   ".length(), markedColumn(failed.getMessage(), "LINE 10:"));
    }

    @Test
    public void anUnwrappedStatementKeepsItsLineNumbersAsTheyAre() {
        QueryFailed failed = failure("EXPLAIN SELECT levle FROM log");
        assertTrue(failed.getMessage(), failed.getMessage().contains("LINE 1: EXPLAIN SELECT levle FROM log"));
    }

    @Test
    public void aMissingTableIsACatalogError() {
        QueryFailed failed = failure("SELECT * FROM lgo");
        assertEquals("Catalog", failed.getErrorType());
        assertTrue(failed.getMessage(), failed.getMessage().contains("lgo"));
    }

    @Test
    public void theGuardsOwnRefusalIsNotAFailure() {
        try {
            guard.run("DROP TABLE log");
            fail("dropped the table");
        }
        catch (QueryRejected refused) {
            assertEquals(QueryRejected.class, refused.getClass());
        }
    }

    @Test
    public void aFailureIsStillAQueryRejectedForCodeThatCatchesThat() {
        try {
            guard.run("SELECT levle FROM log");
            fail();
        }
        catch (QueryRejected refused) {
            assertTrue(refused instanceof QueryFailed);
            assertNotNull(refused.getCause());
        }
    }
}
