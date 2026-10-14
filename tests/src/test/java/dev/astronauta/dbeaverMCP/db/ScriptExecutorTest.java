package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.junit.Test;

public class ScriptExecutorTest {
    /** pgjdbc's connection accessor, which the executor reads reflectively. */
    public interface PgConnection extends Connection {
        Object getTransactionState();
    }

    /** Mirrors pgjdbc's ServerErrorMessage accessors. */
    public static final class ServerErrorMessage {
        private final String severity;
        private final String message;
        private final String detail;
        private final String hint;
        private final int position;

        ServerErrorMessage(String severity, String message, String detail, String hint, int position) {
            this.severity = severity;
            this.message = message;
            this.detail = detail;
            this.hint = hint;
            this.position = position;
        }

        public String getSeverity() { return severity; }
        public String getMessage() { return message; }
        public String getDetail() { return detail; }
        public String getHint() { return hint; }
        public String getWhere() { return "PL/pgSQL function inline_code_block line 1 at RAISE"; }
        public int getPosition() { return position; }
    }

    public static final class PSQLException extends SQLException {
        private final ServerErrorMessage server;

        PSQLException(String sqlState, ServerErrorMessage server) {
            super("ERROR: " + server.getMessage(), sqlState);
            this.server = server;
        }

        public ServerErrorMessage getServerErrorMessage() { return server; }
    }

    public static final class PSQLWarning extends SQLWarning {
        private final ServerErrorMessage server;

        PSQLWarning(String message) {
            super(message, "00000");
            this.server = new ServerErrorMessage("NOTICE", message, null, null, 0);
        }

        public ServerErrorMessage getServerErrorMessage() { return server; }
    }

    private record Outcome(String prefix, long updateCount, List<SQLWarning> warnings, SQLException failure,
            long sleepMillis) {
        static Outcome updates(String prefix, long count) {
            return new Outcome(prefix, count, List.of(), null, 0);
        }

        static Outcome notices(String prefix, String... messages) {
            return new Outcome(prefix, 0, List.of(messages).stream().<SQLWarning>map(PSQLWarning::new).toList(),
                null, 0);
        }

        static Outcome fails(String prefix, SQLException failure, String... notices) {
            return new Outcome(prefix, 0, List.of(notices).stream().<SQLWarning>map(PSQLWarning::new).toList(),
                failure, 0);
        }
    }

    private static final class Database {
        final List<String> executed = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<Outcome> outcomes = new ArrayList<>();
        final List<Integer> timeouts = new ArrayList<>();
        boolean autoCommit;
        boolean reportsTransactionState = true;
        String transactionState = "IDLE";
        String catalog = "qa";
        String standardStrings = "on";
        String product = "PostgreSQL";
        // Answers for other settings queries; a missing entry makes the query fail.
        final Map<String, String> queries = new java.util.HashMap<>();

        final DatabaseMetaData metadata = DbFakes.proxy(DatabaseMetaData.class, (name, args) -> switch (name) {
            case "getDatabaseProductName" -> product;
            default -> DbFakes.unexpected(name);
        });

        Connection jdbc() {
            DbFakes.Calls calls = (name, args) -> switch (name) {
                case "isClosed" -> false;
                case "getMetaData" -> metadata;
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> { autoCommit = (boolean) args[0]; events.add("auto-commit:" + args[0]); yield null; }
                case "getCatalog" -> catalog;
                case "getTransactionState" -> transactionState;
                case "createStatement" -> settingsStatement();
                default -> DbFakes.unexpected(name);
            };
            return reportsTransactionState ? DbFakes.proxy(PgConnection.class, calls)
                : DbFakes.proxy(Connection.class, calls);
        }

