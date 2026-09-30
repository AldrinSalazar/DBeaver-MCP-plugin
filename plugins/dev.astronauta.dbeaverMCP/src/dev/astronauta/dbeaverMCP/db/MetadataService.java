package dev.astronauta.dbeaverMCP.db;

import static dev.astronauta.dbeaverMCP.db.MetadataMapper.displayPath;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.describeObject;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.describeAttribute;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.describeConstraint;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.describeIndex;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.catalogName;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.schemaName;
import static dev.astronauta.dbeaverMCP.db.MetadataMapper.describeProcedure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPScriptObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedure;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.model.struct.rdb.DBSTrigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads DBeaver metadata without owning SQL execution or access configuration. */
public final class MetadataService {
    private static final Logger LOG = LoggerFactory.getLogger(MetadataService.class);


    public static final int MAX_CHILDREN = 2000;
    public static final int MAX_DDL_CHARS = 500_000;

    private final ConnectionCatalog connections;
    private final AccessPolicy policy;

    public MetadataService(ConnectionCatalog connections, AccessPolicy policy) {
        this.connections = connections;
        this.policy = policy;
    }

    private DBPDataSourceContainer resolveConnection(String ref) throws BridgeException {
        DBPDataSourceContainer connection = connections.resolveConnection(ref, policy);
        policy.requireMetadata(connection.getId());
        return connection;
    }

    private static DBRProgressMonitor monitor() {
        return new VoidProgressMonitor();
    }

    private static DBCExecutionContext executionContext(DBPDataSource dataSource, boolean meta) {
        return dataSource.getDefaultInstance().getDefaultContext(monitor(), meta);
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

    public Map<String, Object> browse(String connectionRef, String path) throws BridgeException {
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(container, monitor);
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
                    sorted.removeIf(java.util.Objects::isNull);
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

    // ------------------------------------------------------------------
    // describe_table
    // ------------------------------------------------------------------

    public Map<String, Object> describeTable(String connectionRef, String catalog, String schema, String table)
        throws BridgeException {
        if (table == null || table.isBlank()) {
            throw new BridgeException("Missing required argument 'table'");
        }
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(container, monitor);
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
        List<String> warnings = new ArrayList<>();
        result.put("warnings", warnings);
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
            LOG.error("Cannot read constraints of '" + table + "'", e);
            warnings.add("Constraints could not be read: " + message(e));
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
                LOG.error("Cannot read indexes of '" + table + "'", e);
                warnings.add("Indexes could not be read: " + message(e));
            }
            result.put("indexes", indexes);
        }

        result.put("ddlAvailable", entity instanceof DBPScriptObject);
        return result;
    }

    // ------------------------------------------------------------------
    // get_ddl
    // ------------------------------------------------------------------

    public Map<String, Object> getDdl(String connectionRef, String catalog, String schema,
            String objectName, String kind, String table) throws BridgeException {
        if (objectName == null || objectName.isBlank()) {
            throw new BridgeException("Missing required argument 'object'");
        }
        String wantedKind = kind == null || kind.isBlank() ? "auto" : kind.trim().toLowerCase(java.util.Locale.ROOT);
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(container, monitor);
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
            default -> false;
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

    public Map<String, Object> listProcedures(String connectionRef, String catalog, String schema)
        throws BridgeException {
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(container, monitor);
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

    public Map<String, Object> listTriggers(String connectionRef, String catalog, String schema, String table)
        throws BridgeException {
        if (table == null || table.isBlank()) {
            throw new BridgeException("Missing required argument 'table' (triggers are listed per table)");
        }
        DBPDataSourceContainer container = resolveConnection(connectionRef);
        DBRProgressMonitor monitor = monitor();
        DBPDataSource dataSource = ConnectionCatalog.ensureDataSource(container, monitor);
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
}
