package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.sql.Statement;

import dev.astronauta.dbeaverMCP.McpPlugin;

/**
 * Shared lifecycle: driver read-only hint, explicit transaction, native
 * read-only statement, and fail-safe cleanup (rollback + flag restore).
 */
public abstract class AbstractJdbcStrategy implements ReadOnlyStrategy {

    private interface SqlStep {
        void run() throws Exception;
    }

    @Override
    public final void begin(Connection connection) throws Exception {
        try {
            connection.setReadOnly(true);
        } catch (Exception e) {
            // Hint only, never the boundary: log and continue.
            McpPlugin.logInfo("MCP: driver read-only hint not applied: " + e.getMessage());
        }
        connection.setAutoCommit(false);
        beginNative(connection);
    }

    /**
     * Starts the database-native read-only mechanism.
     * Throwing aborts the query (fail closed).
     */
    protected abstract void beginNative(Connection connection) throws Exception;

    @Override
    public final void end(Connection connection) {
        if (connection == null) {
            return;
        }
        attempt(connection::rollback, "rollback");
        attempt(() -> endNative(connection), "native cleanup");
        attempt(() -> connection.setAutoCommit(true), "restore auto-commit");
        attempt(() -> connection.setReadOnly(false), "restore read/write flag");
    }

    protected void endNative(Connection connection) throws Exception {
    }

    protected static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void attempt(SqlStep step, String what) {
        try {
            step.run();
        } catch (Exception e) {
            McpPlugin.logInfo("MCP: read-only cleanup (" + what + ") failed: " + e.getMessage());
        }
    }
}
