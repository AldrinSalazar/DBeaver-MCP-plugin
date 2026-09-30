package dev.astronauta.dbeaverMCP.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/** Connection discovery and resolution; filters grants before matching names. */
public final class ConnectionCatalog {

    @FunctionalInterface
    public interface ProjectSource {
        List<? extends DBPProject> get() throws Exception;
    }

    private final ProjectSource projects;

    public ConnectionCatalog() {
        this(() -> DBWorkbench.getPlatform().getWorkspace().getProjects());
    }

    public ConnectionCatalog(ProjectSource projects) {
        this.projects = projects;
    }

    public record ConnectionEntry(
        String id, String name, String project, String driver, String driverId,
        String host, String port, String database, String user,
        boolean connected, boolean readOnly) {
    }

    public List<ConnectionEntry> listAllConnections() throws BridgeException {
        List<ConnectionEntry> result = new ArrayList<>();
        for (DBPProject project : allProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            if (registry == null) {
                continue;
            }
            for (DBPDataSourceContainer container : registry.getDataSources()) {
                if (container == null) {
                    continue;
                }
                DBPConnectionConfiguration cfg = safe(container::getConnectionConfiguration);
                result.add(new ConnectionEntry(
                    container.getId(),
                    container.getName(),
                    project.getName(),
                    container.getDriver() != null ? container.getDriver().getName() : "?",
                    safe(() -> container.getDriver().getId()),
                    cfg != null ? cfg.getHostName() : null,
                    cfg != null ? cfg.getHostPort() : null,
                    cfg != null ? cfg.getDatabaseName() : null,
                    cfg != null ? cfg.getUserName() : null,
                    container.isConnected(),
                    safe(() -> container.isConnectionReadOnly(), true)));
            }
        }
        result.sort((a, b) -> {
            int c = a.project.compareToIgnoreCase(b.project);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    public static Map<String, Object> toMap(ConnectionEntry entry, AccessPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.id);
        map.put("name", entry.name);
        map.put("project", entry.project);
        map.put("driver", entry.driver);
        put(map, "driverId", entry.driverId);
        put(map, "host", entry.host);
        put(map, "port", entry.port);
        put(map, "database", entry.database);
        put(map, "user", entry.user);
        map.put("connected", entry.connected);
        map.put("readOnly", entry.readOnly);
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("metadata", policy.canExpose(entry.id));
        access.put("read", policy.canRead(entry.id));
        access.put("write", policy.canWrite(entry.id, entry.readOnly));
        map.put("access", access);
        map.put("readOnlyProtection", ReadOnlyStrategies.forDriver(entry.driverId).protection().id());
        return map;
    }

    /**
     * Resolves a connection reference (id or name) across all projects and
     * enforces the allowlist configured in preferences.
     */
    public DBPDataSourceContainer resolveConnection(String ref, AccessPolicy policy) throws BridgeException {
        if (ref == null || ref.isBlank()) {
            throw new BridgeException("Missing required argument 'connection'");
        }
        String wanted = ref.trim();
        List<DBPDataSourceContainer> byName = new ArrayList<>();
        for (DBPProject project : allProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            if (registry == null) {
                continue;
            }
            for (DBPDataSourceContainer container : registry.getDataSources()) {
                if (container == null || !policy.canExpose(container.getId())) {
                    continue;
                }
                if (wanted.equals(container.getId())) {
                    return container;
                }
                if (wanted.equals(container.getName()) || wanted.equalsIgnoreCase(container.getName())) {
                    byName.add(container);
                }
            }
        }
        if (byName.size() == 1) {
            return byName.get(0);
        }
        if (byName.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (DBPDataSourceContainer c : byName) {
                ids.add(c.getId() + " (project '" + c.getProject().getName() + "')");
            }
            throw new BridgeException("Connection name '" + wanted + "' is ambiguous, use an id: " + ids);
        }
        throw new BridgeException("Unknown connection '" + wanted + "'. Use list_connections to see exposed connections.");
    }

    private List<? extends DBPProject> allProjects() throws BridgeException {
        try {
            return projects.get();
        } catch (Exception e) {
            throw new BridgeException("DBeaver platform is not available: " + message(e), e);
        }
    }

    public static DBPDataSource ensureDataSource(DBPDataSourceContainer container, DBRProgressMonitor monitor)
        throws BridgeException {
        if (!container.isConnected()) {
            try {
                container.connect(monitor, true, true);
            } catch (Exception e) {
                throw new BridgeException("Cannot connect to '" + container.getName() + "': " + message(e)
                    + ". If it needs credentials, connect it in DBeaver first.", e);
            }
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new BridgeException("Connection '" + container.getName() + "' is not available (still connecting?)");
        }
        return dataSource;
    }

    private interface SafeGet<T> {
        T get() throws Exception;
    }

    private static <T> T safe(SafeGet<T> getter) {
        return safe(getter, null);
    }

    private static <T> T safe(SafeGet<T> getter, T fallback) {
        try {
            T value = getter.get();
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String message(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
