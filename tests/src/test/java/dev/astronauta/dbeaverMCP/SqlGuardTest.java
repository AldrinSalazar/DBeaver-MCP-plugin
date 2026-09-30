package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.db.SqlGuard;
import dev.astronauta.dbeaverMCP.db.SqlGuard.Dialect;
import java.util.Locale;
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

    @Test
    public void trailingCommentsDoNotBecomeStatements() throws Exception {
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT 1; -- end", false).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT 1; /* end */", false).kind());
        assertThrows(BridgeException.class, () -> SqlGuard.check("/* comment only */", false));
    }

    @Test
    public void dialectQuotingPreservesStatementBoundaries() throws Exception {
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT $$a;b$$", false, Dialect.POSTGRESQL).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT $tag$insert;delete$tag$", false, Dialect.POSTGRESQL).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT [into;delete] FROM t", false, Dialect.SQL_SERVER).kind());
        assertEquals(SqlGuard.Kind.READ, SqlGuard.check("SELECT `into;delete` FROM t", false, Dialect.MYSQL).kind());
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("SELECT $$a;b$$; DELETE FROM t", true, Dialect.POSTGRESQL));
    }

    @Test
    public void modifyingReadsAndExecutableCommentsAreRejected() {
        assertThrows(BridgeException.class, () -> SqlGuard.check("EXPLAIN ANALYZE SELECT * INTO backup FROM t", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("WITH x AS (SELECT 1) SELECT * INTO backup FROM x", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1 /*!; DELETE FROM t */", true, Dialect.MYSQL));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1--1; DELETE FROM t", true, Dialect.MYSQL));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1--\u2000x; DELETE FROM t", true, Dialect.MYSQL));
    }

    @Test
    public void keywordChecksDoNotDependOnTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThrows(BridgeException.class,
                () -> SqlGuard.check("with x as (insert into t values (1) returning *) select * from x", false));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void malformedQuotesAndCommentsFailClosed() {
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 'unterminated", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT 1 /* unterminated", false));
        assertThrows(BridgeException.class, () -> SqlGuard.check("SELECT $$unterminated", false, Dialect.POSTGRESQL));
    }

    @Test
    public void modeDependentBackslashQuotesFailClosed() throws Exception {
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("SELECT 'a\\b'", true, Dialect.MYSQL));
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("SELECT \"a\\b\"", true, Dialect.MYSQL));
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("SELECT 'a\\b'", true, Dialect.POSTGRESQL));
        assertEquals(SqlGuard.Kind.READ,
            SqlGuard.check("SELECT E'a\\b'", false, Dialect.POSTGRESQL).kind());
        assertEquals(SqlGuard.Kind.READ,
            SqlGuard.check("SELECT 'a''b'", false, Dialect.MYSQL).kind());
    }

    @Test
    public void mariaDbExecutableCommentsAreRejected() {
        assertThrows(BridgeException.class,
            () -> SqlGuard.check("SELECT 1 /*M!; DELETE FROM t */", true, Dialect.MYSQL));
    }
}
