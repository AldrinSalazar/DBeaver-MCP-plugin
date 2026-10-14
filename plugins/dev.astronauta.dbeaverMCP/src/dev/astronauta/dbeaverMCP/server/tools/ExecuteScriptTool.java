package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class ExecuteScriptTool implements McpTool {
    private static final int MAX_TIMEOUT_SECONDS = 3600;

    @Override
    public String name() {
        return "execute_script";
    }

    @Override
    public String description() {
        return """
            Runs a multi-statement SQL script, such as a migration file, in one call on a dedicated \
            auto-commit session separate from DBeaver's editors. Only exposed in read/write mode.

            How it runs: the whole script is parsed first, then statements are sent one at a time, \
            like psql -f. The script's own BEGIN/COMMIT control its transactions; statements outside \
            them commit immediately. Statements are split on semicolons outside quotes, comments and \
            PostgreSQL dollar-quoted bodies; PostgreSQL BEGIN ATOMIC routine bodies and MySQL \
            DELIMITER lines are handled. Execution stops at the first error, then any transaction \
            still open is rolled back, including one the script never committed.

            Returns each statement's command, line and row count, every server notice \
            (RAISE NOTICE) in order, the error with SQLSTATE, detail, hint and script line, and the \
            final transaction state. A failed script is reported as an error with the same details. \
            transactionState comes from the server on PostgreSQL and MariaDB; elsewhere it is inferred \
            from the statements (transactionStateInferred) and can be "unknown". rolledBack means a \
            ROLLBACK was issued.

            Limitations:
            - Statements that ran before an error outside an explicit transaction stay committed. \
            Wrap the script in BEGIN/COMMIT if it must be all-or-nothing.
            - Client-side commands are not SQL and fail with a syntax error where they appear: psql \
            meta-commands (\\set, \\i, \\copy, :variables), COPY ... FROM STDIN, SQL Server GO, \
            and Oracle SQL*Plus commands or "/" terminators.
            - Other procedural blocks with bare semicolons, such as Oracle PL/SQL or SQL Server \
            BEGIN ... END blocks, are split incorrectly.
            - Unterminated quotes, comments or BEGIN ATOMIC blocks reject the script before \
            anything runs. So do backslashes in quoted values whose meaning depends on server \
            settings (standard_conforming_strings, NO_BACKSLASH_ESCAPES, ANSI_QUOTES) that cannot be \
            read or that the script changes before them.
            - Result set rows are not returned; use query_sql. The JDBC driver still reads every \
            row of each SELECT, RETURNING or CALL result into DBeaver's memory, as psql does, so \
            avoid statements that return very large results.
            - There is no dry-run mode, and migration tools' history tables (for example \
            knex_migrations) are not updated.
            - timeoutSeconds limits the whole script. Very long statement or notice lists are \
            truncated and counted in omittedStatements / omittedNotices.
            """;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("sql", Schema.string("Full script text"))
            .property("timeoutSeconds", Schema.integer(
                "Time limit for the whole script. Defaults to the query timeout in preferences.",
                1, MAX_TIMEOUT_SECONDS))
            .property("expectDatabase", Schema.string(
                "Refuse to run unless the session's current database has this name"))
            .property("expectHost", Schema.string(
                "Refuse to run unless the connection's configured host is this one"))
            .required("connection", "sql")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        int timeout = Math.min(args.optionalInt("timeoutSeconds", 0), MAX_TIMEOUT_SECONDS);
        Map<String, Object> report = DBeaverBridge.executeScript(
            args.required("connection"), args.required("sql"), timeout > 0 ? timeout : null,
            args.optional("expectDatabase"), args.optional("expectHost"));
        return "completed".equals(report.get("status")) ? report : new FailedResult(report);
    }
}
