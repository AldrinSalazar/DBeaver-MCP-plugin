package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class GetDdlTool implements McpTool {

    @Override
    public String name() {
        return "get_ddl";
    }

    @Override
    public String description() {
        return "Returns the DDL/source of a table, view, procedure, function or trigger.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("object", Schema.string("Object name"))
            .property("schema", Schema.string(null))
            .property("catalog", Schema.string(null))
            .property("kind", Schema.stringEnum(null, "auto",
                "auto", "table", "view", "procedure", "function", "trigger"))
            .property("table", Schema.string("Owning table, required when kind is 'trigger'"))
            .required("connection", "object")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.getDdl(
            args.required("connection"), args.optional("catalog"), args.optional("schema"),
            args.optional("object"), args.optional("kind"), args.optional("table"));
    }
}
