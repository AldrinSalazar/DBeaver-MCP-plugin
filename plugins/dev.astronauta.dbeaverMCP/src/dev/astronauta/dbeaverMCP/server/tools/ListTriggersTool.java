package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class ListTriggersTool implements McpTool {

    @Override
    public String name() {
        return "list_triggers";
    }

    @Override
    public String description() {
        return "Lists the triggers of a table.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("table", Schema.string("Table name"))
            .property("schema", Schema.string(null))
            .property("catalog", Schema.string(null))
            .required("connection", "table")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.listTriggers(
            args.required("connection"), args.optional("catalog"), args.optional("schema"), args.optional("table"));
    }
}
