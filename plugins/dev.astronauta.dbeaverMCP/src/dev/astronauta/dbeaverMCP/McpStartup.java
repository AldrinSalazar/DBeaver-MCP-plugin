package dev.astronauta.dbeaverMCP;

import dev.astronauta.dbeaverMCP.server.McpServerManager;
import org.eclipse.ui.IStartup;

/**
 * Starts the MCP server shortly after workbench startup when enabled.
 */
public class McpStartup implements IStartup {

    public McpStartup() {
    }

    @Override
    public void earlyStartup() {
        McpPreferences prefs = new McpPreferences();
        if (!prefs.isServerEnabled() || !prefs.isAutoStart()) {
            return;
        }
        Thread starter = new Thread(() -> {
            try {
                // Give the platform a moment to finish initialization
                Thread.sleep(3000);
                McpServerManager.getInstance().start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                McpPlugin.logError("Failed to auto-start MCP server", e);
            }
        }, "MCP-Server-Starter");
        starter.setDaemon(true);
        starter.start();
    }
}
