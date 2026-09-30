package dev.astronauta.dbeaverMCP;

import org.osgi.framework.FrameworkUtil;

/** Runtime identity shared by the preferences page and MCP handshake. */
public final class PluginInfo {
    public static final String REPOSITORY_URL = "https://github.com/AldrinSalazar/DBeaver-MCP-plugin";

    private PluginInfo() {
    }

    public static String version() {
        var bundle = FrameworkUtil.getBundle(PluginInfo.class);
        return bundle == null ? "development" : bundle.getVersion().toString();
    }
}
