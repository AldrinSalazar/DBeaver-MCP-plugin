package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

/**
 * One MCP tool: a static definition (name, description, input schema)
 * plus a handler. Handlers return plain data (maps, lists, strings,
 * numbers, booleans, null) which is serialized to JSON for the client.
 */
public interface McpTool {

    String name();

    String description();

    /**
     * JSON Schema describing the tool input, as a JSON-compatible map.
     * Build it with {@link Schema}.
     */
    Map<String, Object> inputSchema();

    Object call(ToolArgs args) throws Exception;
}
