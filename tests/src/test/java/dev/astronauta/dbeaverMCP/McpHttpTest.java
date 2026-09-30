package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.server.McpHttpServer;
import dev.astronauta.dbeaverMCP.server.tools.McpTool;
import dev.astronauta.dbeaverMCP.server.tools.Schema;
import dev.astronauta.dbeaverMCP.server.tools.ToolArgs;
import org.junit.Test;

/** Exercises the production transport, authentication, schema validation and error dispatch. */
public class McpHttpTest {
    private static final String INITIALIZE = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18","capabilities":{},
        "clientInfo":{"name":"test","version":"1.0"}}}
        """;
    private String sessionId;

    private static McpTool tool(String toolName) {
        return new McpTool() {
            public String name() { return toolName; }
            public String description() { return "Test tool"; }
            public Map<String, Object> inputSchema() {
                return Schema.object().property("text", Schema.string(null)).required("text").build();
            }
            public Object call(ToolArgs args) throws Exception {
                if (toolName.equals("failure")) {
                    throw new BridgeException("Commit failed; transaction outcome is unknown");
                }
                if (toolName.equals("oversized")) {
                    return "x".repeat(McpHttpServer.MAX_TOOL_RESULT_BYTES + 1);
                }
                return Map.of("echo", args.required("text"));
            }
        };
    }

    @Test
    public void authenticatedRoundTripAndToolErrorsUseProductionWiring() throws Exception {
        try (McpHttpServer server = McpHttpServer.start(
                new McpHttpServer.Configuration("127.0.0.1", 0, true, "test-token"),
                List.of(tool("echo"), tool("failure"), tool("oversized")), "Test server")) {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            int port = server.port();
            assertEquals(401, send(http, port, INITIALIZE, null, null).statusCode());
            assertEquals(401, send(http, port, INITIALIZE, "Bearer wrong-token", null).statusCode());
            assertEquals(403, send(http, port, INITIALIZE, "Bearer test-token", "https://evil.example").statusCode());
            String init = post(http, port, INITIALIZE);
            assertTrue(init, init.contains("dbeaver-mcp"));
            assertNotNull(sessionId);
            post(http, port, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            String tools = post(http, port, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            assertTrue(tools, tools.contains("\"echo\""));
            String echo = post(http, port, call(3, "echo"));
            assertTrue(echo, echo.contains("hello"));
            assertTrue(echo, echo.contains("\"isError\":false"));
            String failure = post(http, port, call(4, "failure"));
            assertTrue(failure, failure.contains("\"isError\":true"));
            assertTrue(failure, failure.contains("transaction outcome is unknown"));
            String oversized = post(http, port, call(5, "oversized"));
            assertTrue(oversized, oversized.contains("\"isError\":true"));
            assertTrue(oversized, oversized.contains("size limit"));
        }
    }

    @Test
    public void disablingAuthenticationStillValidatesOrigins() throws Exception {
        try (McpHttpServer server = McpHttpServer.start(
                new McpHttpServer.Configuration("127.0.0.1", 0, false, ""), List.of(), "Test server")) {
            HttpClient http = HttpClient.newHttpClient();
            assertEquals(403, send(http, server.port(), INITIALIZE, null, "https://evil.example").statusCode());
            assertEquals(200, send(http, server.port(), INITIALIZE, null, "http://localhost:3000").statusCode());
        }
    }

    private static String call(int id, String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
            + "\",\"arguments\":{\"text\":\"hello\"}}}";
    }

    private String post(HttpClient http, int port, String body) throws Exception {
        HttpResponse<String> response = send(http, port, body, "Bearer test-token", null);
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        assertTrue("HTTP " + response.statusCode() + ": " + response.body(),
            response.statusCode() == 200 || response.statusCode() == 202);
        return response.body() == null ? "" : response.body();
    }

    private HttpResponse<String> send(HttpClient http, int port, String body, String authorization, String origin)
        throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) { builder.header("Authorization", authorization); }
        if (origin != null) { builder.header("Origin", origin); }
        if (sessionId != null) { builder.header("Mcp-Session-Id", sessionId); }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
