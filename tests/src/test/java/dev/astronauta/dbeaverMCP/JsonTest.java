package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.server.Json;
import org.junit.Test;

public class JsonTest {

    @Test
    public void primitives() {
        assertEquals("null", Json.stringify(null));
        assertEquals("1", Json.stringify(1));
        assertEquals("true", Json.stringify(true));
        assertEquals("\"a\"", Json.stringify("a"));
        assertEquals("null", Json.stringify(Double.NaN));
    }

    @Test
    public void escaping() {
        assertEquals("\"x\\\"y\\n\\t\\\\\"", Json.stringify("x\"y\n\t\\"));
    }

    @Test
    public void mapAndList() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", "x\"y\n");
        map.put("c", null);
        assertEquals("{\"a\":1,\"b\":\"x\\\"y\\n\",\"c\":null}", Json.stringify(map));
        assertEquals("[1,\"a\",true]", Json.stringify(List.of(1, "a", true)));
        assertEquals("{\"rows\":[[1,2],[3]]}", Json.stringify(Map.of("rows", List.of(List.of(1, 2), List.of(3)))));
    }
}
