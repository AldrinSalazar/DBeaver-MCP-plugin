package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/** PostgreSQL-compatible servers: native read-only transaction. */
public final class PostgresReadOnlyStrategy extends AbstractJdbcStrategy {

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public String label() {
        return "PostgreSQL native read-only transaction";
    }

    @Override
    public ProtectionLevel protection() {
        return ProtectionLevel.DATABASE_ENFORCED;
    }

    @Override
    public Set<String> driverIds() {
        return Set.of("postgres-jdbc", "postgres-edb-jdbc", "postgres-gcloud-jdbc", "postgres-greenplum-jdbc");
    }

    @Override
    public Set<String> productMarkers() {
        return Set.of("postgresql");
    }

    @Override
    protected void beginNative(Connection connection) throws Exception {
        execute(connection, "START TRANSACTION READ ONLY");
    }
}
