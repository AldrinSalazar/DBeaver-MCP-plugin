package dev.astronauta.dbeaverMCP;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;

/**
 * Persistent configuration for the MCP server, stored in the Eclipse
 * instance preferences (per workspace).
 *
 * <p>Access model: a global {@link AccessMode} ceiling plus per-connection
 * grants for metadata, read-only queries, and writes.
 */
public class McpPreferences {

    public enum AccessMode {
        METADATA_ONLY("metadata", "Metadata only"),
        READ_ONLY("read", "Read only"),
        READ_WRITE("readwrite", "Read / write");

        private final String id;
        private final String label;

        AccessMode(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public static AccessMode fromId(String id) {
            for (AccessMode mode : values()) {
                if (mode.id.equalsIgnoreCase(id)) {
                    return mode;
                }
            }
            return READ_ONLY;
        }
    }

    public static final String KEY_ENABLED = "serverEnabled";
    public static final String KEY_AUTO_START = "autoStart";
    public static final String KEY_PORT = "port";
    public static final String KEY_BIND_HOST = "bindHost";
    public static final String KEY_TOKEN = "authToken";
    public static final String KEY_AUTH_ENABLED = "authEnabled";
    public static final String KEY_ACCESS_MODE = "accessMode";
    public static final String KEY_METADATA_IDS = "allowedConnectionIds";
    public static final String KEY_READ_IDS = "readConnectionIds";
    public static final String KEY_WRITE_IDS = "writeConnectionIds";
    public static final String KEY_QUERY_TIMEOUT = "queryTimeoutSec";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_AUTO_START = true;
    public static final int DEFAULT_PORT = 4319;
    public static final String DEFAULT_BIND_HOST = "127.0.0.1";
    public static final boolean DEFAULT_AUTH_ENABLED = true;
    public static final AccessMode DEFAULT_ACCESS_MODE = AccessMode.READ_ONLY;
    public static final int DEFAULT_QUERY_TIMEOUT_SEC = 60;

    private final Preferences node;

    public McpPreferences() {
        this.node = InstanceScope.INSTANCE.getNode(McpPlugin.PLUGIN_ID);
    }

    public boolean isServerEnabled() {
        return node.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setServerEnabled(boolean enabled) {
        node.putBoolean(KEY_ENABLED, enabled);
    }

    public boolean isAutoStart() {
        return node.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START);
    }

    public void setAutoStart(boolean autoStart) {
        node.putBoolean(KEY_AUTO_START, autoStart);
    }

    public int getPort() {
        return node.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(int port) {
        node.putInt(KEY_PORT, port);
    }

    public String getBindHost() {
        String host = node.get(KEY_BIND_HOST, DEFAULT_BIND_HOST);
        return host == null || host.isBlank() ? DEFAULT_BIND_HOST : host.trim();
    }

    public void setBindHost(String bindHost) {
        node.put(KEY_BIND_HOST, bindHost == null || bindHost.isBlank() ? DEFAULT_BIND_HOST : bindHost.trim());
    }

    public boolean isAuthEnabled() {
        return node.getBoolean(KEY_AUTH_ENABLED, DEFAULT_AUTH_ENABLED);
    }

    public void setAuthEnabled(boolean authEnabled) {
        node.putBoolean(KEY_AUTH_ENABLED, authEnabled);
    }

    public String getToken() {
        return node.get(KEY_TOKEN, "");
    }

    public void setToken(String token) {
        node.put(KEY_TOKEN, token == null ? "" : token);
    }

    /**
     * Returns the auth token, generating and persisting one if missing.
     * The server never runs without a token.
     */
    public synchronized String ensureToken() {
        String token = getToken();
        if (token == null || token.isBlank()) {
            token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
            setToken(token);
            save();
        }
        return token;
    }

    public AccessMode getAccessMode() {
        return AccessMode.fromId(node.get(KEY_ACCESS_MODE, DEFAULT_ACCESS_MODE.id()));
    }

    public void setAccessMode(AccessMode mode) {
        node.put(KEY_ACCESS_MODE, mode == null ? DEFAULT_ACCESS_MODE.id() : mode.id());
    }

    /** Query timeout in seconds; 0 disables it. */
    public int getQueryTimeoutSec() {
        return node.getInt(KEY_QUERY_TIMEOUT, DEFAULT_QUERY_TIMEOUT_SEC);
    }

    public void setQueryTimeoutSec(int seconds) {
        node.putInt(KEY_QUERY_TIMEOUT, seconds);
    }

    /** Connections visible to metadata tools. Empty exposes nothing. */
    public Set<String> getMetadataIds() {
        return getIdSet(KEY_METADATA_IDS);
    }

    public void setMetadataIds(Set<String> ids) {
        setIdSet(KEY_METADATA_IDS, ids);
    }

    /** Connections allowed for read-only queries. */
    public Set<String> getReadIds() {
        return getIdSet(KEY_READ_IDS);
    }

    public void setReadIds(Set<String> ids) {
        setIdSet(KEY_READ_IDS, ids);
    }

    /** Connections allowed for writes (requires read/write mode). */
    public Set<String> getWriteIds() {
        return getIdSet(KEY_WRITE_IDS);
    }

    public void setWriteIds(Set<String> ids) {
        setIdSet(KEY_WRITE_IDS, ids);
    }

    public boolean isMetadataGranted(String connectionId) {
        return connectionId != null && getMetadataIds().contains(connectionId);
    }

    public boolean isReadGranted(String connectionId) {
        return connectionId != null && getReadIds().contains(connectionId);
    }

    public boolean isWriteGranted(String connectionId) {
        return connectionId != null && getWriteIds().contains(connectionId);
    }

    public boolean canRead(String connectionId) {
        return getAccessMode() != AccessMode.METADATA_ONLY && isReadGranted(connectionId);
    }

    public boolean canWrite(String connectionId) {
        return getAccessMode() == AccessMode.READ_WRITE && isWriteGranted(connectionId);
    }

    public boolean canExpose(String connectionId) {
        return isMetadataGranted(connectionId) || isReadGranted(connectionId) || isWriteGranted(connectionId);
    }

    private Set<String> getIdSet(String key) {
        String raw = node.get(key, "");
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void setIdSet(String key, Set<String> ids) {
        node.put(key, ids == null ? "" : String.join(",", ids));
    }

    public void save() {
        try {
            node.flush();
        } catch (BackingStoreException e) {
            McpPlugin.logError("Failed to save MCP Server preferences", e);
        }
    }
}
