package dev.astronauta.dbeaverMCP.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.server.tools.McpTool;
import dev.astronauta.dbeaverMCP.server.tools.ToolArgs;
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
import org.osgi.framework.FrameworkUtil;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Production MCP/Jetty wiring, also used by the HTTP integration tests. */
public final class McpHttpServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(McpHttpServer.class);
    public static final int MAX_TOOL_RESULT_BYTES = 3 * 1024 * 1024;

    public record Configuration(String host, int port, boolean authEnabled, String token) {
        public Configuration {
            if (authEnabled && (token == null || token.isBlank())) {
                throw new IllegalArgumentException("A bearer token is required when authentication is enabled");
            }
        }
    }

    private final Server jetty;
    private final McpSyncServer mcp;

    private McpHttpServer(Server jetty, McpSyncServer mcp) {
        this.jetty = jetty;
        this.mcp = mcp;
    }

    public static McpHttpServer start(Configuration config, List<McpTool> tools, String instructions)
        throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(McpHttpServer.class.getClassLoader());
        Server jetty = new Server();
        McpSyncServer mcp = null;
        try {
            McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
            JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();
            TransportSecurity security = new TransportSecurity(config.authEnabled(), config.token());
            var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint(McpServerManager.MCP_PATH)
                .securityValidator(security::validate)
                .build();
            var specification = McpServer.sync(transport)
                .serverInfo(McpServerManager.SERVER_NAME, bundleVersion())
                .instructions(instructions)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .jsonMapper(mapper)
                .jsonSchemaValidator(validator);
            for (McpTool tool : tools) {
                specification = specification.toolCall(toMcpTool(tool),
                    (exchange, request) -> handle(() -> tool.call(ToolArgs.of(request))));
            }
            mcp = specification.build();
            ServerConnector connector = new ServerConnector(jetty);
            connector.setHost(config.host());
            connector.setPort(config.port());
            jetty.addConnector(connector);
            var context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            context.addServlet(new ServletHolder("mcp", transport), McpServerManager.MCP_PATH);
            jetty.setHandler(context);
            jetty.start();
            return new McpHttpServer(jetty, mcp);
        } catch (Exception failure) {
            try {
                new McpHttpServer(jetty, mcp).close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public boolean isRunning() {
        return jetty.isRunning();
    }

    public int port() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    private static String bundleVersion() {
        var bundle = FrameworkUtil.getBundle(McpHttpServer.class);
        return bundle == null ? "development" : bundle.getVersion().toString();
    }

    @Override
    public void close() throws Exception {
        try {
            if (mcp != null) {
                mcp.close();
            }
        } finally {
            jetty.stop();
        }
    }

    private interface ToolCall {
        Object call() throws Exception;
    }

    private static McpSchema.Tool toMcpTool(McpTool tool) {
        return McpSchema.Tool.builder(tool.name(), tool.inputSchema())
            .description(tool.description())
            .build();
    }

    private static McpSchema.CallToolResult handle(ToolCall call) {
        try {
            String json = Json.stringify(call.call());
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_TOOL_RESULT_BYTES) {
                return error("Tool response exceeds the 3 MiB size limit. Narrow the request.");
            }
            return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(false)
                .build();
        } catch (BridgeException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            LOG.error("MCP tool failed", e);
            String message = e.getMessage();
            return error(message == null || message.isBlank()
                ? "Internal error: " + e.getClass().getSimpleName() : message);
        }
    }

    private static McpSchema.CallToolResult error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return McpSchema.CallToolResult.builder()
            .addTextContent(Json.stringify(body))
            .isError(true)
            .build();
    }
}
