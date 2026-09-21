package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/**
 * Database-specific read-only enforcement for {@code query_sql}.
 *
 * <p>Strategies run on an isolated JDBC connection owned by a single MCP
 * request. {@link #begin} must throw on any failure so the query fails
 * closed; {@link #end} is cleanup only and must never throw.
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

    void end(Connection connection);
}
