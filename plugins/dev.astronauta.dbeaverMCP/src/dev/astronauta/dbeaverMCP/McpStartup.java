package dev.astronauta.dbeaverMCP;

import dev.astronauta.dbeaverMCP.server.McpServerManager;
import org.eclipse.ui.IStartup;

/** Schedules cancellable startup after the workbench initializes. */
public class McpStartup implements IStartup {
    @Override
    public void earlyStartup() {
        McpServerManager.getInstance().scheduleAutoStart();
    }
}
