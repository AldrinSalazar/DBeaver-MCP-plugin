package dev.astronauta.dbeaverMCP.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import dev.astronauta.dbeaverMCP.McpPlugin;
import dev.astronauta.dbeaverMCP.McpPreferences;
import dev.astronauta.dbeaverMCP.server.tools.BrowseTool;
import dev.astronauta.dbeaverMCP.server.tools.DescribeTableTool;
import dev.astronauta.dbeaverMCP.server.tools.ExecuteSqlTool;
import dev.astronauta.dbeaverMCP.server.tools.GetDdlTool;
import dev.astronauta.dbeaverMCP.server.tools.ListConnectionsTool;
import dev.astronauta.dbeaverMCP.server.tools.ListProceduresTool;
import dev.astronauta.dbeaverMCP.server.tools.ListTriggersTool;
import dev.astronauta.dbeaverMCP.server.tools.McpTool;
import dev.astronauta.dbeaverMCP.server.tools.QuerySqlTool;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.ISchedulingRule;

/**
 * Owns the embedded Jetty + MCP Streamable HTTP server.
 * Loopback host and Bearer token by default (both configurable); rejects
 * requests whose Origin header is not local (DNS-rebinding protection).
 * The exposed tools live in {@link dev.astronauta.dbeaverMCP.server.tools}.
 */
public final class McpServerManager {

    public static final String SERVER_NAME = "dbeaver-mcp";
    public static final String MCP_PATH = "/mcp";

    /** All queued lifecycle operations use the same rule and cannot overlap. */
    public static final ISchedulingRule LIFECYCLE_RULE = new ISchedulingRule() {
        public boolean contains(ISchedulingRule rule) { return rule == this; }
        public boolean isConflicting(ISchedulingRule rule) { return rule == this; }
    };

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

    private final AtomicReference<Job> autoStartJob = new AtomicReference<>();
    private McpHttpServer httpServer;
    private boolean shuttingDown;
    private volatile String status = "Stopped";

    private McpServerManager() {
    }

    public static McpServerManager getInstance() {
        return INSTANCE;
    }

    public synchronized boolean isRunning() {
        return httpServer != null && httpServer.isRunning();
    }

    public String getStatus() {
        return status;
    }

    public synchronized void start() throws Exception {
        if (shuttingDown) {
            throw new IllegalStateException("MCP plugin is shutting down");
        }
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

        try {
            McpPreferences.AccessMode mode = prefs.getAccessMode();
            httpServer = McpHttpServer.start(
                new McpHttpServer.Configuration(bindHost, port, authEnabled, token),
                toolsFor(mode), instructionsFor(mode));
            status = "Running on http://" + bindHost + ":" + port + MCP_PATH
                + (authEnabled ? "" : " (auth disabled)");
            McpPlugin.logInfo("MCP server started: " + status);
        } catch (Exception e) {
            shutdownQuietly();
            status = "Failed: " + e.getMessage();
            throw e;
        }
    }

    public synchronized void scheduleAutoStart() {
        cancelAutoStart();
        McpPreferences preferences = new McpPreferences();
        if (!preferences.isServerEnabled() || !preferences.isAutoStart()) {
            return;
        }
        Job job = Job.create("Start MCP server", monitor -> {
            try {
                startAutomatically(monitor);
                return Status.OK_STATUS;
            } catch (Exception e) {
                McpPlugin.logError("Failed to auto-start MCP server", e);
                return new Status(Status.ERROR, McpPlugin.PLUGIN_ID, "Failed to auto-start MCP server", e);
            }
        });
        job.setRule(LIFECYCLE_RULE);
        job.setSystem(true);
        autoStartJob.set(job);
        job.schedule(3000);
    }

    private synchronized void startAutomatically(IProgressMonitor monitor) throws Exception {
        McpPreferences preferences = new McpPreferences();
        if (!monitor.isCanceled() && preferences.isServerEnabled() && preferences.isAutoStart()) {
            start();
        }
    }

    public void cancelAutoStart() {
        Job pending = autoStartJob.getAndSet(null);
        if (pending != null) {
            pending.cancel();
        }
    }

    /** Explicit Start now applies current settings even when automatic startup is disabled. */
    public synchronized void restartNow() throws Exception {
        stop();
        start();
    }

    public synchronized void stop() {
        cancelAutoStart();
        shutdownQuietly();
        status = "Stopped";
    }

    public synchronized void activate() {
        shuttingDown = false;
    }

    public synchronized void shutdown() {
        shuttingDown = true;
        stop();
    }

    public synchronized void restart() throws Exception {
        stop();
        McpPreferences prefs = new McpPreferences();
        if (prefs.isServerEnabled()) {
            start();
        }
    }

    private void shutdownQuietly() {
        if (httpServer == null) {
            return;
        }
        try {
            httpServer.close();
        } catch (Exception e) {
            McpPlugin.logError("Error closing MCP HTTP server", e);
        } finally {
            httpServer = null;
        }
    }
}
