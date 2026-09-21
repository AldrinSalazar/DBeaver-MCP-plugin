package dev.astronauta.dbeaverMCP;

import dev.astronauta.dbeaverMCP.server.McpServerManager;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jkiss.dbeaver.Log;
import org.osgi.framework.BundleContext;

/**
 * Bundle activator. Owns the MCP server lifecycle.
 */
public class McpPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "dev.astronauta.dbeaverMCP";

    private static final Log LOG = Log.getLog(McpPlugin.class);

    private static McpPlugin instance;

    public McpPlugin() {
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        LOG.info("MCP Server plugin started");
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            McpServerManager.getInstance().stop();
        } catch (Exception e) {
            LOG.warn("Error stopping MCP server", e);
        }
        instance = null;
        super.stop(context);
    }

    public static McpPlugin getDefault() {
        return instance;
    }

    public static void logError(String message, Throwable e) {
        LOG.error(message, e);
        McpPlugin plugin = instance;
        if (plugin != null) {
            ILog log = plugin.getLog();
            if (log != null) {
                log.log(new Status(Status.ERROR, PLUGIN_ID, message, e));
            }
        }
    }

    public static void logInfo(String message) {
        LOG.info(message);
    }
}
