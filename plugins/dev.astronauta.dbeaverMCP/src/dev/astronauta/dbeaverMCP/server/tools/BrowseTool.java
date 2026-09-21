package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class BrowseTool implements McpTool {

    @Override
    public String name() {
        return "browse";
    }

    @Override
    public String description() {
        return "Browses the object tree of a connection. Omit 'path' for the root"
            + " (catalogs/schemas), or pass a slash-separated path like 'mydb/public'.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("path", Schema.string("Slash-separated object path, e.g. 'catalog/schema'"))
            .required("connection")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.browse(args.required("connection"), args.optional("path"));
    }
}
