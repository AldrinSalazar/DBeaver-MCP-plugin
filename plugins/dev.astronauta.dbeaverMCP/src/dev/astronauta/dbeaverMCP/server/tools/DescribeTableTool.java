package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class DescribeTableTool implements McpTool {

    @Override
    public String name() {
        return "describe_table";
    }

    @Override
    public String description() {
        return "Describes a table or view: columns with types, constraints, foreign keys and indexes.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("table", Schema.string("Table or view name"))
            .property("schema", Schema.string(null))
            .property("catalog", Schema.string(null))
            .required("connection", "table")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.describeTable(
            args.required("connection"), args.optional("catalog"), args.optional("schema"), args.optional("table"));
    }
}
