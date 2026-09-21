package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/** MySQL 5.6+ / MariaDB: native read-only transaction. */
public final class MySqlReadOnlyStrategy extends AbstractJdbcStrategy {

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String label() {
        return "MySQL/MariaDB native read-only transaction";
    }

    @Override
    public ProtectionLevel protection() {
        return ProtectionLevel.DATABASE_ENFORCED;
    }

    @Override
    public Set<String> driverIds() {
        return Set.of("mysql5", "mysql8", "mariadb");
    }

    @Override
    public Set<String> productMarkers() {
        return Set.of("mysql", "mariadb");
    }

    @Override
    protected void beginNative(Connection connection) throws Exception {
        execute(connection, "START TRANSACTION READ ONLY");
    }
}
