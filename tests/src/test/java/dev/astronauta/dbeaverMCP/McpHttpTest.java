package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the same MCP + Jetty wiring McpServerManager uses (minus DBeaver)
 * and speaks Streamable HTTP to it. Catches SDK misuse and missing
 * embedded dependencies.
 */
public class McpHttpTest {

    private String sessionId;

    @Test
    public void fullRoundTrip() throws Exception {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
        JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();

        HttpServletStreamableServerTransportProvider transport =
            HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint("/mcp")
                .build();

        McpSchema.Tool echo = McpSchema.Tool.builder("echo", mapper,
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}")
            .description("Echoes input")
            .build();

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("test-server", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .jsonMapper(mapper)
            .jsonSchemaValidator(validator)
            .toolCall(echo, (exchange, req) -> McpSchema.CallToolResult.builder()
                .addTextContent("echo:" + req.arguments().get("text"))
                .isError(false)
                .build())
            .build();

        Server jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        jetty.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder("mcp", transport), "/mcp");
        jetty.setHandler(context);
        jetty.start();
        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();

        try {
            HttpClient http = HttpClient.newHttpClient();
            String init = post(http, port,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}");
            assertTrue(init, init.contains("test-server"));
            assertNotNull(sessionId);

            post(http, port, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            String tools = post(http, port,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            assertTrue(tools, tools.contains("\"echo\""));

            String call = post(http, port,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                    + "\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}}");
            assertTrue(call, call.contains("echo:hello"));
        } finally {
            server.close();
            jetty.stop();
        }
    }

    private String post(HttpClient http, int port, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(id -> sessionId = id);
        response.headers().firstValue("mcp-session-id").ifPresent(id -> sessionId = id);
        assertTrue("HTTP " + response.statusCode() + ": " + response.body(),
            response.statusCode() == 200 || response.statusCode() == 202);
        return response.body() == null ? "" : response.body();
    }
}
