package dev.astronauta.dbeaverMCP.server.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny builder for MCP tool input schemas, so tools declare JSON Schema
 * as typed code instead of raw JSON strings.
 */
public final class Schema {

    private Schema() {
    }

    public static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        putIfPresent(schema, "description", description);
        return schema;
    }

    public static Map<String, Object> stringEnum(String description, String defaultValue, String... values) {
        Map<String, Object> schema = string(description);
        schema.put("enum", List.of(values));
        putIfPresent(schema, "default", defaultValue);
        return schema;
    }

    public static Map<String, Object> integer(String description, int minimum, int maximum, int defaultValue) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        putIfPresent(schema, "description", description);
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        schema.put("default", defaultValue);
        return schema;
    }

    public static ObjectSchema object() {
        return new ObjectSchema();
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    public static final class ObjectSchema {
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public ObjectSchema property(String name, Map<String, Object> schema) {
            properties.put(name, schema);
            return this;
        }

        public ObjectSchema required(String... names) {
            required.addAll(List.of(names));
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", new LinkedHashMap<>(properties));
            if (!required.isEmpty()) {
                schema.put("required", new ArrayList<>(required));
            }
            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
