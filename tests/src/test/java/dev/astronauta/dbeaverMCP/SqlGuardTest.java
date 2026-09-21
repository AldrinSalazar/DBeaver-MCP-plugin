package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.db.SqlGuard;
import org.junit.Test;

public class SqlGuardTest {

    @Test
    public void readStatementsPass() throws Exception {
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT * FROM t", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("  -- comment\nWITH x AS (SELECT 1) SELECT * FROM x", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("/* block */ explain select 1", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("VALUES (1), (2)", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SHOW TABLES", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("DESCRIBE my_table", false).kind());
    }

    @Test
    public void semicolonInsideStringIsNotASplit() throws Exception {
        assertEquals(1, SqlGuard.check("SELECT 'a;b' FROM t", false).statements().size());
    }

    @Test
    public void writesRejectedWhenDisabled() {
        assertThrows(BridgeException.class, () -> SqlGuard.check("INSERT INTO t VALUES (1)", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("UPDATE t SET a=1", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("DELETE FROM t", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("DROP TABLE t", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1; SELECT 2", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT ';' FROM t; DELETE FROM t", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("  ", false));
    }

    @Test
    public void writesAllowedWhenEnabled() throws Exception {
        assertEquals(SqlGuard.Kind.WRITE, SqlGuard.check("UPDATE t SET a=1", true).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT 1", true).kind());
    }

    @Test
    public void multipleStatementsAlwaysRejected() {
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1; SELECT 2", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1; SELECT 2", true));
        assertThrows(BridgeException.class, () -> SqlGuard.check("UPDATE t SET a=1; UPDATE t SET a=2", true));
    }

    @Test
    public void hiddenWritesDetected() throws Exception {
        // SELECT ... INTO creates a table
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT * INTO backup FROM t", false));
        assertEquals(SqlGuard.Kind.WRITE, SqlGuard.check("SELECT * INTO backup FROM t", true).kind());
        // Data-modifying CTEs
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("WITH moved AS (DELETE FROM t RETURNING *) SELECT * FROM moved", false));
        assertEquals(SqlGuard.Kind.WRITE,
            SqlGuard.check("WITH moved AS (DELETE FROM t RETURNING *) SELECT * FROM moved", true).kind());
        // EXPLAIN ANALYZE executes the statement
        assertThrows(BridgeException.class, () -> SqlGuard.check("EXPLAIN ANALYZE DELETE FROM t", false));
        assertEquals(SqlGuard.Kind.WRITE, SqlGuard.check("EXPLAIN ANALYZE DELETE FROM t", true).kind());
    }

    @Test
    public void suspiciousWordsInLiteralsStillRead() throws Exception {
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT 'delete me, into the void' FROM t", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("WITH x AS (SELECT 1) SELECT * FROM x", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("EXPLAIN SELECT * FROM t", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT 1 -- delete from t", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT deleted_flag FROM t", false).kind());
    }
}
