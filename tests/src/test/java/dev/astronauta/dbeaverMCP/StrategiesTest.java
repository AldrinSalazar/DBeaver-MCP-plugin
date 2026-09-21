package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;

import dev.astronauta.dbeaverMCP.db.readonly.ProtectionLevel;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import org.junit.Test;

public class StrategiesTest {

    @Test
    public void selectsByProductName() {
        assertEquals("postgresql", ReadOnlyStrategies.forProduct("PostgreSQL").id());
        assertEquals("mysql", ReadOnlyStrategies.forProduct("MySQL").id());
        assertEquals("mysql", ReadOnlyStrategies.forProduct("MariaDB").id());
        assertEquals("oracle", ReadOnlyStrategies.forProduct("Oracle").id());
        assertEquals("sqlite", ReadOnlyStrategies.forProduct("SQLite").id());
        assertEquals("generic", ReadOnlyStrategies.forProduct("Microsoft SQL Server").id());
        assertEquals("generic", ReadOnlyStrategies.forProduct("Something Exotic").id());
        assertEquals("generic", ReadOnlyStrategies.forProduct(null).id());
    }

    @Test
    public void selectsByDriverId() {
        assertEquals("postgresql", ReadOnlyStrategies.forDriver("postgres-jdbc").id());
        assertEquals("postgresql", ReadOnlyStrategies.forDriver("postgres-gcloud-jdbc").id());
        assertEquals("mysql", ReadOnlyStrategies.forDriver("mysql8").id());
        assertEquals("mysql", ReadOnlyStrategies.forDriver("mariaDB").id());
        assertEquals("oracle", ReadOnlyStrategies.forDriver("oracle_thin").id());
        assertEquals("sqlite", ReadOnlyStrategies.forDriver("sqlite_jdbc").id());
        assertEquals("generic", ReadOnlyStrategies.forDriver("postgres-redshift-jdbc").id());
        assertEquals("generic", ReadOnlyStrategies.forDriver(null).id());
    }

    @Test
    public void protectionLevels() {
        assertEquals(ProtectionLevel.DATABASE_ENFORCED, ReadOnlyStrategies.forProduct("PostgreSQL").protection());
        assertEquals(ProtectionLevel.DATABASE_ENFORCED, ReadOnlyStrategies.forProduct("MySQL").protection());
        assertEquals(ProtectionLevel.DATABASE_ENFORCED, ReadOnlyStrategies.forProduct("Oracle").protection());
        assertEquals(ProtectionLevel.DRIVER_ENFORCED, ReadOnlyStrategies.forProduct("SQLite").protection());
        assertEquals(ProtectionLevel.BEST_EFFORT, ReadOnlyStrategies.forProduct("Microsoft SQL Server").protection());
    }
}