        private Statement settingsStatement() {
            return DbFakes.proxy(Statement.class, (name, args) -> switch (name) {
                case "executeQuery" -> {
                    String value = args[0].equals("SHOW standard_conforming_strings") ? standardStrings
                        : queries.get(args[0]);
                    if (value == null) {
                        throw new SQLException("unsupported query: " + args[0]);
                    }
                    boolean[] read = {false};
                    yield DbFakes.proxy(ResultSet.class, (rsName, rsArgs) -> switch (rsName) {
                        case "next" -> !read[0] && (read[0] = true);
                        case "getString" -> value;
                        case "close" -> null;
                        default -> DbFakes.unexpected(rsName);
                    });
                }
                case "close" -> null;
                default -> DbFakes.unexpected(name);
            });
        }

        private DBCStatement statement(String sql) {
            Outcome outcome = outcomes.stream().filter(o -> sql.startsWith(o.prefix())).findFirst()
                .orElse(Outcome.updates("", 0));
            boolean[] consumed = {false};
            return DbFakes.proxy(DBCStatement.class, (name, args) -> switch (name) {
                case "setStatementTimeout" -> { timeouts.add((int) args[0]); yield null; }
                case "executeStatement" -> {
                    assertTrue("scripts run with auto-commit", autoCommit);
                    executed.add(sql);
                    Thread.sleep(outcome.sleepMillis());
                    if (outcome.failure() != null) {
                        if (transactionState.equals("OPEN")) {
                            transactionState = "FAILED";
                        }
                        throw new DBCException("SQL Error", outcome.failure());
                    }
                    switch (sql) {
                        case "BEGIN" -> transactionState = "OPEN";
                        case "COMMIT", "ROLLBACK" -> transactionState = "IDLE";
                        default -> { }
                    }
                    yield false;
                }
                case "getUpdateRowCount" -> consumed[0] ? -1L : outcome.updateCount();
                case "nextResults" -> { consumed[0] = true; yield false; }
                case "getStatementWarnings" -> outcome.warnings().toArray(new Throwable[0]);
                case "close" -> null;
                default -> DbFakes.unexpected(name);
            });
        }

        ScriptExecutor executor(int timeoutSeconds) {
            Connection jdbc = jdbc();
            DBCSession session = DbFakes.proxy(DBCSession.class, (name, args) -> switch (name) {
                case "prepareStatement" -> statement((String) args[1]);
                case "close" -> { events.add("session-close"); yield null; }
                default -> DbFakes.unexpected(name);
            });
            return new ScriptExecutor(purpose -> new IsolatedSession(session, jdbc, null), timeoutSeconds);
        }
    }

