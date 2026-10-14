package dev.astronauta.dbeaverMCP.db;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.astronauta.dbeaverMCP.server.Json;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a multi-statement script on one isolated auto-commit session. Each statement is its own
 * round trip, as in psql: a multi-statement string would make PostgreSQL treat everything before
 * the script's own BEGIN as part of that transaction. Stops at the first error and rolls back a
 * transaction the script left open.
 */
public final class ScriptExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(ScriptExecutor.class);

    static final int MAX_STATEMENT_BYTES = 512 * 1024;
    static final int MAX_NOTICE_BYTES = 1536 * 1024;
    static final int MAX_TEXT_CHARS = 4000;
    private static final int PREVIEW_CHARS = 80;
    private static final int ERROR_PREVIEW_CHARS = 200;
    private static final int MAX_RESULTS_PER_STATEMENT = 10_000;
    private static final Set<String> OBJECT_VERBS = Set.of("CREATE", "ALTER", "DROP");
    // Commands whose zero row count is meaningful; others only report a count above zero.
    private static final Set<String> ROW_COMMANDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "COPY");
    private static final String QUERY_CANCELED = "57014";
    // MySQL statements that implicitly commit an open transaction (except on TEMPORARY tables).
    private static final Set<String> IMPLICIT_COMMITS = Set.of("ALTER", "CREATE", "DROP", "RENAME", "TRUNCATE",
        "GRANT", "REVOKE", "LOCK", "ANALYZE", "CHECK", "OPTIMIZE", "REPAIR", "FLUSH", "CACHE", "INSTALL",
        "UNINSTALL", "RESET");
    // Words that may follow a transaction BEGIN (PostgreSQL, MySQL, SQLite, SQL Server); anything else,
    // such as MariaDB's BEGIN NOT ATOMIC, starts a block.
    private static final Set<String> BEGIN_OPTIONS = Set.of("WORK", "TRANSACTION", "ISOLATION", "READ", "DEFERRABLE",
        "NOT", "DEFERRED", "IMMEDIATE", "EXCLUSIVE", "TRAN", "DISTRIBUTED");
    private static final Set<String> OBJECT_MODIFIERS = Set.of(
        "OR", "REPLACE", "UNIQUE", "TEMP", "TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "RECURSIVE",
        "TRUSTED", "PROCEDURAL");
    private static final Set<String> TWO_WORD_OBJECTS = Set.of(
        "MATERIALIZED", "FOREIGN", "DEFAULT", "EVENT", "TEXT", "CONSTRAINT");

    private final SqlExecutor.SessionFactory sessions;
    private final int timeoutSeconds;

    /** @param timeoutSeconds limit for the whole script; 0 disables it. */
    public ScriptExecutor(DBPDataSource dataSource, int timeoutSeconds) {
        this(purpose -> IsolatedSession.open(dataSource, purpose), timeoutSeconds);
    }

    ScriptExecutor(SqlExecutor.SessionFactory sessions, int timeoutSeconds) {
        this.sessions = sessions;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Compares against the configured host without echoing it back. */
    public static void requireHost(String configuredHost, String expectHost) throws BridgeException {
        if (configuredHost == null || configuredHost.isBlank()) {
            throw new BridgeException("The connection has no host to compare with expectHost."
                + " Script was not executed.");
        }
        if (!configuredHost.trim().equalsIgnoreCase(expectHost.trim())) {
            throw new BridgeException("The connection's host does not match expectHost. Script was not executed.");
        }
    }

    /**
     * Returns a report for completed and failed scripts alike ({@code status} tells them apart).
     * Throws only when nothing was executed.
     */
    public Map<String, Object> run(String script, String expectDatabase) throws BridgeException {
        if (script == null || script.isBlank()) {
            throw new BridgeException("SQL text is empty");
        }
        long started = System.nanoTime();
        try (IsolatedSession isolated = sessions.open("MCP execute_script")) {
            Connection jdbc = isolated.requireJdbc("Cannot run a script");
            isolated.enableAutoCommit();
            if (expectDatabase != null) {
                requireDatabase(jdbc, expectDatabase);
            }
            SqlGuard.Dialect dialect = isolated.dialect();
            List<SqlLexer.Statement> statements = SqlLexer.scanScript(script, dialect, quoting(jdbc, dialect));
            if (statements.isEmpty()) {
                throw new BridgeException("SQL text is empty");
            }
            return new Run(isolated, jdbc, dialect, script, statements, started).execute();
        } catch (Exception e) {
            throw SqlExecutor.executionError("Script was not executed", e);
        }
    }

    private static void requireDatabase(Connection jdbc, String expected) throws Exception {
        String actual = jdbc.getCatalog();
        if (actual == null || actual.isBlank()) {
            throw new BridgeException("Cannot determine the current database to compare with expectDatabase."
                + " Script was not executed.");
        }
        // Exact match: PostgreSQL can hold both "qa" and "QA".
        if (!actual.equals(expected.trim())) {
            throw new BridgeException("Connected to database '" + actual + "', not '" + expected.trim()
                + "'. Script was not executed.");
        }
    }

    /** Quote handling depends on server settings; unknown when they cannot be read. */
    private static SqlLexer.Quoting quoting(Connection jdbc, SqlGuard.Dialect dialect) {
        String query = switch (dialect) {
            case POSTGRESQL -> "SHOW standard_conforming_strings";
            case MYSQL -> "SELECT @@SESSION.sql_mode";
            default -> null;
        };
        if (query == null) {
            return SqlLexer.Quoting.UNKNOWN;
        }
        try (Statement statement = jdbc.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            String value = rows.next() ? rows.getString(1) : null;
            if (value == null) {
                return SqlLexer.Quoting.UNKNOWN;
            }
            if (dialect == SqlGuard.Dialect.POSTGRESQL) {
                return new SqlLexer.Quoting(!value.trim().equalsIgnoreCase("on"), null);
            }
            Set<String> modes = Set.of(value.toUpperCase(Locale.ROOT).replace(" ", "").split(","));
            return new SqlLexer.Quoting(!modes.contains("NO_BACKSLASH_ESCAPES"), modes.contains("ANSI_QUOTES"));
        } catch (SQLException e) {
            return SqlLexer.Quoting.UNKNOWN;
        }
    }

    /** Short label for a statement, close to PostgreSQL's command tag. */
    static String command(List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        String verb = words.get(0);
        if (OBJECT_VERBS.contains(verb)) {
            for (int i = 1; i < words.size(); i++) {
                String word = words.get(i);
                if (OBJECT_MODIFIERS.contains(word)) {
                    continue;
                }
                if (TWO_WORD_OBJECTS.contains(word) && i + 1 < words.size()) {
                    return verb + " " + word + " " + words.get(i + 1);
                }
                return verb + " " + word;
            }
        }
        if (verb.equals("START") && words.size() > 1 && words.get(1).equals("TRANSACTION")) {
            return "START TRANSACTION";
        }
        return verb;
    }

    private final class Run {
        private final IsolatedSession isolated;
        private final Connection jdbc;
        private final SqlGuard.Dialect dialect;
        private final String script;
        private final List<SqlLexer.Statement> statements;
        private final long started;
        private final Bounded executed = new Bounded(MAX_STATEMENT_BYTES);
        private final Bounded notices = new Bounded(MAX_NOTICE_BYTES);
        private final List<Integer> lineStarts = new ArrayList<>();
        // Only consulted when neither the driver nor the server reports the transaction state.
        private boolean inTransaction;
        private boolean stateUnknown;
        // SQLite: open savepoints, and whether the outermost one started the transaction.
        private final List<String> savepoints = new ArrayList<>();
        private boolean savepointTransaction;

        Run(IsolatedSession isolated, Connection jdbc, SqlGuard.Dialect dialect, String script,
                List<SqlLexer.Statement> statements, long started) {
            this.isolated = isolated;
            this.jdbc = jdbc;
            this.dialect = dialect;
            this.script = script;
            this.statements = statements;
            this.started = started;
            lineStarts.add(0);
            for (int i = 0; i < script.length(); i++) {
                if (script.charAt(i) == '\n') {
                    lineStarts.add(i + 1);
                }
            }
        }

        Map<String, Object> execute() {
            Map<String, Object> error = null;
            int executedCount = 0;
            for (int i = 0; i < statements.size() && error == null; i++) {
                error = executeOne(i + 1, statements.get(i));
                if (error == null) {
                    executedCount++;
                    track(statements.get(i).words());
                } else if (inTransaction && implicitlyCommits(statements.get(i).words())) {
                    // MySQL commits before running DDL, so a failed one may or may not have committed.
                    stateUnknown = true;
                }
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", error == null ? "completed" : "failed");
            report.put("statementCount", statements.size());
            report.put("executedCount", executedCount);
            executed.putInto(report, "statements", "omittedStatements");
            notices.putInto(report, "notices", "omittedNotices");
            if (error != null) {
                report.put("error", error);
            }
            finishTransaction(report);
            report.put("elapsedMs", millisSince(started));
            return report;
        }

        /** Returns the error description, or null after recording a successful statement. */
        private Map<String, Object> executeOne(int index, SqlLexer.Statement statement) {
            Integer timeout = remainingSeconds();
            if (timeout != null && timeout <= 0) {
                return error(index, statement, "Script time limit of " + timeoutSeconds
                    + " seconds reached; this statement was not executed.");
            }
            long statementStarted = System.nanoTime();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index);
            entry.put("line", line(statement.contentStart()));
            putIfPresent(entry, "command", command(statement.words()));
            entry.put("sql", preview(body(statement), PREVIEW_CHARS));
            DBCStatement dbc = null;
            try {
                dbc = isolated.session().prepareStatement(
                    DBCStatementType.SCRIPT, statement.text(), false, false, false);
                if (timeout != null) {
                    dbc.setStatementTimeout(timeout);
                }
                readResults(dbc, dbc.executeStatement(), entry, statement.words());
            } catch (Exception e) {
                return failure(index, statement, e, timeout != null);
            } finally {
                if (dbc != null) {
                    // Notices raised before a failure are often the explanation, so read them either way.
                    collectNotices(dbc, index);
                    closeQuietly(dbc);
                }
            }
            entry.put("elapsedMs", millisSince(statementStarted));
            executed.add(entry);
            return null;
        }

        private void readResults(DBCStatement dbc, boolean hasRows, Map<String, Object> entry, List<String> words)
                throws Exception {
            long updateCount = -1;
            int resultSets = 0;
            for (int i = 0; i < MAX_RESULTS_PER_STATEMENT; i++) {
                if (hasRows) {
                    resultSets++;
                } else {
                    long count = dbc.getUpdateRowCount();
                    if (count < 0) {
                        break;
                    }
                    updateCount = Math.max(updateCount, 0) + count;
                }
                hasRows = dbc.nextResults();
            }
            if (updateCount > 0 || (updateCount == 0 && !words.isEmpty() && ROW_COMMANDS.contains(words.get(0)))) {
                entry.put("updateCount", updateCount);
            }
            if (resultSets > 0) {
                entry.put("resultSets", resultSets);
            }
        }

        private void collectNotices(DBCStatement dbc, int index) {
            try {
                Throwable[] warnings = dbc.getStatementWarnings();
                if (warnings == null) {
                    return;
                }
                for (Throwable warning : warnings) {
                    Map<String, Object> notice = new LinkedHashMap<>();
                    notice.put("statement", index);
                    ServerMessage server = ServerMessage.of(warning);
                    putIfPresent(notice, "severity", server.severity());
                    if (warning instanceof SQLWarning sql && sql.getSQLState() != null
                            && !sql.getSQLState().equals("00000")) {
                        notice.put("sqlState", sql.getSQLState());
                    }
                    notice.put("message", truncate(server.message() != null ? server.message() : warning.getMessage()));
                    putIfPresent(notice, "detail", truncate(server.detail()));
                    putIfPresent(notice, "hint", truncate(server.hint()));
                    notices.add(notice);
                }
            } catch (Exception e) {
                LOG.warn("Could not read script statement warnings", e);
            }
        }

        private Map<String, Object> failure(int index, SqlLexer.Statement statement, Exception failure,
                boolean limited) {
            SQLException sql = sqlException(failure);
            ServerMessage server = ServerMessage.of(sql);
            String message = server.message();
            if (message == null) {
                Throwable source = sql != null ? sql : failure;
                message = source.getMessage() != null ? source.getMessage() : source.getClass().getSimpleName();
            }
            if (limited && sql != null && QUERY_CANCELED.equals(sql.getSQLState()) && remainingSeconds() == 0) {
                message += " (script time limit of " + timeoutSeconds + " seconds reached)";
            }
            Map<String, Object> error = error(index, statement, message);
            if (sql != null && sql.getSQLState() != null) {
                error.put("sqlState", sql.getSQLState());
            }
            putIfPresent(error, "detail", truncate(server.detail()));
            putIfPresent(error, "hint", truncate(server.hint()));
            putIfPresent(error, "where", truncate(server.where()));
            String text = statement.text();
            // PostgreSQL counts characters; Java strings count UTF-16 units.
            if (server.position() > 0 && server.position() <= text.codePointCount(0, text.length())) {
                int offset = statement.start() + text.offsetByCodePoints(0, server.position() - 1);
                error.put("errorLine", line(offset));
                error.put("errorColumn", column(offset));
            }
            return error;
        }

        private Map<String, Object> error(int index, SqlLexer.Statement statement, String message) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("statement", index);
            error.put("line", line(statement.contentStart()));
            putIfPresent(error, "command", command(statement.words()));
            error.put("message", truncate(message));
            error.put("sql", preview(body(statement), ERROR_PREVIEW_CHARS));
            return error;
        }

        /** Reports the state the script left, then never lets an open transaction outlive the call. */
        private void finishTransaction(Map<String, Object> report) {
            String reported = reportedTransactionState();
            String state = reported != null ? reported : stateUnknown ? "unknown" : inTransaction ? "open" : "idle";
            report.put("transactionState", state);
            if (reported == null) {
                report.put("transactionStateInferred", true);
            }
            if (state.equals("idle")) {
                return;
            }
            DBCStatement rollback = null;
            try {
                rollback = isolated.session().prepareStatement(DBCStatementType.SCRIPT, "ROLLBACK", false, false, false);
                rollback.executeStatement();
                report.put("rolledBack", true);
            } catch (Exception e) {
                // The isolated session is closed next, which ends the transaction on the server.
                LOG.warn("MCP script rollback failed", e);
                report.put("rolledBack", false);
                report.put("rollbackError", truncate(e.getMessage()));
            } finally {
                closeQuietly(rollback);
            }
        }

        /**
         * PostgreSQL's driver tracks the server's idle/open/failed state and MariaDB exposes
         * {@code @@in_transaction}; for other databases the state is inferred.
         */
        private String reportedTransactionState() {
            Object state = Reflection.call(jdbc, "getTransactionState");
            if (state != null) {
                String id = state.toString().toLowerCase(Locale.ROOT);
                return Set.of("idle", "open", "failed").contains(id) ? id : null;
            }
            if (dialect != SqlGuard.Dialect.MYSQL) {
                return null;
            }
            try (Statement statement = jdbc.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT @@in_transaction")) {
                String value = rows.next() ? rows.getString(1) : null;
                return value == null ? null : value.trim().equals("1") ? "open" : "idle";
            } catch (SQLException e) {
                return null;
            }
        }

        private void track(List<String> words) {
            if (words.isEmpty()) {
                return;
            }
            String verb = words.get(0);
            if (dialect == SqlGuard.Dialect.SQLITE && trackSavepoint(verb, words)) {
                return;
            }
            boolean transactionBegin = verb.equals("BEGIN")
                && (words.size() == 1 || (BEGIN_OPTIONS.contains(words.get(1)) && !isNotAtomic(words)));
            if (transactionBegin || command(words).equals("START TRANSACTION")) {
                inTransaction = true;
                endSavepoints();
            } else if (verb.equals("COMMIT") || verb.equals("END") || verb.equals("ABORT")
                    || (verb.equals("ROLLBACK") && !words.contains("TO"))) {
                // AND CHAIN starts the next transaction straight away; AND NO CHAIN does not.
                inTransaction = words.contains("CHAIN") && !words.contains("NO");
                stateUnknown = false;
                endSavepoints();
            } else if (implicitlyCommits(words)) {
                inTransaction = false;
            }
        }

        /**
         * SQLite opens a transaction for an outermost SAVEPOINT, and releasing that savepoint commits it.
         * Returns false for statements that are not savepoint commands.
         */
        private boolean trackSavepoint(String verb, List<String> words) {
            boolean rollbackTo = verb.equals("ROLLBACK") && words.contains("TO");
            if (!verb.equals("SAVEPOINT") && !verb.equals("RELEASE") && !rollbackTo) {
                return false;
            }
            String name = words.size() > 1 ? words.getLast() : null;
            if (name == null || name.equals("SAVEPOINT") || name.equals("TO")) {
                // Quoted names are not words, so the savepoint cannot be identified.
                stateUnknown = true;
                return true;
            }
            if (verb.equals("SAVEPOINT")) {
                savepointTransaction |= !inTransaction;
                inTransaction = true;
                savepoints.add(name);
                return true;
            }
            int index = savepoints.lastIndexOf(name);
            if (index < 0) {
                stateUnknown = true;
                return true;
            }
            // RELEASE removes the savepoint and newer ones; ROLLBACK TO keeps it open.
            savepoints.subList(rollbackTo ? index + 1 : index, savepoints.size()).clear();
            if (!rollbackTo && savepoints.isEmpty() && savepointTransaction) {
                inTransaction = false;
                savepointTransaction = false;
            }
            return true;
        }

        private void endSavepoints() {
            savepoints.clear();
            savepointTransaction = false;
        }

        private boolean implicitlyCommits(List<String> words) {
            return dialect == SqlGuard.Dialect.MYSQL && !words.isEmpty() && IMPLICIT_COMMITS.contains(words.get(0))
                && !(words.size() > 1 && words.get(1).equals("TEMPORARY"));
        }

        private boolean isNotAtomic(List<String> words) {
            return words.size() > 2 && words.get(1).equals("NOT") && words.get(2).equals("ATOMIC");
        }

        private Integer remainingSeconds() {
            if (timeoutSeconds <= 0) {
                return null;
            }
            long remaining = TimeUnit.SECONDS.toNanos(timeoutSeconds) - (System.nanoTime() - started);
            // Round up so a cancelled statement means the script deadline has passed.
            return remaining <= 0 ? 0 : (int) TimeUnit.NANOSECONDS.toSeconds(remaining + TimeUnit.SECONDS.toNanos(1) - 1);
        }

        /** Statement text without the comments that precede its first token. */
        private String body(SqlLexer.Statement statement) {
            return statement.text().substring(statement.contentStart() - statement.start());
        }

        private int line(int offset) {
            int index = Collections.binarySearch(lineStarts, offset);
            return index >= 0 ? index + 1 : -index - 1;
        }

        private int column(int offset) {
            return script.codePointCount(lineStarts.get(line(offset) - 1), offset) + 1;
        }
    }

    /** Keeps an ordered prefix of entries within a serialized size budget. */
    private static final class Bounded {
        private final List<Map<String, Object>> items = new ArrayList<>();
        private final long maxBytes;
        private long bytes;
        private int omitted;

        Bounded(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        void add(Map<String, Object> item) {
            long size = Json.stringify(item).getBytes(StandardCharsets.UTF_8).length + 1L;
            if (omitted > 0 || bytes + size > maxBytes) {
                omitted++;
                return;
            }
            bytes += size;
            items.add(item);
        }

        void putInto(Map<String, Object> report, String key, String omittedKey) {
            report.put(key, items);
            if (omitted > 0) {
                report.put(omittedKey, omitted);
            }
        }
    }

    /** Structured server fields from PostgreSQL's driver, read reflectively; empty for other drivers. */
    private record ServerMessage(String severity, String message, String detail, String hint, String where,
            int position) {
        static ServerMessage of(Throwable error) {
            Object server = error == null ? null : Reflection.call(error, "getServerErrorMessage");
            if (server == null) {
                return new ServerMessage(null, null, null, null, null, 0);
            }
            return new ServerMessage(
                Reflection.text(server, "getSeverity"),
                Reflection.text(server, "getMessage"),
                Reflection.text(server, "getDetail"),
                Reflection.text(server, "getHint"),
                Reflection.text(server, "getWhere"),
                Reflection.call(server, "getPosition") instanceof Integer position ? position : 0);
        }
    }

    private static final class Reflection {
        private Reflection() {
        }

        static Object call(Object target, String method) {
            try {
                return target.getClass().getMethod(method).invoke(target);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static String text(Object target, String method) {
            Object value = call(target, method);
            return value == null || value.toString().isBlank() ? null : value.toString();
        }
    }

    private static SQLException sqlException(Throwable error) {
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    private static String preview(String text, int maxChars) {
        String head = text.length() > maxChars * 4 ? text.substring(0, maxChars * 4) : text;
        String collapsed = head.replaceAll("\\s+", " ").trim();
        return collapsed.length() > maxChars || head.length() < text.length()
            ? collapsed.substring(0, Math.min(collapsed.length(), maxChars)) + "..." : collapsed;
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS) + "...";
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static long millisSince(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void closeQuietly(DBCStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (Exception e) {
            LOG.warn("Failed to close script statement", e);
        }
    }
}
