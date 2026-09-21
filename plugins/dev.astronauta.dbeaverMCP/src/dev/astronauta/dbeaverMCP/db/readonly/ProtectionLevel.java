package dev.astronauta.dbeaverMCP.db.readonly;

/**
 * How strongly read-only execution is protected for a connection.
 */
public enum ProtectionLevel {

    /** The database itself rejects writes (native read-only transaction). */
    DATABASE_ENFORCED("database-enforced", "Database enforced"),

    /** The driver/engine rejects writes, but no SQL-level transaction boundary exists. */
    DRIVER_ENFORCED("driver-enforced", "Driver enforced"),

    /** SQL validation plus driver hints and rollback only. Honest, not bulletproof. */
    BEST_EFFORT("best-effort", "Best effort");

    private final String id;
    private final String label;

    ProtectionLevel(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