    private static final String MIGRATION = """
        -- MORDEV-8268 up
        ALTER TYPE status ADD VALUE IF NOT EXISTS 'graveyard';

        BEGIN;
        DO $$
        BEGIN
          RAISE NOTICE 'MORDEV-8268: brokerages processed %', 3; -- semicolons in bodies stay put
        END
        $$;
        UPDATE brokerage SET status = 'graveyard' WHERE note LIKE '%\\_%';
        COMMIT;
        """;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> report, String key) {
        return (List<Map<String, Object>>) report.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> report) {
        return (Map<String, Object>) report.get("error");
    }

    @Test
    public void runsEachStatementSeparatelyOnAnAutoCommitSession() throws Exception {
        Database database = new Database();
        database.outcomes.add(Outcome.notices("DO", "MORDEV-8268: brokerages processed 3"));
        database.outcomes.add(Outcome.updates("UPDATE", 537));
        Map<String, Object> report = database.executor(0).run(MIGRATION, "qa");

        assertEquals("completed", report.get("status"));
        assertEquals(5, database.executed.size());
        assertTrue(database.executed.get(0).endsWith("ALTER TYPE status ADD VALUE IF NOT EXISTS 'graveyard'"));
        assertEquals("BEGIN", database.executed.get(1));
        assertTrue(database.executed.get(2).startsWith("DO $$") && database.executed.get(2).endsWith("$$"));
        assertEquals("COMMIT", database.executed.get(4));

        List<Map<String, Object>> statements = list(report, "statements");
        assertEquals(List.of("ALTER TYPE", "BEGIN", "DO", "UPDATE", "COMMIT"),
            statements.stream().map(s -> s.get("command")).toList());
        assertEquals(List.of(2, 4, 5, 10, 11), statements.stream().map(s -> s.get("line")).toList());
        assertEquals(537L, statements.get(3).get("updateCount"));
        assertFalse("DDL has no row count", statements.get(0).containsKey("updateCount"));
        assertEquals(Map.of("statement", 3, "severity", "NOTICE", "message", "MORDEV-8268: brokerages processed 3"),
            list(report, "notices").get(0));
        assertEquals("idle", report.get("transactionState"));
        assertNull(report.get("transactionStateInferred"));
        assertNull(report.get("rolledBack"));
        assertEquals("session-close", database.events.getLast());
        assertTrue(database.events.contains("auto-commit:true"));
    }

    @Test
    public void stopsAtTheFirstErrorKeepsItsNoticesAndRollsBack() throws Exception {
        Database database = new Database();
        database.outcomes.add(Outcome.fails("DO", new PSQLException("P0001",
            new ServerErrorMessage("ERROR", "3 brokerages lack a hierarchy", "ids: 4, 5, 9", "Run the backfill", 0)),
            "Cleanup: 12 moved to Graveyard"));
        String script = "BEGIN;\nUPDATE a SET x = 1;\nDO $$ BEGIN PERFORM check(); END $$;\nUPDATE b SET y = 2;\n";
        Map<String, Object> report = database.executor(0).run(script, null);

        assertEquals("failed", report.get("status"));
        assertEquals(2, report.get("executedCount"));
        assertEquals(4, report.get("statementCount"));
        Map<String, Object> error = error(report);
        assertEquals(3, error.get("statement"));
        assertEquals(3, error.get("line"));
        assertEquals("P0001", error.get("sqlState"));
        assertEquals("3 brokerages lack a hierarchy", error.get("message"));
        assertEquals("ids: 4, 5, 9", error.get("detail"));
        assertEquals("Run the backfill", error.get("hint"));
        assertEquals("Cleanup: 12 moved to Graveyard", list(report, "notices").get(0).get("message"));
        assertEquals("failed", report.get("transactionState"));
        assertEquals(true, report.get("rolledBack"));
        assertEquals(List.of("BEGIN", "UPDATE a SET x = 1", "DO $$ BEGIN PERFORM check(); END $$", "ROLLBACK"),
            database.executed);
        assertEquals("IDLE", database.transactionState);
    }

    @Test
    public void mapsServerErrorPositionsToScriptLines() throws Exception {
        Database database = new Database();
        String failing = "UPDATE b\n     SET y = nope";
        database.outcomes.add(Outcome.fails("UPDATE b", new PSQLException("42703",
            new ServerErrorMessage("ERROR", "column \"nope\" does not exist", null, null, failing.indexOf("nope") + 1))));
        Map<String, Object> report = database.executor(0).run("SELECT 1;\n  " + failing + ";\n", null);

        Map<String, Object> error = error(report);
        assertEquals(2, error.get("line"));
        assertEquals(3, error.get("errorLine"));
        assertEquals(14, error.get("errorColumn"));
        assertEquals("UPDATE b SET y = nope", error.get("sql"));
        assertEquals("idle", report.get("transactionState"));
        assertFalse(database.executed.contains("ROLLBACK"));
    }

    @Test
    public void infersAnOpenTransactionWhenTheDriverCannotReportIt() throws Exception {
        Database database = new Database();
        database.reportsTransactionState = false;
        Map<String, Object> report = database.executor(0).run("BEGIN; UPDATE t SET a = 1", null);

        assertEquals("completed", report.get("status"));
        assertEquals("open", report.get("transactionState"));
        assertEquals(true, report.get("transactionStateInferred"));
        assertEquals(true, report.get("rolledBack"));
        assertEquals("ROLLBACK", database.executed.getLast());
    }

    @Test
    public void anonymousBlocksAreNotMistakenForTransactions() throws Exception {
        Database database = new Database();
        database.reportsTransactionState = false;
        Map<String, Object> report = database.executor(0).run("BEGIN NOT ATOMIC SELECT 1 END", null);
        assertEquals("idle", report.get("transactionState"));
        assertNull(report.get("rolledBack"));

        Database isolation = new Database();
        isolation.reportsTransactionState = false;
        report = isolation.executor(0).run("BEGIN ISOLATION LEVEL SERIALIZABLE; SELECT 1", null);
        assertEquals("open", report.get("transactionState"));
        assertEquals(true, report.get("rolledBack"));
    }

    private static Database mysql() {
        Database database = new Database();
        database.product = "MySQL";
        database.reportsTransactionState = false;
        database.queries.put("SELECT @@SESSION.sql_mode", "STRICT_TRANS_TABLES");
        return database;
    }

    @Test
    public void mysqlDdlImplicitlyCommitsTheInferredTransaction() throws Exception {
        Database ddl = mysql();
        Map<String, Object> report = ddl.executor(0).run("BEGIN; INSERT INTO t VALUES (1); CREATE TABLE u (id int);", null);
        assertEquals("idle", report.get("transactionState"));
        assertEquals(true, report.get("transactionStateInferred"));
        assertNull(report.get("rolledBack"));

        Database temporary = mysql();
        report = temporary.executor(0).run("BEGIN; INSERT INTO t VALUES (1); CREATE TEMPORARY TABLE u (id int);", null);
        assertEquals("open", report.get("transactionState"));
        assertEquals(true, report.get("rolledBack"));

        Database failedDdl = mysql();
        failedDdl.outcomes.add(Outcome.fails("CREATE TABLE", new SQLException("Table 'u' already exists", "42S01")));
        report = failedDdl.executor(0).run("BEGIN; INSERT INTO t VALUES (1); CREATE TABLE u (id int);", null);
        assertEquals("unknown", report.get("transactionState"));
        assertEquals(true, report.get("rolledBack"));
    }

    @Test
    public void chainedCommitsKeepTheInferredTransactionOpen() throws Exception {
        Database chained = mysql();
        Map<String, Object> report = chained.executor(0).run(
            "BEGIN; INSERT INTO t VALUES (1); COMMIT AND CHAIN; INSERT INTO t VALUES (2);", null);
        assertEquals("open", report.get("transactionState"));
        assertEquals(true, report.get("rolledBack"));

        Database unchained = mysql();
        report = unchained.executor(0).run("BEGIN; INSERT INTO t VALUES (1); COMMIT AND NO CHAIN;", null);
        assertEquals("idle", report.get("transactionState"));
    }

    @Test
    public void sqliteAndSqlServerBeginFormsOpenTransactions() throws Exception {
        for (String begin : List.of("BEGIN IMMEDIATE", "BEGIN EXCLUSIVE TRANSACTION", "BEGIN DEFERRED", "BEGIN TRAN")) {
            Database database = new Database();
            database.product = "SQLite";
            database.reportsTransactionState = false;
            Map<String, Object> report = database.executor(0).run(begin + "; INSERT INTO t VALUES (1);", null);
            assertEquals(begin, "open", report.get("transactionState"));
            assertEquals(begin, "ROLLBACK", database.executed.getLast());
        }
    }

    private static Map<String, Object> sqlite(String script) throws Exception {
        Database database = new Database();
        database.product = "SQLite";
        database.reportsTransactionState = false;
        return database.executor(0).run(script, null);
    }

    @Test
    public void sqliteSavepointsOpenAndCommitTransactions() throws Exception {
        assertEquals("open", sqlite("SAVEPOINT m; INSERT INTO t VALUES (1);").get("transactionState"));
        assertEquals(true, sqlite("SAVEPOINT m; INSERT INTO t VALUES (1);").get("rolledBack"));
        assertEquals("idle", sqlite("SAVEPOINT m; SAVEPOINT n; INSERT INTO t VALUES (1); RELEASE SAVEPOINT m;")
            .get("transactionState"));
        assertEquals("open", sqlite("SAVEPOINT m; SAVEPOINT n; ROLLBACK TO m; INSERT INTO t VALUES (1);")
            .get("transactionState"));
        assertEquals("idle", sqlite("SAVEPOINT m; SAVEPOINT n; ROLLBACK TRANSACTION TO SAVEPOINT m; RELEASE m;")
            .get("transactionState"));
        assertEquals("open", sqlite("BEGIN; SAVEPOINT a; RELEASE a;").get("transactionState"));
        assertEquals("unknown", sqlite("SAVEPOINT \"m\"; INSERT INTO t VALUES (1);").get("transactionState"));
        assertEquals("idle", sqlite("SAVEPOINT \"m\"; COMMIT;").get("transactionState"));
    }

    @Test
    public void errorPositionsCountCharactersNotUtf16Units() throws Exception {
        Database database = new Database();
        String failing = "SELECT /* 😀😀😀😀 */ 1,\n  nope";
        int position = failing.codePointCount(0, failing.indexOf("nope")) + 1;
        database.outcomes.add(Outcome.fails("SELECT /*", new PSQLException("42703",
            new ServerErrorMessage("ERROR", "column \"nope\" does not exist", null, null, position))));
        Map<String, Object> error = error(database.executor(0).run(failing, null));
        assertEquals(2, error.get("errorLine"));
        assertEquals(3, error.get("errorColumn"));
    }

    @Test
    public void mysqlAnsiQuotesComeFromTheSessionSqlMode() throws Exception {
        Database database = mysql();
        database.queries.put("SELECT @@SESSION.sql_mode", "ANSI_QUOTES, STRICT_TRANS_TABLES");
        database.executor(0).run("SELECT \"dir\\\" FROM t; SELECT 2", null);
        assertEquals(List.of("SELECT \"dir\\\" FROM t", "SELECT 2"), database.executed);
    }

    @Test
    public void mariadbReportsTheServerTransactionState() throws Exception {
        Database database = mysql();
        database.queries.put("SELECT @@in_transaction", "1");
        Map<String, Object> report = database.executor(0).run("CREATE TABLE u (id int)", null);
        assertEquals("open", report.get("transactionState"));
        assertNull(report.get("transactionStateInferred"));
        assertEquals("ROLLBACK", database.executed.getLast());
    }

    @Test
    public void scriptTimeLimitStopsBeforeTheNextStatement() throws Exception {
        Database database = new Database();
        database.outcomes.add(new Outcome("SELECT pg_sleep", 0, List.of(), null, 1100));
        Map<String, Object> report = database.executor(1).run("SELECT pg_sleep(1.1); UPDATE t SET a = 1;", null);

        assertEquals("failed", report.get("status"));
        assertEquals(List.of(1), database.timeouts);
        assertEquals(2, error(report).get("statement"));
        assertTrue((String) error(report).get("message"), ((String) error(report).get("message")).contains("time limit"));
        assertEquals(1, database.executed.size());
    }

    @Test
    public void cancellationAtTheDeadlineNamesTheScriptTimeLimit() throws Exception {
        Database database = new Database();
        database.outcomes.add(new Outcome("SELECT pg_sleep", 0, List.of(),
            new SQLException("canceling statement due to user request", "57014"), 1100));
        Map<String, Object> report = database.executor(1).run("SELECT pg_sleep(5)", null);

        assertEquals("57014", error(report).get("sqlState"));
        assertTrue((String) error(report).get("message"),
            ((String) error(report).get("message")).endsWith("(script time limit of 1 seconds reached)"));
    }

    @Test
    public void safetyChecksRejectBeforeAnythingRuns() {
        Database database = new Database();
        database.catalog = "production";
        BridgeException mismatch = assertThrows(BridgeException.class,
            () -> database.executor(0).run("UPDATE t SET a = 1", "qa"));
        assertTrue(mismatch.getMessage(), mismatch.getMessage().contains("'production', not 'qa'"));
        database.catalog = "QA";
        assertThrows("database names are case-sensitive", BridgeException.class,
            () -> database.executor(0).run("UPDATE t SET a = 1", "qa"));
        assertTrue(database.executed.isEmpty());

        BridgeException host = assertThrows(BridgeException.class,
            () -> ScriptExecutor.requireHost("db.internal.example", "qa.example"));
        assertFalse(host.getMessage().contains("db.internal.example"));
        assertThrows(BridgeException.class, () -> ScriptExecutor.requireHost(" ", "qa.example"));
    }

    @Test
    public void requireHostAcceptsTheConfiguredHostIgnoringCase() throws Exception {
        ScriptExecutor.requireHost("QA.example ", "qa.example");
    }

    @Test
    public void backslashesInLiteralsFollowTheServerSetting() throws Exception {
        String script = "UPDATE t SET a = 'x\\'; DROP TABLE t; --'";
        Database conforming = new Database();
        conforming.executor(0).run(script, null);
        assertEquals(List.of("UPDATE t SET a = 'x\\'", "DROP TABLE t"), conforming.executed);

        Database escaping = new Database();
        escaping.standardStrings = "off";
        escaping.executor(0).run(script, null);
        assertEquals(List.of(script), escaping.executed);

        Database unknown = new Database();
        unknown.standardStrings = null;
        assertThrows(BridgeException.class, () -> unknown.executor(0).run(script, null));
        assertTrue(unknown.executed.isEmpty());
    }

    @Test
    public void beginAtomicRoutineBodiesRunAsOneStatement() throws Exception {
        Database database = new Database();
        String function = """
            CREATE OR REPLACE FUNCTION add_one(x int) RETURNS int LANGUAGE sql
            BEGIN ATOMIC
              SELECT CASE WHEN x > 0 THEN 1 ELSE 0 END;
              SELECT x + 1;
            END""";
        Map<String, Object> report = database.executor(0).run(function + ";\nSELECT add_one(41);", null);

        assertEquals("completed", report.get("status"));
        assertEquals(List.of(function, "SELECT add_one(41)"), database.executed);
        assertEquals("CREATE FUNCTION", list(report, "statements").get(0).get("command"));
    }

    @Test
    public void unterminatedScriptsAreRejectedWithoutExecuting() {
        Database database = new Database();
        assertThrows(BridgeException.class, () -> database.executor(0).run("SELECT 1; DO $$ BEGIN", null));
        assertThrows(BridgeException.class, () -> database.executor(0).run(" \n ", null));
        assertTrue(database.executed.isEmpty());
    }

    @Test
    public void noticesKeepAnOrderedPrefixWithinTheirBudget() throws Exception {
        Database database = new Database();
        String[] messages = new String[500];
        for (int i = 0; i < messages.length; i++) {
            messages[i] = i + ":" + "x".repeat(ScriptExecutor.MAX_TEXT_CHARS);
        }
        database.outcomes.add(Outcome.notices("DO", messages));
        Map<String, Object> report = database.executor(0).run("DO $$ BEGIN END $$; SELECT 1", null);

        List<Map<String, Object>> notices = list(report, "notices");
        int omitted = (int) report.get("omittedNotices");
        assertEquals(500, notices.size() + omitted);
        assertTrue(omitted > 0);
        assertTrue(((String) notices.getLast().get("message")).startsWith((notices.size() - 1) + ":"));
        assertEquals("completed", report.get("status"));
    }

    @Test
    public void commandLabelsFollowPostgresCommandTags() {
        assertEquals("CREATE FUNCTION", ScriptExecutor.command(List.of("CREATE", "OR", "REPLACE", "FUNCTION", "F")));
        assertEquals("CREATE INDEX", ScriptExecutor.command(List.of("CREATE", "UNIQUE", "INDEX", "I", "ON", "T")));
        assertEquals("CREATE MATERIALIZED VIEW", ScriptExecutor.command(List.of("CREATE", "MATERIALIZED", "VIEW", "V")));
        assertEquals("DROP TABLE", ScriptExecutor.command(List.of("DROP", "TABLE", "IF", "EXISTS", "T")));
        assertEquals("START TRANSACTION", ScriptExecutor.command(List.of("START", "TRANSACTION")));
        assertEquals("UPDATE", ScriptExecutor.command(List.of("UPDATE", "T", "SET", "A")));
    }
}
