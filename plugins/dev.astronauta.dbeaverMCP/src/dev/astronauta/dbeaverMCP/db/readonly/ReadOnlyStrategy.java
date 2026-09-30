package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/**
 * Database-specific read-only enforcement for {@code query_sql}.
 *
 * <p>Strategies run on an isolated JDBC connection owned by a single MCP
 * request. Establishment and cleanup failures both fail the query.
 */
public interface ReadOnlyStrategy {

    /** Stable id, e.g. {@code "postgresql"}. */
    String id();

    /** Human-readable label for UI and query responses. */
    String label();

    ProtectionLevel protection();

    /** Lowercase DBeaver driver ids this strategy applies to (UI display). */
    Set<String> driverIds();

    /**
     * Lowercase substrings matched against the JDBC product name
     * (runtime selection). Empty means "fallback for everything else".
     */
    Set<String> productMarkers();

    void begin(Connection connection) throws Exception;

    void end(Connection connection) throws Exception;

    /** Cleanup runs even after partial establishment; preserves the original failure. */
    default AutoCloseable protect(Connection connection) throws Exception {
        try {
            begin(connection);
        } catch (Exception failure) {
            try {
                end(connection);
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return () -> end(connection);
    }
}
