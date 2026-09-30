package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;

import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import dev.astronauta.dbeaverMCP.db.AccessPolicy;
import dev.astronauta.dbeaverMCP.db.ConnectionCatalog.ConnectionEntry;
import dev.astronauta.dbeaverMCP.ui.ConnectionGrants;
import org.junit.Test;

public class ConnectionGrantsTest {
    private static ConnectionEntry entry(String id, boolean readOnly) {
        return new ConnectionEntry(id, id, "project", "driver", "driver", null, null, null, null, false, readOnly);
    }

    @Test
    public void bulkGrantsRespectModeAndDbeaverReadOnlyState() {
        for (AccessMode mode : AccessMode.values()) {
            var grants = new ConnectionGrants(new AccessPolicy(mode, Set.of(), Set.of(), Set.of()));
            grants.enableAll(List.of(entry("normal", false), entry("protected", true)), mode);
            AccessPolicy policy = grants.snapshot(AccessMode.READ_WRITE);
            assertTrue(policy.canExpose("normal"));
            assertEquals(mode != AccessMode.METADATA_ONLY, policy.canRead("normal"));
            assertEquals(mode == AccessMode.READ_WRITE, policy.canWrite("normal", false));
            assertFalse(policy.writeIds().contains("protected"));
        }
    }

    @Test
    public void clearingGrantsRemovesAllAccess() {
        var grants = new ConnectionGrants(new AccessPolicy(AccessMode.READ_WRITE,
            Set.of("connection"), Set.of("connection"), Set.of("connection")));
        grants.clear();
        assertFalse(grants.snapshot(AccessMode.READ_WRITE).canExpose("connection"));
    }
}
