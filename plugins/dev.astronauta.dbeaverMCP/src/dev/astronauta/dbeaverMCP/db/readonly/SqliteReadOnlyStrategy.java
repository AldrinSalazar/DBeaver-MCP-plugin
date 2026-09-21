package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

/**
 * SQLite: engine-level {@code PRAGMA query_only}. Unknown pragmas are
 * silently ignored by SQLite, so the flag is verified after setting and
 * any failure aborts the query (fail closed).
 */
public final class SqliteReadOnlyStrategy extends AbstractJdbcStrategy {

    @Override
    public String id() {
        return "sqlite";
    }

    @Override
    public String label() {
        return "SQLite engine read-only mode (PRAGMA query_only)";
    }

    @Override
    public ProtectionLevel protection() {
        return ProtectionLevel.DRIVER_ENFORCED;
    }

    @Override
    public Set<String> driverIds() {
        return Set.of("sqlite_jdbc");
    }

    @Override
    public Set<String> productMarkers() {
        return Set.of("sqlite");
    }

    @Override
    protected void beginNative(Connection connection) throws Exception {
        execute(connection, "PRAGMA query_only = ON");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA query_only")) {
            if (!rows.next() || rows.getInt(1) != 1) {
                throw new IllegalStateException("PRAGMA query_only was not honored by the driver");
            }
        }
    }

    @Override
    protected void endNative(Connection connection) throws Exception {
        execute(connection, "PRAGMA query_only = OFF");
    }
}
