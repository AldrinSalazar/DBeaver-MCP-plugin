package dev.astronauta.dbeaverMCP.server;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;

/** Shared production HTTP authentication and origin validation. */
final class TransportSecurity {
    private final boolean authEnabled;
    private final String expectedToken;

    TransportSecurity(boolean authEnabled, String expectedToken) {
        this.authEnabled = authEnabled;
        this.expectedToken = expectedToken;
    }

    void validate(Map<String, List<String>> headers)
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
}
