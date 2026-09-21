package dev.astronauta.dbeaverMCP.db.readonly;

import java.sql.Connection;
import java.util.Set;

/** Oracle: native read-only transaction. Must be the first statement of the transaction. */
public final class OracleReadOnlyStrategy extends AbstractJdbcStrategy {

    @Override
    public String id() {
        return "oracle";
    }

    @Override
    public String label() {
        return "Oracle native read-only transaction";
    }

    @Override
    public ProtectionLevel protection() {
        return ProtectionLevel.DATABASE_ENFORCED;
    }

    @Override
    public Set<String> driverIds() {
        return Set.of("oracle_thin");
    }

    @Override
    public Set<String> productMarkers() {
        return Set.of("oracle");
    }

    @Override
    protected void beginNative(Connection connection) throws Exception {
        execute(connection, "SET TRANSACTION READ ONLY");
    }
}
