package dev.astronauta.dbeaverMCP;

import java.net.URI;
import java.util.UUID;

/** Validated server fields, independent of SWT and persistence. */
public record ServerSettings(boolean enabled, boolean autoStart, String host, int port,
        boolean authEnabled, String token, int timeoutSeconds) {

    public ServerSettings {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be a number between 1 and 65535");
        }
        if (timeoutSeconds < 0 || timeoutSeconds > 86400) {
            throw new IllegalArgumentException("Query timeout must be between 0 and 86400 seconds");
        }
        host = host == null || host.isBlank() ? McpPreferences.DEFAULT_BIND_HOST : host.trim();
        validateHost(host);
        token = token == null || token.isBlank() ? newToken() : token.trim();
    }

    public static String newToken() {
        return UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    }

    public static ServerSettings parse(boolean enabled, boolean autoStart, String host, String port,
            boolean authEnabled, String token, String timeout) {
        return new ServerSettings(enabled, autoStart, host,
            number(port, "Port must be a number between 1 and 65535"), authEnabled, token,
            number(timeout, "Query timeout must be a number of seconds (0 disables it)"));
    }

    private static int number(String text, String error) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
    }

    private static void validateHost(String host) {
        try {
            URI address = new URI("http", null, host, -1, null, null, null);
            if (address.getHost() == null) {
                throw new IllegalArgumentException("Invalid host");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Listen host is not a valid host name or IP address", e);
        }
    }

    public void saveTo(McpPreferences preferences) {
        preferences.setServerEnabled(enabled);
        preferences.setAutoStart(autoStart);
        preferences.setBindHost(host);
        preferences.setPort(port);
        preferences.setAuthEnabled(authEnabled);
        preferences.setToken(token);
        preferences.setQueryTimeoutSec(timeoutSeconds);
    }
}
