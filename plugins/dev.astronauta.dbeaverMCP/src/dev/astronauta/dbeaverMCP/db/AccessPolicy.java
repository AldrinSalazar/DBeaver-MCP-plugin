package dev.astronauta.dbeaverMCP.db;

import java.util.LinkedHashSet;
import java.util.Set;

import dev.astronauta.dbeaverMCP.McpPreferences.AccessMode;

/** Immutable permission snapshot for one operation. SQL grants imply metadata access. */
public record AccessPolicy(AccessMode mode, Set<String> metadataIds, Set<String> readIds, Set<String> writeIds) {

    public AccessPolicy {
        readIds = Set.copyOf(readIds);
        writeIds = Set.copyOf(writeIds);
        Set<String> exposed = new LinkedHashSet<>(metadataIds);
        exposed.addAll(readIds);
        exposed.addAll(writeIds);
        metadataIds = Set.copyOf(exposed);
    }

    public boolean canExpose(String id) {
        return id != null && metadataIds.contains(id);
    }

    public boolean canRead(String id) {
        return id != null && mode != AccessMode.METADATA_ONLY && readIds.contains(id);
    }

    public boolean canWrite(String id, boolean connectionReadOnly) {
        return id != null && mode == AccessMode.READ_WRITE && writeIds.contains(id) && !connectionReadOnly;
    }

    public void requireMetadata(String id) throws BridgeException {
        if (!canExpose(id)) {
            throw new BridgeException("Connection is not exposed over MCP. Enable access in MCP Server preferences.");
        }
    }

    public void requireRead(String id) throws BridgeException {
        if (!canRead(id)) {
            throw new BridgeException("Read-only queries require a SQL access mode and the connection's Read grant.");
        }
    }

    public void requireWrite(String id, boolean connectionReadOnly) throws BridgeException {
        if (!canWrite(id, connectionReadOnly)) {
            throw new BridgeException("Writes require Read/write mode, the connection's Write grant,"
                + " and a connection that is not read-only in DBeaver.");
        }
    }
}
