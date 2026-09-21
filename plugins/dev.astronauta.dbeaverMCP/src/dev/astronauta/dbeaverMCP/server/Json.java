package dev.astronauta.dbeaverMCP.server;

import tools.jackson.databind.json.JsonMapper;

/**
 * JSON serialization for MCP tool results, backed by the embedded Jackson.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static String stringify(Object value) {
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return "null";
        }
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return "null";
        }
        return MAPPER.writeValueAsString(value);
    }
}
