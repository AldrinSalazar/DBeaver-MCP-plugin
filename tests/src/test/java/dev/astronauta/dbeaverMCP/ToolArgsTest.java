package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.HashMap;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.server.tools.ToolArgs;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Test;

public class ToolArgsTest {

    private static ToolArgs args(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return ToolArgs.of(new McpSchema.CallToolRequest("tool", map, null));
    }

    @Test
    public void strings() throws Exception {
        assertEquals("x", args("a", "x").required("a"));
        assertNull(args().optional("missing"));
        assertNull(args("a", "  ").optional("a"));
        assertThrows(BridgeException.class, () -> args().required("missing"));
    }

    @Test
    public void ints() {
        assertEquals(5, args("n", 5).optionalInt("n", 1));
        assertEquals(7, args("n", "7").optionalInt("n", 1));
        assertEquals(1, args().optionalInt("n", 1));
        assertEquals(1, args("n", "junk").optionalInt("n", 1));
    }
}
