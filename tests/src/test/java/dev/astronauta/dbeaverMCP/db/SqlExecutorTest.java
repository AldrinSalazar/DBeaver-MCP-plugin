package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.junit.Test;

public class SqlExecutorTest {
    private static final class Database {
        final List<String> events = new ArrayList<>();
        boolean rows;
        boolean commitFails;
        boolean protectionFails;
        boolean rollbackFails;
        boolean executionFails;
        boolean autoCommit;
        boolean hintFails;
        boolean timeoutFails;
        String product = "PostgreSQL";
        long requestedLimit;

        final DatabaseMetaData metadata = DbFakes.proxy(DatabaseMetaData.class, (name, args) -> switch (name) {
            case "getDatabaseProductName" -> product;
            default -> DbFakes.unexpected(name);
        });
        final Statement nativeStatement = DbFakes.proxy(Statement.class, (name, args) -> {
            if (name.equals("execute")) {
                events.add("native-protection");
                if (protectionFails) {
                    throw new SQLException("native protection failed");
                }
                return false;
            }
            return name.equals("close") ? null : DbFakes.unexpected(name);
        });
        final Connection jdbc = DbFakes.proxy(Connection.class, (name, args) -> switch (name) {
            case "isClosed" -> false;
            case "getMetaData" -> metadata;
            case "getAutoCommit" -> autoCommit;
            case "setReadOnly" -> {
                events.add("read-only:" + args[0]);
                if (hintFails) { throw new SQLException("hint not supported"); }
                yield null;
            }
            case "setAutoCommit" -> { autoCommit = (boolean) args[0]; events.add("auto-commit:" + args[0]); yield null; }
            case "createStatement" -> nativeStatement;
            case "commit" -> {
                events.add("commit");
                if (commitFails) { throw new SQLException("lost connection"); }
                yield null;
            }
            case "rollback" -> {
                events.add("rollback");
                if (rollbackFails) { throw new SQLException("rollback failed"); }
                yield null;
            }
            default -> DbFakes.unexpected(name);
        });
        final DBCStatement statement = DbFakes.proxy(DBCStatement.class, (name, args) -> switch (name) {
            case "setLimit" -> { requestedLimit = (long) args[1]; yield null; }
            case "setStatementTimeout" -> {
                if (timeoutFails) { throw new org.jkiss.dbeaver.model.exec.DBCException("timeout unsupported"); }
                yield null;
            }
            case "executeStatement" -> {
                events.add("execute");
                if (executionFails) { throw new org.jkiss.dbeaver.model.exec.DBCException("execution failed"); }
                yield rows;
            }
            case "openResultSet" -> DbFakes.rows(1, 2, 3);
            case "getUpdateRowCount" -> 1L;
            case "nextResults" -> false;
            case "close" -> { events.add("statement-close"); yield null; }
            default -> DbFakes.unexpected(name);
        });
        final DBCSession session = DbFakes.proxy(DBCSession.class, (name, args) -> switch (name) {
            case "prepareStatement" -> { events.add("prepare"); yield statement; }
            case "close" -> { events.add("session-close"); yield null; }
            default -> DbFakes.unexpected(name);
        });

        SqlExecutor executor() {
            return new SqlExecutor(purpose -> new IsolatedSession(session, jdbc, null), 60);
        }
    }

    @Test
    public void commitsWritesWithAndWithoutReturningRows() throws Exception {
        for (boolean returnsRows : List.of(false, true)) {
            Database database = new Database();
            database.rows = returnsRows;
            String sql = returnsRows ? "INSERT INTO t VALUES (1) RETURNING *" : "INSERT INTO t VALUES (1)";
            var result = database.executor().executeReadWrite(sql);
            assertEquals("committed", result.get("committed"));
            assertTrue(database.events.indexOf("commit") > database.events.indexOf("statement-close"));
            assertEquals("session-close", database.events.getLast());
        }
    }

    @Test
    public void writeAuthorizedSelectsAlsoFinalizeTheirTransaction() throws Exception {
        Database database = new Database();
        database.rows = true;
        assertEquals("committed", database.executor().executeReadWrite("SELECT side_effect()").get("committed"));
        assertTrue(database.events.contains("commit"));
    }

    @Test
    public void commitFailureIsAnErrorAndAttemptsRollback() {
        Database database = new Database();
        database.commitFails = true;
        BridgeException failure = assertThrows(BridgeException.class,
            () -> database.executor().executeReadWrite("UPDATE t SET a=1"));
        assertTrue(failure.getMessage().contains("outcome is unknown"));
        assertTrue(database.events.contains("rollback"));
        assertEquals("session-close", database.events.getLast());
    }

    @Test
    public void executionFailureRollsBackAndNeverCommits() {
        Database database = new Database();
        database.executionFails = true;
        assertThrows(BridgeException.class, () -> database.executor().executeReadWrite("UPDATE t SET a=1"));
        assertTrue(database.events.contains("rollback"));
        assertFalse(database.events.contains("commit"));
    }

    @Test
    public void missingJdbcNeverExecutesProtectedSql() {
        Database database = new Database();
        SqlExecutor executor = new SqlExecutor(purpose -> new IsolatedSession(database.session, null, null), 60);
        assertThrows(BridgeException.class, () -> executor.queryReadOnly("SELECT 1", 100));
        assertFalse(database.events.contains("prepare"));
        assertEquals(List.of("session-close"), database.events);
    }

