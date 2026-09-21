package dev.astronauta.dbeaverMCP.db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.astronauta.dbeaverMCP.McpPlugin;
import dev.astronauta.dbeaverMCP.McpPreferences;
import dev.astronauta.dbeaverMCP.db.readonly.ProtectionLevel;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategy;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameter;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTrigger;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/**
 * All access to the DBeaver model. Every method is safe to call from
 * MCP request threads: it uses its own progress monitor and never
 * touches SWT. Credentials are never read - connections are established
 * (or reused) through DBeaver itself.
 */
public final class DBeaverBridge {

    public static final int MAX_ROWS = 5000;
    public static final int MAX_CHILDREN = 2000;
    public static final int MAX_DDL_CHARS = 500_000;

    private DBeaverBridge() {
    }

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

    public record ConnectionEntry(
        String id, String name, String project, String driver, String driverId,
        String host, String port, String database, String user,
        boolean connected, boolean readOnly) {
    }

    public static List<ConnectionEntry> listAllConnections() throws BridgeException {
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
                    safe(() -> container.isConnectionReadOnly(), false)));
            }
        }
        result.sort((a, b) -> {
            int c = a.project.compareToIgnoreCase(b.project);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    public static Map<String, Object> toMap(ConnectionEntry entry) {
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
        McpPreferences prefs = new McpPreferences();
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("metadata", prefs.isMetadataGranted(entry.id));
        access.put("read", prefs.canRead(entry.id));
        access.put("write", prefs.canWrite(entry.id) && !entry.readOnly);
        map.put("access", access);
        map.put("readOnlyProtection", ReadOnlyStrategies.forDriver(entry.driverId).protection().id());
        return map;
    }

    /**
     * Resolves a connection reference (id or name) across all projects and
     * enforces the allowlist configured in preferences.
     */
    public static DBPDataSourceContainer resolveConnection(String ref) throws BridgeException {
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
                if (container == null) {
                    continue;
                }
                if (wanted.equals(container.getId())) {
                    return checkAllowed(container);
                }
                if (wanted.equals(container.getName()) || wanted.equalsIgnoreCase(container.getName())) {
                    byName.add(container);
                }
            }
        }
        if (byName.size() == 1) {
            return checkAllowed(byName.get(0));
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

    private static DBPDataSourceContainer checkAllowed(DBPDataSourceContainer container) throws BridgeException {
        if (!new McpPreferences().isMetadataGranted(container.getId())) {
            throw new BridgeException("Connection '" + container.getName()
                + "' is not exposed over MCP. Enable Metadata access in Window > Preferences > MCP Server.");
        }
        return container;
    }

    private static void requireRead(DBPDataSourceContainer container) throws BridgeException {
        McpPreferences prefs = new McpPreferences();
        if (prefs.getAccessMode() == McpPreferences.AccessMode.METADATA_ONLY) {
            throw new BridgeException("SQL queries are disabled in Metadata only mode."
                + " Enable Read only mode in Window > Preferences > MCP Server.");
        }
        if (!prefs.isReadGranted(container.getId())) {
            throw new BridgeException("Connection '" + container.getName()
                + "' is not enabled for read-only queries."
                + " Enable Read-only queries in Window > Preferences > MCP Server.");
        }
    }

    private static void requireWrite(DBPDataSourceContainer container) throws BridgeException {
        McpPreferences prefs = new McpPreferences();
        if (prefs.getAccessMode() != McpPreferences.AccessMode.READ_WRITE) {
            throw new BridgeException("Write access is not enabled (global mode is '"
                + prefs.getAccessMode().label() + "'). Enable Read/Write mode"
                + " in Window > Preferences > MCP Server.");
        }
        if (!prefs.isWriteGranted(container.getId())) {
            throw new BridgeException("Connection '" + container.getName()
                + "' is not enabled for writes. Enable Write queries in Window > Preferences > MCP Server.");
        }
        if (safe(container::isConnectionReadOnly, false)) {
            throw new BridgeException("Connection '" + container.getName() + "' is read-only in DBeaver");
        }
    }

    private static List<? extends DBPProject> allProjects() throws BridgeException {
        try {
            return DBWorkbench.getPlatform().getWorkspace().getProjects();
        } catch (Exception e) {
            throw new BridgeException("DBeaver platform is not available: " + message(e), e);
        }
    }

    // ------------------------------------------------------------------
    // Sessions and navigation helpers
    // ------------------------------------------------------------------

    private static DBRProgressMonitor monitor() {
        return new VoidProgressMonitor();
    }

    private static DBPDataSource ensureDataSource(DBPDataSourceContainer container, DBRProgressMonitor monitor)
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

    private static DBCExecutionContext executionContext(DBPDataSource dataSource, boolean meta) {
        return dataSource.getDefaultInstance().getDefaultContext(monitor(), meta);
    }

    /**
     * Opens a separate physical connection for MCP query execution, so MCP
     * traffic never shares transaction or session state with the user's
     * working sessions. Callers must close the returned context.
     */
    private static DBCExecutionContext isolatedContext(DBPDataSource dataSource, String purpose)
        throws BridgeException {
        try {
            return dataSource.getDefaultInstance().openIsolatedContext(monitor(), purpose, null);
        } catch (Exception e) {
            throw new BridgeException("Cannot open isolated session on '"
                + dataSource.getContainer().getName() + "': " + message(e), e);
        }
    }

    private static DBSObjectContainer rootContainer(DBPDataSource dataSource) throws BridgeException {
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        if (root == null) {
            throw new BridgeException("Connection '" + dataSource.getContainer().getName()
                + "' does not expose a browsable object tree");
        }
        return root;
    }

    private static DBSObject findChild(DBSObjectContainer parent, String name) throws BridgeException {
        DBRProgressMonitor monitor = monitor();
        try {
            DBSObject exact = parent.getChild(monitor, name);
            if (exact != null) {
                return exact;
            }
            Collection<? extends DBSObject> children = parent.getChildren(monitor);
            if (children != null) {
                for (DBSObject child : children) {
                    if (child != null && name.equalsIgnoreCase(child.getName())) {
                        return child;
                    }
                }
            }
        } catch (Exception e) {
            throw new BridgeException("Cannot read children of '" + parent.getName() + "': " + message(e), e);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // browse
    // ------------------------------------------------------------------

    public static Map<String, Object> browse(String connectionRef, String path) throws BridgeException {
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        DBSObjectContainer root = rootContainer(dataSource);

        DBSObject target = root;
        if (path != null && !path.isBlank()) {
            List<String> segments = new ArrayList<>();
            for (String raw : path.split("/")) {
                if (!raw.trim().isEmpty()) {
                    segments.add(raw.trim());
                }
            }
            DBSObjectContainer current = root;
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                DBSObject child = findChild(current, segment);
                if (child == null) {
                    throw new BridgeException("Object '" + segment + "' not found under '"
                        + displayPath(root, current) + "'. Browse without a path to list available objects.");
                }
                boolean last = (i == segments.size() - 1);
                if (child instanceof DBSObjectContainer childContainer) {
                    current = childContainer;
                } else if (!last) {
                    throw new BridgeException("'" + segment + "' is not a container, cannot navigate deeper");
                }
                target = child;
            }
        }

        Map<String, Object> result = describeObject(target);
        result.put("connection", container.getName());
        result.put("path", path == null ? "" : path.trim());
        List<Map<String, Object>> children = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        if (target instanceof DBSObjectContainer targetContainer) {
            try {
                Collection<? extends DBSObject> raw = targetContainer.getChildren(monitor);
                if (raw != null) {
                    List<DBSObject> sorted = new ArrayList<>(raw);
                    sorted.sort((a, b) -> String.valueOf(a.getName()).compareToIgnoreCase(String.valueOf(b.getName())));
                    total = sorted.size();
                    for (DBSObject child : sorted) {
                        if (children.size() >= MAX_CHILDREN) {
                            truncated = true;
                            break;
                        }
                        if (child != null) {
                            children.add(describeObject(child));
                        }
                    }
                }
            } catch (Exception e) {
                throw new BridgeException("Cannot list children of '" + target.getName() + "': " + message(e), e);
            }
        }
        result.put("totalChildren", total);
        result.put("truncated", truncated);
        result.put("children", children);
        return result;
    }

    private static String displayPath(DBSObjectContainer root, DBSObject object) {
        if (object == root) {
            return "(connection root)";
        }
        List<String> parts = new ArrayList<>();
        for (DBSObject current = object; current != null && current != root; current = current.getParentObject()) {
            parts.add(0, current.getName());
        }
        return String.join("/", parts);
    }

    private static Map<String, Object> describeObject(DBSObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", object.getName());
        String kind;
        if (object instanceof DBSEntity entity) {
            kind = "entity:" + safe(() -> entity.getEntityType().getId(), "entity");
        } else if (object instanceof DBSProcedure) {
            kind = "procedure";
        } else if (object instanceof DBSTrigger) {
            kind = "trigger";
        } else if (object instanceof DBSObjectContainer) {
            kind = "container";
        } else {
            kind = "object";
        }
        map.put("kind", kind);
        map.put("type", object.getClass().getSimpleName());
        put(map, "description", safe(object::getDescription));
        map.put("navigable", object instanceof DBSObjectContainer);
        return map;
    }

    // ------------------------------------------------------------------
    // describe_table
    // ------------------------------------------------------------------

    public static Map<String, Object> describeTable(String connectionRef, String catalog, String schema, String table)
        throws BridgeException {
        if (table == null || table.isBlank()) {
            throw new BridgeException("Missing required argument 'table'");
        }
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        DBCExecutionContext context = executionContext(dataSource, true);
        DBSObjectContainer root = rootContainer(dataSource);

        DBSObject found;
        try {
            found = DBUtils.getObjectByPath(monitor, context, root,
                blankToNull(catalog), blankToNull(schema), table.trim());
        } catch (Exception e) {
            throw new BridgeException("Cannot resolve table '" + table + "': " + message(e), e);
        }
        if (found == null) {
            throw new BridgeException("Table '" + table + "' not found"
                + (schema != null && !schema.isBlank() ? " in schema '" + schema + "'" : "")
                + ". Use browse to explore.");
        }
        if (!(found instanceof DBSEntity entity)) {
            throw new BridgeException("'" + table + "' is a " + found.getClass().getSimpleName() + ", not a table or view");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connection", container.getName());
        result.put("name", entity.getName());
        put(result, "catalog", catalogName(entity));
        put(result, "schema", schemaName(entity));
        put(result, "type", safe(() -> entity.getEntityType().getId()));
        put(result, "description", safe(entity::getDescription));
        if (entity instanceof DBSTable dbTable) {
            result.put("view", safe(dbTable::isView, false));
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            List<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            if (attributes != null) {
                for (DBSEntityAttribute attr : attributes) {
                    if (attr != null) {
                        columns.add(describeAttribute(attr));
                    }
                }
            }
        } catch (Exception e) {
            throw new BridgeException("Cannot read columns of '" + table + "': " + message(e), e);
        }
        result.put("columns", columns);

        List<Map<String, Object>> constraints = new ArrayList<>();
        Map<String, Object> primaryKey = null;
        try {
            Collection<? extends DBSEntityConstraint> raw = entity.getConstraints(monitor);
            if (raw != null) {
                for (DBSEntityConstraint constraint : raw) {
                    if (constraint == null) {
                        continue;
                    }
                    Map<String, Object> c = describeConstraint(constraint, monitor);
                    constraints.add(c);
                    if (primaryKey == null && "pk".equals(c.get("type"))) {
                        primaryKey = c;
                    }
                }
            }
        } catch (Exception e) {
            McpPlugin.logError("Cannot read constraints of '" + table + "'", e);
        }
        result.put("constraints", constraints);
        if (primaryKey != null) {
            result.put("primaryKey", primaryKey);
        }

        if (entity instanceof DBSTable dbTable) {
            List<Map<String, Object>> indexes = new ArrayList<>();
            try {
                Collection<? extends DBSTableIndex> raw = dbTable.getIndexes(monitor);
                if (raw != null) {
                    for (DBSTableIndex index : raw) {
                        if (index != null) {
                            indexes.add(describeIndex(index, monitor));
                        }
                    }
                }
            } catch (Exception e) {
                McpPlugin.logError("Cannot read indexes of '" + table + "'", e);
            }
            result.put("indexes", indexes);
        }

        result.put("ddlAvailable", entity instanceof DBPScriptObject);
        return result;
    }

    private static Map<String, Object> describeAttribute(DBSEntityAttribute attr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", attr.getName());
        put(map, "ordinal", safe(() -> ((DBSAttributeBase) attr).getOrdinalPosition() + 1));
        put(map, "type", safe(attr::getTypeName));
        put(map, "fullType", safe(attr::getFullTypeName));
        put(map, "typeId", safe(attr::getTypeID));
        put(map, "dataKind", safe(() -> String.valueOf(attr.getDataKind())));
        put(map, "maxLength", safe(attr::getMaxLength));
        put(map, "precision", safe(attr::getPrecision));
        put(map, "scale", safe(attr::getScale));
        put(map, "required", safe(() -> ((DBSAttributeBase) attr).isRequired(), false));
        put(map, "autoGenerated", safe(() -> ((DBSAttributeBase) attr).isAutoGenerated(), false));
        put(map, "defaultValue", safe(attr::getDefaultValue));
        put(map, "description", safe(attr::getDescription));
        return map;
    }

    private static Map<String, Object> describeConstraint(DBSEntityConstraint constraint, DBRProgressMonitor monitor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", constraint.getName());
        put(map, "type", safe(() -> constraint.getConstraintType().getId()));
        put(map, "typeName", safe(() -> constraint.getConstraintType().getName()));
        if (constraint instanceof DBSEntityReferrer referrer) {
            List<String> columns = new ArrayList<>();
            try {
                List<? extends DBSEntityAttributeRef> refs = referrer.getAttributeReferences(monitor);
                if (refs != null) {
                    for (DBSEntityAttributeRef ref : refs) {
                        if (ref != null && ref.getAttribute() != null) {
                            columns.add(ref.getAttribute().getName());
                        }
                    }
                }
            } catch (Exception e) {
                McpPlugin.logError("Cannot read columns of constraint '" + constraint.getName() + "'", e);
            }
            map.put("columns", columns);
        }
        if (constraint instanceof DBSEntityAssociation association) {
            put(map, "referencedTable", safe(() -> {
                DBSEntity target = association.getAssociatedEntity();
                return target != null ? DBUtils.getObjectFullId(target) : null;
            }));
            put(map, "referencedConstraint", safe(() -> {
                DBSEntityConstraint target = association.getReferencedConstraint();
                return target != null ? target.getName() : null;
            }));
        }
        return map;
    }

    private static Map<String, Object> describeIndex(DBSTableIndex index, DBRProgressMonitor monitor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", index.getName());
        put(map, "unique", safe(index::isUnique, false));
        put(map, "primary", safe(index::isPrimary, false));
        put(map, "indexType", safe(() -> String.valueOf(index.getIndexType())));
        List<String> columns = new ArrayList<>();
        try {
            List<? extends DBSEntityAttributeRef> refs = index.getAttributeReferences(monitor);
            if (refs != null) {
                for (DBSEntityAttributeRef ref : refs) {
                    if (ref != null && ref.getAttribute() != null) {
                        columns.add(ref.getAttribute().getName());
                    }
                }
            }
        } catch (Exception e) {
            McpPlugin.logError("Cannot read columns of index '" + index.getName() + "'", e);
        }
        map.put("columns", columns);
        return map;
    }

    private static String catalogName(DBSObject object) {
        for (DBSObject current = object.getParentObject(); current != null; current = current.getParentObject()) {
            if (current instanceof DBSCatalog) {
                return current.getName();
            }
        }
        return null;
    }

    private static String schemaName(DBSObject object) {
        for (DBSObject current = object.getParentObject(); current != null; current = current.getParentObject()) {
            if (current instanceof DBSSchema) {
                return current.getName();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // get_ddl
    // ------------------------------------------------------------------

    public static Map<String, Object> getDdl(String connectionRef, String catalog, String schema,
            String objectName, String kind, String table) throws BridgeException {
        if (objectName == null || objectName.isBlank()) {
            throw new BridgeException("Missing required argument 'object'");
        }
        String wantedKind = kind == null || kind.isBlank() ? "auto" : kind.trim().toLowerCase();
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        DBCExecutionContext context = executionContext(dataSource, true);
        DBSObjectContainer root = rootContainer(dataSource);

        try {
            // 1. Direct path resolution (tables, views, and anything addressable by name)
            if (!"trigger".equals(wantedKind)) {
                DBSObject found = DBUtils.getObjectByPath(monitor, context, root,
                    blankToNull(catalog), blankToNull(schema), objectName.trim());
                if (found != null && kindMatches(found, wantedKind) && found instanceof DBPScriptObject script) {
                    return ddlResult(container, found, ddl(script, monitor));
                }
                // 2. Procedures / functions live outside the entity tree on most drivers
                if (("auto".equals(wantedKind) || "procedure".equals(wantedKind) || "function".equals(wantedKind))
                    && !(found instanceof DBSProcedure)) {
                    DBSObject scope = DBUtils.getObjectByPath(monitor, context, root,
                        blankToNull(catalog), blankToNull(schema), null);
                    if (scope instanceof DBSProcedureContainer procedures) {
                        DBSProcedure procedure = procedures.getProcedure(monitor, objectName.trim());
                        if (procedure instanceof DBPScriptObject script) {
                            return ddlResult(container, procedure, ddl(script, monitor));
                        }
                        if (procedure != null) {
                            throw new BridgeException("Procedure '" + objectName + "' does not expose its source on this driver");
                        }
                    }
                }
                if (found == null) {
                    throw new BridgeException("Object '" + objectName + "' not found"
                        + (schema != null && !schema.isBlank() ? " in schema '" + schema + "'" : ""));
                }
                if (!kindMatches(found, wantedKind)) {
                    throw new BridgeException("'" + objectName + "' is a " + found.getClass().getSimpleName()
                        + ", not a " + wantedKind);
                }
                throw new BridgeException("'" + objectName + "' does not expose DDL on this driver");
            }
            // 3. Triggers belong to a table
            if (table == null || table.isBlank()) {
                throw new BridgeException("Argument 'table' is required when kind is 'trigger'");
            }
            DBSObject scope = DBUtils.getObjectByPath(monitor, context, root,
                blankToNull(catalog), blankToNull(schema), table.trim());
            if (!(scope instanceof DBSTable dbTable)) {
                throw new BridgeException("Table '" + table + "' not found");
            }
            List<? extends DBSTrigger> triggers = dbTable.getTriggers(monitor);
            if (triggers != null) {
                for (DBSTrigger trigger : triggers) {
                    if (trigger != null && objectName.trim().equalsIgnoreCase(trigger.getName())) {
                        if (trigger instanceof DBPScriptObject script) {
                            return ddlResult(container, trigger, ddl(script, monitor));
                        }
                        throw new BridgeException("Trigger '" + objectName + "' does not expose its source on this driver");
                    }
                }
            }
            throw new BridgeException("Trigger '" + objectName + "' not found on table '" + table + "'");
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("Cannot read DDL of '" + objectName + "': " + message(e), e);
        }
    }

    private static boolean kindMatches(DBSObject object, String wantedKind) {
        return switch (wantedKind) {
            case "auto" -> true;
            case "table" -> object instanceof DBSEntity && !(object instanceof DBSTable t && safe(t::isView, false));
            case "view" -> object instanceof DBSTable t && safe(t::isView, false);
            case "procedure", "function" -> object instanceof DBSProcedure;
            case "trigger" -> object instanceof DBSTrigger;
            default -> true;
        };
    }

    private static String ddl(DBPScriptObject script, DBRProgressMonitor monitor) throws BridgeException {
        try {
            String text = script.getObjectDefinitionText(monitor, DBPScriptObject.EMPTY_OPTIONS);
            if (text == null || text.isBlank()) {
                return "-- no source returned by driver";
            }
            return ValueRenderer.truncate(text.trim(), MAX_DDL_CHARS);
        } catch (Exception e) {
            throw new BridgeException("Driver failed to generate DDL: " + message(e), e);
        }
    }

    private static Map<String, Object> ddlResult(DBPDataSourceContainer container, DBSObject object, String ddl) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connection", container.getName());
        map.put("object", DBUtils.getObjectFullId(object));
        map.put("name", object.getName());
        map.put("type", object.getClass().getSimpleName());
        map.put("ddl", ddl);
        return map;
    }

    // ------------------------------------------------------------------
    // list_procedures / list_triggers
    // ------------------------------------------------------------------

    public static Map<String, Object> listProcedures(String connectionRef, String catalog, String schema)
        throws BridgeException {
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        DBCExecutionContext context = executionContext(dataSource, true);
        DBSObjectContainer root = rootContainer(dataSource);
        try {
            DBSObject scope = DBUtils.getObjectByPath(monitor, context, root,
                blankToNull(catalog), blankToNull(schema), null);
            if (scope == null) {
                throw new BridgeException("Schema '" + schema + "' not found");
            }
            if (!(scope instanceof DBSProcedureContainer procedures)) {
                throw new BridgeException("'" + scope.getName() + "' does not contain procedures on this driver");
            }
            Collection<? extends DBSProcedure> raw = procedures.getProcedures(monitor);
            List<Map<String, Object>> result = new ArrayList<>();
            boolean truncated = false;
            if (raw != null) {
                for (DBSProcedure procedure : raw) {
                    if (procedure == null) {
                        continue;
                    }
                    if (result.size() >= MAX_CHILDREN) {
                        truncated = true;
                        break;
                    }
                    result.add(describeProcedure(procedure, monitor));
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("connection", container.getName());
            map.put("scope", displayPath(root, scope));
            map.put("total", raw == null ? 0 : raw.size());
            map.put("truncated", truncated);
            map.put("procedures", result);
            return map;
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("Cannot list procedures: " + message(e), e);
        }
    }

    private static Map<String, Object> describeProcedure(DBSProcedure procedure, DBRProgressMonitor monitor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", procedure.getName());
        put(map, "type", safe(() -> String.valueOf(procedure.getProcedureType())));
        put(map, "description", safe(procedure::getDescription));
        List<Map<String, Object>> params = new ArrayList<>();
        try {
            Collection<? extends DBSProcedureParameter> raw = procedure.getParameters(monitor);
            if (raw != null) {
                for (DBSProcedureParameter param : raw) {
                    if (param == null) {
                        continue;
                    }
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", param.getName());
                    put(p, "kind", safe(() -> String.valueOf(param.getParameterKind())));
                    put(p, "type", safe(() -> param.getParameterType().getTypeName()));
                    put(p, "fullType", safe(() -> param.getParameterType().getFullTypeName()));
                    params.add(p);
                }
            }
        } catch (Exception e) {
            McpPlugin.logError("Cannot read parameters of '" + procedure.getName() + "'", e);
        }
        map.put("parameters", params);
        map.put("sourceAvailable", procedure instanceof DBPScriptObject);
        return map;
    }

    public static Map<String, Object> listTriggers(String connectionRef, String catalog, String schema, String table)
        throws BridgeException {
        if (table == null || table.isBlank()) {
            throw new BridgeException("Missing required argument 'table' (triggers are listed per table)");
        }
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        DBCExecutionContext context = executionContext(dataSource, true);
        DBSObjectContainer root = rootContainer(dataSource);
        try {
            DBSObject found = DBUtils.getObjectByPath(monitor, context, root,
                blankToNull(catalog), blankToNull(schema), table.trim());
            if (!(found instanceof DBSTable dbTable)) {
                throw new BridgeException("Table '" + table + "' not found");
            }
            List<? extends DBSTrigger> raw = dbTable.getTriggers(monitor);
            List<Map<String, Object>> result = new ArrayList<>();
            if (raw != null) {
                for (DBSTrigger trigger : raw) {
                    if (trigger == null) {
                        continue;
                    }
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", trigger.getName());
                    put(t, "description", safe(trigger::getDescription));
                    t.put("sourceAvailable", trigger instanceof DBPScriptObject);
                    result.add(t);
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("connection", container.getName());
            map.put("table", DBUtils.getObjectFullId(dbTable));
            map.put("triggers", result);
            return map;
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("Cannot list triggers of '" + table + "': " + message(e), e);
        }
    }

    // ------------------------------------------------------------------
    // execute_sql
    // ------------------------------------------------------------------

    public static Map<String, Object> querySql(String connectionRef, String sql, int maxRows) throws BridgeException {
        SqlGuard.CheckResult check = SqlGuard.check(sql, false); // query_sql is always read-only
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        requireRead(container);
        DBPDataSource dataSource = ensureDataSource(container, monitor());
        Map<String, Object> result = runQuery(dataSource, check.statements().get(0), maxRows);
        result.put("connection", container.getName());
        result.put("mode", "query");
        return result;
    }

    public static Map<String, Object> executeSql(String connectionRef, String sql) throws BridgeException {
        SqlGuard.CheckResult check = SqlGuard.check(sql, true);
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        requireWrite(container);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ensureDataSource(container, monitor);
        String singleStatement = check.statements().get(0);

        if (check.kind() == SqlGuard.Kind.READ) {
            // Trusted path (writes are enabled): plain isolated session, no
            // read-only transaction, so locking reads keep working.
            Map<String, Object> result = runPlainQuery(dataSource, singleStatement);
            result.put("connection", container.getName());
            result.put("mode", "query");
            return result;
        }

        DBCExecutionContext context = null;
        DBCSession session = null;
        DBCStatement statement = null;
        try {
            context = isolatedContext(dataSource, "MCP execute_sql");
            session = context.openSession(monitor, DBCExecutionPurpose.USER, "MCP execute_sql");
            statement = session.prepareStatement(DBCStatementType.SCRIPT, singleStatement, false, false, false);
            applyTimeout(statement);
            boolean hasResultSet = statement.executeStatement();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connection", container.getName());
            result.put("mode", hasResultSet ? "query" : "update");
            if (hasResultSet) {
                DBCResultSet resultSet = statement.openResultSet();
                try {
                    fillRows(result, resultSet, MAX_ROWS);
                } finally {
                    closeQuietly(resultSet);
                }
            } else {
                long count = statement.getUpdateRowCount();
                result.put("updateCount", count);
                result.put("committed", commitIfNeeded(container, session));
            }
            try {
                result.put("hasMoreResults", statement.nextResults());
            } catch (Exception e) {
                result.put("hasMoreResults", false);
            }
            return result;
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("SQL execution failed: " + message(e), e);
        } finally {
            closeQuietly(statement);
            closeQuietly(session);
            closeQuietly(context);
        }
    }

    /**
     * Guarded read path for {@code query_sql}: isolated session, native
     * read-only transaction where supported, timeout, row cap, rollback.
     * Any protection failure aborts the query (fail closed).
     */
    private static Map<String, Object> runQuery(DBPDataSource dataSource, String sql, int maxRows)
        throws BridgeException {
        DBCExecutionContext context = null;
        DBCSession session = null;
        DBCStatement statement = null;
        try {
            context = isolatedContext(dataSource, "MCP query_sql");
            session = context.openSession(monitor(), DBCExecutionPurpose.USER, "MCP query_sql");
            Optional<Connection> jdbc = rawConnection(session);
            ReadOnlyStrategy strategy = ReadOnlyStrategies.forProduct(productName(jdbc));
            boolean enforced = false;
            if (jdbc.isPresent()) {
                strategy.begin(jdbc.get());
                enforced = true;
            }
            try {
                int effectiveMax = Math.min(Math.max(maxRows, 1), MAX_ROWS);
                statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
                applyTimeout(statement);
                statement.setLimit(0, effectiveMax);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("mode", "query");
                result.put("limit", effectiveMax);
                result.put("protection", protectionInfo(strategy, enforced));
                if (!statement.executeStatement()) {
                    result.put("columns", List.of());
                    result.put("rows", List.of());
                    result.put("rowCount", 0);
                    result.put("truncated", false);
                    result.put("updateCount", statement.getUpdateRowCount());
                    return result;
                }
                DBCResultSet resultSet = statement.openResultSet();
                try {
                    fillRows(result, resultSet, effectiveMax);
                } finally {
                    closeQuietly(resultSet);
                }
                return result;
            } finally {
                if (enforced) {
                    strategy.end(jdbc.get());
                }
            }
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("Query failed: " + message(e), e);
        } finally {
            closeQuietly(statement);
            closeQuietly(session);
            closeQuietly(context);
        }
    }

    /**
     * Plain read path for {@code execute_sql} in read/write mode:
     * isolated session, no read-only transaction.
     */
    private static Map<String, Object> runPlainQuery(DBPDataSource dataSource, String sql) throws BridgeException {
        DBCExecutionContext context = null;
        DBCSession session = null;
        DBCStatement statement = null;
        try {
            context = isolatedContext(dataSource, "MCP execute_sql");
            session = context.openSession(monitor(), DBCExecutionPurpose.USER, "MCP execute_sql");
            statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
            applyTimeout(statement);
            statement.setLimit(0, MAX_ROWS);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "query");
            result.put("limit", MAX_ROWS);
            if (!statement.executeStatement()) {
                result.put("columns", List.of());
                result.put("rows", List.of());
                result.put("rowCount", 0);
                result.put("truncated", false);
                result.put("updateCount", statement.getUpdateRowCount());
                return result;
            }
            DBCResultSet resultSet = statement.openResultSet();
            try {
                fillRows(result, resultSet, MAX_ROWS);
            } finally {
                closeQuietly(resultSet);
            }
            return result;
        } catch (BridgeException e) {
            throw e;
        } catch (Exception e) {
            throw new BridgeException("Query failed: " + message(e), e);
        } finally {
            closeQuietly(statement);
            closeQuietly(session);
            closeQuietly(context);
        }
    }

    private static Optional<Connection> rawConnection(DBCSession session) {
        try {
            if (session instanceof JDBCSession jdbcSession) {
                Connection connection = jdbcSession.getOriginal();
                if (connection != null && !connection.isClosed()) {
                    return Optional.of(connection);
                }
            }
        } catch (Exception e) {
            McpPlugin.logInfo("MCP: no raw JDBC connection available: " + message(e));
        }
        return Optional.empty();
    }

    private static String productName(Optional<Connection> jdbc) {
        if (jdbc.isEmpty()) {
            return null;
        }
        try {
            return jdbc.get().getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            return null; // unknown product -> generic strategy
        }
    }

    private static Map<String, Object> protectionInfo(ReadOnlyStrategy strategy, boolean enforced) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("level", strategy.protection().id());
        info.put("strategy", strategy.id());
        info.put("enforced", enforced);
        if (!enforced) {
            info.put("detail", "No JDBC connection available; SQL validation only.");
        } else if (strategy.protection() == ProtectionLevel.BEST_EFFORT) {
            info.put("detail", "Driver hint plus validation and rollback; not database-enforced.");
        }
        return info;
    }

    private static void applyTimeout(DBCStatement statement) {
        try {
            int timeout = new McpPreferences().getQueryTimeoutSec();
            if (timeout > 0) {
                statement.setStatementTimeout(timeout);
            }
        } catch (Exception e) {
            McpPlugin.logInfo("MCP: query timeout not applied: " + message(e));
        }
    }

    private static void fillRows(Map<String, Object> result, DBCResultSet resultSet, int limit) throws BridgeException {
        try {
            if (resultSet == null) {
                result.put("columns", List.of());
                result.put("rows", List.of());
                result.put("rowCount", 0);
                result.put("truncated", false);
                return;
            }
            List<? extends DBCAttributeMetaData> attributes = resultSet.getMeta().getAttributes();
            List<Map<String, Object>> columns = new ArrayList<>();
            for (DBCAttributeMetaData attr : attributes) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", attr.getName());
                put(c, "label", safe(attr::getLabel));
                put(c, "type", safe(attr::getTypeName));
                columns.add(c);
            }
            result.put("columns", columns);
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            while (resultSet.nextRow()) {
                if (rows.size() >= limit) {
                    truncated = true;
                    break;
                }
                List<Object> row = new ArrayList<>(attributes.size());
                for (int i = 0; i < attributes.size(); i++) {
                    Object value;
                    try {
                        value = resultSet.getAttributeValue(i);
                    } catch (Exception e) {
                        value = "<unreadable: " + e.getMessage() + ">";
                    }
                    row.add(ValueRenderer.render(value));
                }
                rows.add(row);
            }
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("truncated", truncated);
        } catch (Exception e) {
            throw new BridgeException("Cannot read result rows: " + message(e), e);
        }
    }

    private static String commitIfNeeded(DBPDataSourceContainer container, DBCSession session) {
        try {
            DBCTransactionManager manager = DBUtils.getAdapter(DBCTransactionManager.class, session);
            if (manager == null) {
                manager = DBUtils.getAdapter(DBCTransactionManager.class, session.getExecutionContext());
            }
            if (manager == null) {
                manager = DBUtils.getAdapter(DBCTransactionManager.class, container);
            }
            if (manager == null || !manager.isSupportsTransactions()) {
                return "unknown (no transaction manager)";
            }
            if (manager.isAutoCommit()) {
                return "auto-commit";
            }
            manager.commit(session);
            return "committed";
        } catch (Exception e) {
            McpPlugin.logError("MCP write executed but commit failed", e);
            return "COMMIT FAILED: " + message(e);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String message(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                McpPlugin.logError("Failed to close database resource", e);
            }
        }
    }
}
