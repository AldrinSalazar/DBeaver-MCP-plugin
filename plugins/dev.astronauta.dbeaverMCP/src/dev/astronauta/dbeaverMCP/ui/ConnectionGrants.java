package dev.astronauta.dbeaverMCP.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;
import dev.astronauta.dbeaverMCP.db.AccessPolicy;
import dev.astronauta.dbeaverMCP.db.ConnectionCatalog.ConnectionEntry;

/** Mutable permission editor; no SWT dependencies. */
public final class ConnectionGrants {
    private final Set<String> metadata = new LinkedHashSet<>();
    private final Set<String> reads = new LinkedHashSet<>();
    private final Set<String> writes = new LinkedHashSet<>();

    public ConnectionGrants(AccessPolicy policy) {
        metadata.addAll(policy.metadataIds());
        reads.addAll(policy.readIds());
        writes.addAll(policy.writeIds());
    }

    public AccessPolicy snapshot(AccessMode mode) {
        return new AccessPolicy(mode, metadata, reads, writes);
    }

    public void setMetadata(String id, boolean grant) {
        set(metadata, id, grant);
        if (!grant) {
            reads.remove(id);
            writes.remove(id);
        }
    }

    public void setRead(String id, boolean grant) {
        set(reads, id, grant);
        if (grant) {
            metadata.add(id);
        }
    }

    public void setWrite(String id, boolean grant) {
        set(writes, id, grant);
        if (grant) {
            metadata.add(id);
        }
    }

    public void enableAll(List<ConnectionEntry> connections, AccessMode mode) {
        for (ConnectionEntry connection : connections) {
            setMetadata(connection.id(), true);
            if (mode != AccessMode.METADATA_ONLY) {
                setRead(connection.id(), true);
            }
            if (mode == AccessMode.READ_WRITE && !connection.readOnly()) {
                setWrite(connection.id(), true);
            }
        }
    }

    public void clear() {
        metadata.clear();
        reads.clear();
        writes.clear();
    }

    private static void set(Set<String> ids, String id, boolean grant) {
        if (grant) {
            ids.add(id);
        } else {
            ids.remove(id);
        }
    }
}
