package dev.astronauta.dbeaverMCP.db;

import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.McpPreferences;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

/** Small composition facade used by MCP tools and the preferences page. */
public final class DBeaverBridge {
    private DBeaverBridge() {
    }

    public static List<ConnectionCatalog.ConnectionEntry> listAllConnections() throws BridgeException {
        return new ConnectionCatalog().listAllConnections();
    }

    public static List<Map<String, Object>> listConnections() throws BridgeException {
        AccessPolicy policy = new McpPreferences().accessPolicy();
        return new ConnectionCatalog().listAllConnections().stream()
            .filter(connection -> policy.canExpose(connection.id()))
            .map(connection -> ConnectionCatalog.toMap(connection, policy))
            .toList();
    }

    private static MetadataService metadata() {
        return new MetadataService(new ConnectionCatalog(), new McpPreferences().accessPolicy());
    }

    public static Map<String, Object> browse(String connection, String path) throws BridgeException {
        return metadata().browse(connection, path);
    }

    public static Map<String, Object> describeTable(String connection, String catalog, String schema, String table)
        throws BridgeException {
        return metadata().describeTable(connection, catalog, schema, table);
    }

    public static Map<String, Object> getDdl(String connection, String catalog, String schema,
            String object, String kind, String table) throws BridgeException {
        return metadata().getDdl(connection, catalog, schema, object, kind, table);
    }

    public static Map<String, Object> listProcedures(String connection, String catalog, String schema)
        throws BridgeException {
        return metadata().listProcedures(connection, catalog, schema);
    }

    public static Map<String, Object> listTriggers(String connection, String catalog, String schema, String table)
        throws BridgeException {
        return metadata().listTriggers(connection, catalog, schema, table);
    }

    public static Map<String, Object> querySql(String ref, String sql, int maxRows) throws BridgeException {
        McpPreferences preferences = new McpPreferences();
        AccessPolicy policy = preferences.accessPolicy();
        DBPDataSourceContainer connection = new ConnectionCatalog().resolveConnection(ref, policy);
        policy.requireRead(connection.getId());
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(connection, new VoidProgressMonitor());
        Map<String, Object> result = new SqlExecutor(dataSource, preferences.getQueryTimeoutSec())
            .queryReadOnly(sql, maxRows);
        result.put("connection", connection.getName());
        return result;
    }

    public static Map<String, Object> executeSql(String ref, String sql) throws BridgeException {
        McpPreferences preferences = new McpPreferences();
        AccessPolicy policy = preferences.accessPolicy();
        DBPDataSourceContainer connection = new ConnectionCatalog().resolveConnection(ref, policy);
        policy.requireWrite(connection.getId(), connection.isConnectionReadOnly());
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(connection, new VoidProgressMonitor());
        Map<String, Object> result = new SqlExecutor(dataSource, preferences.getQueryTimeoutSec())
            .executeReadWrite(sql);
        result.put("connection", connection.getName());
        return result;
    }
}
