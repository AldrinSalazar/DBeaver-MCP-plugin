package dev.astronauta.dbeaverMCP.server;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.astronauta.dbeaverMCP.McpPlugin;
import dev.astronauta.dbeaverMCP.McpPreferences;
import dev.astronauta.dbeaverMCP.db.BridgeException;
import dev.astronauta.dbeaverMCP.server.tools.BrowseTool;
import dev.astronauta.dbeaverMCP.server.tools.DescribeTableTool;
import dev.astronauta.dbeaverMCP.server.tools.ExecuteSqlTool;
import dev.astronauta.dbeaverMCP.server.tools.GetDdlTool;
import dev.astronauta.dbeaverMCP.server.tools.ListConnectionsTool;
import dev.astronauta.dbeaverMCP.server.tools.ListProceduresTool;
import dev.astronauta.dbeaverMCP.server.tools.ListTriggersTool;
import dev.astronauta.dbeaverMCP.server.tools.McpTool;
import dev.astronauta.dbeaverMCP.server.tools.QuerySqlTool;
import dev.astronauta.dbeaverMCP.server.tools.ToolArgs;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the embedded Jetty + MCP Streamable HTTP server.
 * Loopback host and Bearer token by default (both configurable); rejects
 * requests whose Origin header is not local (DNS-rebinding protection).
 * The exposed tools live in {@link dev.astronauta.dbeaverMCP.server.tools}.
 */
public final class McpServerManager {

    public static final String SERVER_NAME = "dbeaver-mcp";
    public static final String SERVER_VERSION = "1.0.0";
    public static final String MCP_PATH = "/mcp";

    private static final String INSTRUCTIONS_BASE = """
        DBeaver MCP server. Exposes the DBeaver connections the user explicitly enabled.
        Start with list_connections to see available connection ids, then browse catalogs/schemas/tables,
        describe_table for columns/keys/indexes, get_ddl for view/procedure/trigger/table sources.
        """;

    private static final List<McpTool> METADATA_TOOLS = List.of(
        new ListConnectionsTool(),
        new BrowseTool(),
        new DescribeTableTool(),
        new GetDdlTool(),
        new ListProceduresTool(),
        new ListTriggersTool());

    /**
     * Tools advertised for each access mode. {@code execute_sql} only exists
     * in read/write mode; {@code query_sql} in read-only and read/write mode.
     */
    private static List<McpTool> toolsFor(McpPreferences.AccessMode mode) {
        List<McpTool> tools = new ArrayList<>(METADATA_TOOLS);
        if (mode == McpPreferences.AccessMode.READ_ONLY || mode == McpPreferences.AccessMode.READ_WRITE) {
            tools.add(new QuerySqlTool());
        }
        if (mode == McpPreferences.AccessMode.READ_WRITE) {
            tools.add(new ExecuteSqlTool());
        }
        return tools;
    }

    private static String instructionsFor(McpPreferences.AccessMode mode) {
        return switch (mode) {
            case METADATA_ONLY -> INSTRUCTIONS_BASE + "No SQL execution is available in metadata-only mode.\n";
            case READ_ONLY -> INSTRUCTIONS_BASE
                + "Use query_sql for reads (always read-only, up to 5000 rows per call).\n";
            case READ_WRITE -> INSTRUCTIONS_BASE
                + "Use query_sql for reads (always read-only) and execute_sql for writes"
                + " (one single statement per call).\n";
        };
    }

    private static final McpServerManager INSTANCE = new McpServerManager();

    private Server jetty;
    private McpSyncServer mcpServer;
    private volatile String status = "Stopped";

    private McpServerManager() {
    }

    public static McpServerManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean isRunning() {
        return jetty != null && jetty.isRunning() && mcpServer != null;
    }

    public String getStatus() {
        return status;
    }

    public synchronized void start() throws Exception {
        if (isRunning()) {
            return;
        }
        McpPreferences prefs = new McpPreferences();
        int port = prefs.getPort();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        String bindHost = prefs.getBindHost();
        boolean authEnabled = prefs.isAuthEnabled();
        String token = prefs.ensureToken();

        // Bundle classloader for any ServiceLoader lookups on this thread
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(McpServerManager.class.getClassLoader());
        try {
            McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());
            JsonSchemaValidator validator = new JacksonJsonSchemaValidatorSupplier().get();

            HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                    .jsonMapper(jsonMapper)
                    .mcpEndpoint(MCP_PATH)
                    .securityValidator(headers -> validateHeaders(headers, authEnabled, token))
                    .build();

            var spec = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions(instructionsFor(prefs.getAccessMode()))
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(validator);
            for (McpTool tool : toolsFor(prefs.getAccessMode())) {
                spec = spec.toolCall(toMcpTool(tool),
                    (exchange, request) -> handle(() -> tool.call(ToolArgs.of(request))));
            }
            mcpServer = spec.build();

            jetty = new Server();
            ServerConnector connector = new ServerConnector(jetty);
            connector.setHost(bindHost);
            connector.setPort(port);
            jetty.addConnector(connector);
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            context.addServlet(new ServletHolder("mcp", transport), MCP_PATH);
            jetty.setHandler(context);
            jetty.start();

            status = "Running on http://" + bindHost + ":" + port + MCP_PATH
                + (authEnabled ? "" : " (auth disabled)");
            McpPlugin.logInfo("MCP server started: " + status);
        } catch (Exception e) {
            shutdownQuietly();
            status = "Failed: " + e.getMessage();
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    public synchronized void stop() {
        shutdownQuietly();
        status = "Stopped";
    }

    public synchronized void restart() throws Exception {
        stop();
        McpPreferences prefs = new McpPreferences();
        if (prefs.isServerEnabled()) {
            start();
        }
    }

    private void shutdownQuietly() {
        if (mcpServer != null) {
            try {
                mcpServer.close();
            } catch (Exception e) {
                McpPlugin.logError("Error closing MCP server", e);
            }
            mcpServer = null;
        }
        if (jetty != null) {
            try {
                jetty.stop();
            } catch (Exception e) {
                McpPlugin.logError("Error stopping MCP HTTP server", e);
            }
            jetty = null;
        }
    }

    private void validateHeaders(Map<String, List<String>> headers, boolean authEnabled, String expectedToken)
        throws ServerTransportSecurityException {
        String origin = firstHeader(headers, "origin");
        if (origin != null && !isLocalOrigin(origin)) {
            throw new ServerTransportSecurityException(403, "Forbidden origin: " + origin);
        }
        if (!authEnabled) {
            return;
        }
        String authorization = firstHeader(headers, "authorization");
        String expected = "Bearer " + expectedToken;
        if (authorization == null || !MessageDigest.isEqual(
            authorization.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ServerTransportSecurityException(401, "Missing or invalid Authorization header");
        }
    }

    /**
     * True if a browser Origin header points at the local machine:
     * http(s) scheme, loopback host, any port. Anything else - lookalike
     * hosts like {@code localhost.evil.com}, other schemes, unparseable or
     * {@code null} origins - is rejected (fail closed). Non-browser MCP
     * clients send no Origin header at all and never reach this check.
     */
    static boolean isLocalOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return switch (host) {
            case "localhost", "127.0.0.1", "::1" -> true;
            default -> false;
        };
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Tool plumbing
    // ------------------------------------------------------------------

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
            return McpSchema.CallToolResult.builder()
                .addTextContent(Json.stringify(call.call()))
                .isError(false)
                .build();
        } catch (BridgeException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            McpPlugin.logError("MCP tool failed", e);
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
