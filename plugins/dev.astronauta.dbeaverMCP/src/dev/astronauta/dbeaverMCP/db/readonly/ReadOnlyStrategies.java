package dev.astronauta.dbeaverMCP.db.readonly;

import java.util.List;
import java.util.Locale;

/**
 * Selects the read-only strategy: by JDBC product name at runtime
 * (authoritative), by DBeaver driver id for UI display.
 */
public final class ReadOnlyStrategies {

    private static final GenericReadOnlyStrategy GENERIC = new GenericReadOnlyStrategy();

    private static final List<ReadOnlyStrategy> ALL = List.of(
        new PostgresReadOnlyStrategy(),
        new MySqlReadOnlyStrategy(),
        new OracleReadOnlyStrategy(),
        new SqliteReadOnlyStrategy(),
        GENERIC);

    private ReadOnlyStrategies() {
    }

    public static ReadOnlyStrategy forProduct(String productName) {
        if (productName != null) {
            String lower = productName.toLowerCase(Locale.ROOT);
            for (ReadOnlyStrategy strategy : ALL) {
                if (strategy.productMarkers().stream().anyMatch(lower::contains)) {
                    return strategy;
                }
            }
        }
        return GENERIC;
    }

    public static ReadOnlyStrategy forDriver(String driverId) {
        if (driverId != null) {
            String lower = driverId.toLowerCase(Locale.ROOT);
            for (ReadOnlyStrategy strategy : ALL) {
                if (strategy.driverIds().contains(lower)) {
                    return strategy;
                }
            }
        }
        return GENERIC;
    }
}
