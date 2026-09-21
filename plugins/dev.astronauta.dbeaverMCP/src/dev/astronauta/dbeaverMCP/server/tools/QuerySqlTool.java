package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class QuerySqlTool implements McpTool {

    @Override
    public String name() {
        return "query_sql";
    }

    @Override
    public String description() {
        return "Executes a single read-only SQL statement (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN/VALUES)."
            + " Always runs with read-only protections; writes are rejected.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("sql", Schema.string("Single read-only SQL statement"))
            .property("maxRows", Schema.integer("Max rows to return", 1, 5000, 100))
            .required("connection", "sql")
            .build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        return DBeaverBridge.querySql(
            args.required("connection"), args.required("sql"), args.optionalInt("maxRows", 100));
    }
}
