package dev.astronauta.dbeaverMCP.server.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.DBeaverBridge;

public final class ListConnectionsTool implements McpTool {

    @Override
    public String name() {
        return "list_connections";
    }

    @Override
    public String description() {
        return "Lists the DBeaver connections exposed over MCP.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Schema.object().build();
    }

    @Override
    public Object call(ToolArgs args) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connections", DBeaverBridge.listConnections());
        return result;
    }
}