    @Test
    public void unknownWriteTransactionHandlingNeverExecutesSql() {
        Database database = new Database();
        SqlExecutor executor = new SqlExecutor(purpose -> new IsolatedSession(database.session, null, null), 60);
        assertThrows(BridgeException.class, () -> executor.executeReadWrite("UPDATE t SET a=1"));
        assertFalse(database.events.contains("prepare"));
    }

    @Test
    public void partialProtectionFailureRollsBackWithoutExecutingSql() {
        Database database = new Database();
        database.protectionFails = true;
        assertThrows(BridgeException.class, () -> database.executor().queryReadOnly("SELECT 1", 100));
        assertTrue(database.events.contains("rollback"));
        assertFalse(database.events.contains("prepare"));
        assertEquals("session-close", database.events.getLast());
    }

    @Test
    public void rollbackFailureNeverRestoresAutoCommitOrReportsSuccess() {
        Database database = new Database();
        database.rows = true;
        database.rollbackFails = true;
        assertThrows(BridgeException.class, () -> database.executor().queryReadOnly("SELECT 1", 100));
        assertFalse(database.events.contains("auto-commit:true"));
        assertEquals("session-close", database.events.getLast());
    }

    @Test
    public void readsUseAnExtraRowForTruncationAndAlwaysRollback() throws Exception {
        Database database = new Database();
        database.rows = true;
        var result = database.executor().queryReadOnly("SELECT value FROM t", 2);
        assertEquals(3L, database.requestedLimit);
        assertEquals(2, result.get("rowCount"));
        assertEquals(true, result.get("truncated"));
        assertTrue(database.events.contains("rollback"));
        assertFalse(database.events.contains("commit"));
    }

    @Test
    public void nativeProtectionStillAppliesWhenTheDriverHintIsUnsupported() throws Exception {
        Database database = new Database();
        database.hintFails = true;
        database.rows = true;
        database.executor().queryReadOnly("SELECT 1", 100);
        assertTrue(database.events.indexOf("native-protection") < database.events.indexOf("execute"));
        assertTrue(database.events.contains("rollback"));
    }

    @Test
    public void failedRollbackPreservesTheOriginalCommitFailure() {
        Database database = new Database();
        database.commitFails = true;
        database.rollbackFails = true;
        BridgeException failure = assertThrows(BridgeException.class,
            () -> database.executor().executeReadWrite("UPDATE t SET a=1"));
        assertTrue(failure.getMessage().contains("Commit failed"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("session-close", database.events.getLast());
    }

    @Test
    public void autoCommitWritesHaveAnExplicitOutcomeWithoutAnExtraCommit() throws Exception {
        Database database = new Database();
        database.autoCommit = true;
        assertEquals("auto-commit", database.executor().executeReadWrite("UPDATE t SET a=1").get("committed"));
        assertFalse(database.events.contains("commit"));
    }

    @Test
    public void nonJdbcWritesUseTheIsolatedSessionsTransactionManager() throws Exception {
        Database database = new Database();
        database.rows = true;
        DBCTransactionManager manager = DbFakes.proxy(DBCTransactionManager.class, (name, args) -> switch (name) {
            case "isSupportsTransactions" -> true;
            case "isAutoCommit" -> false;
            case "commit" -> {
                assertSame(database.session, args[0]);
                database.events.add("manager-commit");
                yield null;
            }
            default -> DbFakes.unexpected(name);
        });
        SqlExecutor executor = new SqlExecutor(purpose -> new IsolatedSession(database.session, null, manager), 60);
        assertEquals("committed", executor.executeReadWrite("UPDATE t SET a=1 RETURNING *").get("committed"));
        assertTrue(database.events.contains("manager-commit"));
        assertFalse(database.events.contains("commit"));
    }

    @Test
    public void enabledTimeoutFailureAbortsBeforeExecutionAndRollsBack() {
        Database database = new Database();
        database.timeoutFails = true;
        assertThrows(BridgeException.class, () -> database.executor().queryReadOnly("SELECT 1", 100));
        assertFalse(database.events.contains("execute"));
        assertTrue(database.events.contains("rollback"));
    }

    @Test
    public void genericProtectionRequiresTheDriverReadOnlyHint() {
        Database database = new Database();
        database.product = "Microsoft SQL Server";
        database.hintFails = true;
        assertThrows(BridgeException.class, () -> database.executor().queryReadOnly("SELECT 1", 100));
        assertFalse(database.events.contains("prepare"));
    }

    @Test
    public void jdbcWritesPreferTheSessionOwnedManagerForCommitAndRollback() throws Exception {
        Database database = new Database();
        DBCTransactionManager manager = DbFakes.proxy(DBCTransactionManager.class, (name, args) -> switch (name) {
            case "isSupportsTransactions" -> true;
            case "isAutoCommit" -> false;
            case "commit", "rollback" -> {
                assertSame(database.session, args[0]);
                database.events.add("manager-" + name);
                yield null;
            }
            default -> DbFakes.unexpected(name);
        });
        SqlExecutor executor = new SqlExecutor(purpose -> new IsolatedSession(database.session, database.jdbc, manager), 60);
        assertEquals("committed", executor.executeReadWrite("UPDATE t SET a=1").get("committed"));
        assertTrue(database.events.contains("manager-commit"));
        assertFalse(database.events.contains("commit"));

        database.events.clear();
        database.executionFails = true;
        assertThrows(BridgeException.class, () -> executor.executeReadWrite("UPDATE t SET a=1"));
        assertTrue(database.events.contains("manager-rollback"));
        assertFalse(database.events.contains("rollback"));
    }
}
