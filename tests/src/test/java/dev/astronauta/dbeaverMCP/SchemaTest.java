package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.server.tools.BrowseTool;
import dev.astronauta.dbeaverMCP.server.tools.Schema;
import org.junit.Test;

public class SchemaTest {

    @Test
    @SuppressWarnings("unchecked")
    public void buildsObjectSchema() {
        Map<String, Object> schema = Schema.object()
            .property("connection", Schema.string("Connection id or name"))
            .property("path", Schema.string(null))
            .required("connection")
            .build();
        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("connection"), schema.get("required"));
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(Map.of("type", "string", "description", "Connection id or name"), properties.get("connection"));
        assertEquals(Map.of("type", "string"), properties.get("path"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void toolsProduceEquivalentSchemas() {
        Map<String, Object> schema = new BrowseTool().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("connection"), schema.get("required"));
        assertEquals("string", ((Map<String, Object>) properties.get("path")).get("type"));
    }

    @Test
    public void enumAndIntegerSchemas() {
        Map<String, Object> kind = Schema.stringEnum("Kind", "auto", "auto", "table");
        assertEquals(List.of("auto", "table"), kind.get("enum"));
        assertEquals("auto", kind.get("default"));
        Map<String, Object> maxRows = Schema.integer("Max rows", 1, 5000, 100);
        assertEquals(1, maxRows.get("minimum"));
        assertEquals(5000, maxRows.get("maximum"));
        assertEquals(100, maxRows.get("default"));
    }
}
