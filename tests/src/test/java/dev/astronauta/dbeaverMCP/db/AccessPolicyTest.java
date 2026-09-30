package dev.astronauta.dbeaverMCP.db;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import dev.astronauta.dbeaverMCP.ui.ConnectionGrants;
import org.junit.Test;

public class AccessPolicyTest {
    @Test
    public void modeAndConnectionGrantsBothGateSql() {
        for (AccessMode mode : AccessMode.values()) {
            AccessPolicy policy = new AccessPolicy(mode, Set.of("meta"), Set.of("read"), Set.of("write"));
            assertTrue(policy.canExpose("read"));
            assertTrue(policy.canExpose("write"));
            assertFalse(policy.canRead("meta"));
            assertFalse(policy.canWrite("read", false));
            assertEquals(mode != AccessMode.METADATA_ONLY, policy.canRead("read"));
            assertEquals(mode == AccessMode.READ_WRITE, policy.canWrite("write", false));
            assertFalse(policy.canWrite("write", true));
            assertFalse(policy.canExpose("hidden"));
        }
    }

    @Test
    public void policySnapshotsDoNotChangeWhenEditorSetsChange() {
        Set<String> reads = new HashSet<>(Set.of("read"));
        AccessPolicy policy = new AccessPolicy(AccessMode.READ_ONLY, Set.of(), reads, Set.of());
        reads.clear();
        assertTrue(policy.canRead("read"));
        assertThrows(UnsupportedOperationException.class, () -> policy.readIds().clear());
    }

    @Test
    public void revokingMetadataRevokesSqlGrants() {
        ConnectionGrants grants = new ConnectionGrants(
            new AccessPolicy(AccessMode.READ_WRITE, Set.of(), Set.of(), Set.of()));
        grants.setWrite("connection", true);
        grants.setRead("connection", true);
        grants.setMetadata("connection", false);
        assertFalse(grants.snapshot(AccessMode.READ_WRITE).canExpose("connection"));
        assertFalse(grants.snapshot(AccessMode.READ_WRITE).canRead("connection"));
        assertFalse(grants.snapshot(AccessMode.READ_WRITE).canWrite("connection", false));
    }
}
