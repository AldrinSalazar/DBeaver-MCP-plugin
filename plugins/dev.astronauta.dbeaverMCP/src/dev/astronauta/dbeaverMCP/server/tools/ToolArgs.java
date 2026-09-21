package dev.astronauta.dbeaverMCP.server.tools;

import java.util.Map;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Typed access to a tool call's arguments.
 */
public final class ToolArgs {

    private final Map<String, Object> args;

    private ToolArgs(Map<String, Object> args) {
        this.args = args;
    }

    public static ToolArgs of(McpSchema.CallToolRequest request) {
        Map<String, Object> arguments = request.arguments();
        return new ToolArgs(arguments != null ? arguments : Map.of());
    }

    /**
     * Optional string argument; blank and missing values become null.
     */
    public String optional(String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        return String.valueOf(value);
    }

    public String required(String key) throws BridgeException {
        String value = optional(key);
        if (value == null) {
            throw new BridgeException("Missing required argument '" + key + "'");
        }
        return value;
    }

    public int optionalInt(String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
