package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/**
 * Fallback for everything else (SQL Server, exotic drivers, ...):
 * driver hint plus explicit transaction and rollback, with SQL
 * validation as the main gate. Honestly reported as best effort.
 */
public final class GenericReadOnlyStrategy extends AbstractJdbcStrategy {

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public String label() {
        return "Generic JDBC (hint + rollback, best effort)";
    }

    @Override
    public ProtectionLevel protection() {
        return ProtectionLevel.BEST_EFFORT;
    }

    @Override
    public Set<String> driverIds() {
        return Set.of();
    }

    @Override
    public Set<String> productMarkers() {
        return Set.of();
    }

    @Override
    protected void beginNative(Connection connection) {
        // No database-native mechanism known; the base class still applies
        // the driver hint, an explicit transaction, and rollback on cleanup.
    }
}
