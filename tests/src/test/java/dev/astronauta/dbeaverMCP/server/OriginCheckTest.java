package dev.astronauta.dbeaverMCP.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * DNS-rebinding protection: only exact loopback origins pass. Prefix
 * lookalikes ({@code localhost.evil.com}) must be rejected.
 */
public class OriginCheckTest {

    @Test
    public void allowsExactLoopbackOrigins() {
        assertTrue(McpServerManager.isLocalOrigin("http://localhost"));
        assertTrue(McpServerManager.isLocalOrigin("http://localhost:3000"));
        assertTrue(McpServerManager.isLocalOrigin("https://localhost:5173"));
        assertTrue(McpServerManager.isLocalOrigin("http://LOCALHOST:8080"));
        assertTrue(McpServerManager.isLocalOrigin("http://localhost.:4200"));
        assertTrue(McpServerManager.isLocalOrigin("http://127.0.0.1"));
        assertTrue(McpServerManager.isLocalOrigin("https://127.0.0.1:8443"));
        assertTrue(McpServerManager.isLocalOrigin("http://[::1]:4200"));
    }

    @Test
    public void rejectsPrefixLookalikeHosts() {
        assertFalse(McpServerManager.isLocalOrigin("http://localhost.evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("http://127.0.0.1.evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("http://localhost-evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("https://localhost.evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("http://sub.localhost.evil.com"));
    }

    @Test
    public void rejectsRemoteAndNonHttpOrigins() {
        assertFalse(McpServerManager.isLocalOrigin("http://evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("http://evil.com:4319"));
        assertFalse(McpServerManager.isLocalOrigin("https://127.0.0.1.evil.com"));
        assertFalse(McpServerManager.isLocalOrigin("ftp://localhost"));
        assertFalse(McpServerManager.isLocalOrigin("chrome-extension://abcdef"));
    }

    @Test
    public void rejectsAbsentUnparseableOrNullStringOrigins() {
        assertFalse(McpServerManager.isLocalOrigin(null));
        assertFalse(McpServerManager.isLocalOrigin("  "));
        assertFalse(McpServerManager.isLocalOrigin("null"));
        assertFalse(McpServerManager.isLocalOrigin("http://local host"));
        assertFalse(McpServerManager.isLocalOrigin("not a url"));
    }
}
