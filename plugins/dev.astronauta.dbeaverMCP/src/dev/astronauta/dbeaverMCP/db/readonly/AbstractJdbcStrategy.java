package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared lifecycle: driver read-only hint, explicit transaction, native
 * read-only statement, and fail-safe cleanup (rollback + flag restore).
 */
public abstract class AbstractJdbcStrategy implements ReadOnlyStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractJdbcStrategy.class);

    @Override
    public final void begin(Connection connection) throws Exception {
        try {
            connection.setReadOnly(true);
        } catch (Exception e) {
            if (protection() == ProtectionLevel.BEST_EFFORT) {
                throw e;
            }
            // A native strategy remains authoritative when a driver hint is unsupported.
            LOG.info("MCP: driver read-only hint not applied", e);
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
    public final void end(Connection connection) throws Exception {
        if (connection == null) {
            return;
        }
        // Enabling auto-commit after a failed rollback could commit the read transaction.
        // The caller owns the isolated connection and closes it instead.
        connection.rollback();
        endNative(connection);
        connection.setAutoCommit(true);
        try {
            connection.setReadOnly(false);
        } catch (Exception e) {
            // This remains a hint; rollback and native cleanup above are mandatory.
            LOG.info("MCP: driver read-only hint could not be restored", e);
        }
    }

    protected void endNative(Connection connection) throws Exception {
    }

    protected static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
