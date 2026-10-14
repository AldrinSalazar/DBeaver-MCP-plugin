package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.util.List;

import dev.astronauta.dbeaverMCP.db.SqlGuard.Dialect;
import org.junit.Test;

/** Script-only splitting rules: BEGIN ATOMIC bodies, DELIMITER lines, and server backslash settings. */
public class SqlLexerScriptTest {
    private static List<String> split(String sql, Dialect dialect, Boolean backslashEscapes) throws BridgeException {
        return split(sql, dialect, new SqlLexer.Quoting(backslashEscapes, false));
    }

    private static List<String> split(String sql, Dialect dialect, SqlLexer.Quoting quoting) throws BridgeException {
        return SqlLexer.scanScript(sql, dialect, quoting).stream().map(SqlLexer.Statement::text).toList();
    }

    @Test
    public void beginAtomicBodiesKeepTheirSemicolons() throws Exception {
        String procedure = """
            CREATE PROCEDURE log_it(msg text) LANGUAGE sql
            BEGIN ATOMIC
              INSERT INTO log VALUES (msg);
              SELECT CASE WHEN msg = '' THEN 'empty' ELSE CASE WHEN true THEN 'x' END END;
            END""";
        assertEquals(List.of(procedure, "CALL log_it('a')"),
            split(procedure + ";\nCALL log_it('a');", Dialect.POSTGRESQL, false));
    }

    @Test
    public void beginOnlyOpensABodyOutsideParenthesesOfRoutineDefinitions() throws Exception {
        assertEquals(2, split("CREATE FUNCTION f(begin int) RETURNS int LANGUAGE sql RETURN 1; SELECT 2",
            Dialect.POSTGRESQL, false).size());
        assertEquals(List.of("BEGIN", "SELECT CASE WHEN true THEN 1 END", "COMMIT"),
            split("BEGIN; SELECT CASE WHEN true THEN 1 END; COMMIT;", Dialect.POSTGRESQL, false));
        assertEquals(2, split("CREATE FUNCTION f() RETURNS int LANGUAGE sql RETURN CASE WHEN true THEN 1 END; SELECT 2",
            Dialect.POSTGRESQL, false).size());
    }

    @Test
    public void qualifiedNamesAreNotBlockKeywords() throws Exception {
        String function = """
            CREATE FUNCTION f() RETURNS SETOF t LANGUAGE sql
            BEGIN ATOMIC
              SELECT t.end FROM t;
              SELECT t . begin, t.case FROM t;
              SELECT t. /* comment */ end, t.-- comment
                end FROM t;
            END""";
        assertEquals(List.of(function, "SELECT 1"), split(function + "; SELECT 1", Dialect.POSTGRESQL, false));
    }

    @Test
    public void changingTheBackslashSettingMakesLaterBackslashesAmbiguous() throws Exception {
        assertEquals(2, split("SET standard_conforming_strings = off; SELECT 'no backslash'",
            Dialect.POSTGRESQL, false).size());
        for (String change : List.of("SET standard_conforming_strings = off", "SET LOCAL standard_conforming_strings TO on",
                "RESET standard_conforming_strings", "RESET ALL")) {
            BridgeException error = assertThrows(change, BridgeException.class,
                () -> split(change + "; SELECT 'it\\'s'", Dialect.POSTGRESQL, false));
            assertTrue(error.getMessage(), error.getMessage().contains("changed earlier in the script"));
        }
        assertEquals(2, split("SELECT 'a\\b'; SET standard_conforming_strings = off", Dialect.POSTGRESQL, false).size());
        for (String change : List.of("SET \"standard_conforming_strings\" = off",
                "SELECT set_config('standard_conforming_strings', 'off', false)")) {
            assertThrows(change, BridgeException.class,
                () -> split(change + "; SELECT 'it\\'s'", Dialect.POSTGRESQL, false));
        }
        assertThrows(BridgeException.class,
            () -> split("SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES'; SELECT 'a\\'", Dialect.MYSQL, true));
    }

    @Test
    public void onlyBeginAtomicOpensABodyAndLabelsAreNotKeywords() throws Exception {
        String function = """
            CREATE FUNCTION f() RETURNS TABLE (a int, b int) LANGUAGE sql
            BEGIN ATOMIC
              SELECT 1 AS end, 2 AS case;
            END""";
        assertEquals(List.of(function, "SELECT 1"), split(function + "; SELECT 1", Dialect.POSTGRESQL, false));
        assertEquals(List.of("CREATE FUNCTION begin() RETURNS int LANGUAGE sql RETURN 1", "SELECT 2"),
            split("CREATE FUNCTION begin() RETURNS int LANGUAGE sql RETURN 1; SELECT 2", Dialect.POSTGRESQL, false));
    }

