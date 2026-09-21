package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class ExecuteSqlTool implements McpTool {

    @Override
    public String name() {
        return "execute_sql";
    }

    @Override
    public String description() {
        return "Executes a single SQL statement, reads or writes."
            + " Only exposed when read/write mode is enabled in preferences.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("sql", Schema.string("Single SQL statement to execute"))
            .required("connection", "sql")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.executeSql(args.required("connection"), args.required("sql"));
    }
}
