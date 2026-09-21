package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class ListProceduresTool implements McpTool {

    @Override
    public String name() {
        return "list_procedures";
    }

    @Override
    public String description() {
        return "Lists stored procedures and functions with their parameters.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("schema", Schema.string(null))
            .property("catalog", Schema.string(null))
            .required("connection")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.listProcedures(
            args.required("connection"), args.optional("catalog"), args.optional("schema"));
    }
}