    @Test
    public void mysqlAnsiQuotesMakeDoubleQuotesIdentifiers() throws Exception {
        String identifier = "SELECT \"dir\\\" FROM t; SELECT 2";
        assertEquals(List.of("SELECT \"dir\\\" FROM t", "SELECT 2"),
            split(identifier, Dialect.MYSQL, new SqlLexer.Quoting(true, true)));
        String string = "SELECT \"it\\\"s\"; SELECT 2";
        assertEquals(List.of("SELECT \"it\\\"s\"", "SELECT 2"),
            split(string, Dialect.MYSQL, new SqlLexer.Quoting(true, false)));
        assertThrows(BridgeException.class, () -> split(identifier, Dialect.MYSQL, new SqlLexer.Quoting(true, null)));
        assertEquals(2, split(identifier, Dialect.MYSQL, new SqlLexer.Quoting(false, null)).size());
        assertThrows(BridgeException.class, () -> split("SET sql_mode = 'ANSI_QUOTES'; " + identifier,
            Dialect.MYSQL, new SqlLexer.Quoting(true, true)));
    }

    @Test
    public void unterminatedBeginAtomicRejectsTheScript() {
        BridgeException error = assertThrows(BridgeException.class, () -> split(
            "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; DROP TABLE t;",
            Dialect.POSTGRESQL, false));
        assertTrue(error.getMessage(), error.getMessage().contains("BEGIN ATOMIC"));
    }

    @Test
    public void singleStatementValidationKeepsSplittingAtomicBodies() {
        assertThrows(BridgeException.class, () -> SqlGuard.check(
            "CREATE FUNCTION f() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT 1; END", true, Dialect.POSTGRESQL));
    }

    @Test
    public void mysqlDelimiterLinesChangeTheStatementSeparator() throws Exception {
        String script = """
            DROP PROCEDURE IF EXISTS p;
            DELIMITER //
            CREATE PROCEDURE p()
            BEGIN
              SELECT 'a//b'; -- // in a comment
              SELECT 2;
            END//
            delimiter $$
            CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW BEGIN SET NEW.a = 1; END$$
            DELIMITER ;
            CALL p();
            """;
        List<SqlLexer.Statement> statements = SqlLexer.scanScript(script, Dialect.MYSQL, new SqlLexer.Quoting(true, false));
        List<String> texts = statements.stream().map(SqlLexer.Statement::text).toList();
        assertEquals(4, texts.size());
        assertEquals("DROP PROCEDURE IF EXISTS p", texts.get(0));
        assertTrue(texts.get(1), texts.get(1).startsWith("CREATE PROCEDURE p()") && texts.get(1).endsWith("END"));
        assertTrue(texts.get(1).contains("SELECT 'a//b';"));
        assertEquals("CREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW BEGIN SET NEW.a = 1; END", texts.get(2));
        assertEquals(List.of("CREATE", "TRIGGER", "T", "BEFORE", "INSERT", "ON", "X", "FOR", "EACH", "ROW", "BEGIN",
            "SET", "NEW", "A", "END"), statements.get(2).words());
        assertEquals("CALL p()", texts.get(3));
        assertEquals(script.indexOf("CREATE PROCEDURE"), statements.get(1).contentStart());
    }

    @Test
    public void delimiterIsOnlyACommandAtTheStartOfAMysqlScriptStatement() throws Exception {
        assertEquals(List.of("SELECT delimiter FROM t", "SELECT 2"),
            split("SELECT delimiter FROM t; SELECT 2;", Dialect.MYSQL, true));
        assertThrows(BridgeException.class, () -> split("DELIMITER \nSELECT 1", Dialect.MYSQL, true));
        assertEquals(2, split("DELIMITER //\nSELECT 1; SELECT 2", Dialect.POSTGRESQL, false).size());
        assertThrows(BridgeException.class, () -> SqlGuard.check("DELIMITER //\nSELECT 1; SET @a = 1//", true, Dialect.MYSQL));
    }

    @Test
    public void mysqlBackslashEscapesFollowSqlMode() throws Exception {
        String sql = "INSERT INTO t VALUES ('a\\'; b'); SELECT 2";
        assertEquals(List.of("INSERT INTO t VALUES ('a\\'; b')", "SELECT 2"), split(sql, Dialect.MYSQL, true));
        assertThrows(BridgeException.class, () -> split(sql, Dialect.MYSQL, (Boolean) null));
        String literal = "INSERT INTO t VALUES ('a\\'); SELECT 2";
        assertEquals(List.of("INSERT INTO t VALUES ('a\\')", "SELECT 2"), split(literal, Dialect.MYSQL, false));
        assertThrows(BridgeException.class, () -> split(literal, Dialect.MYSQL, true));
    }
}
