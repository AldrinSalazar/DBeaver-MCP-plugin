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
        assertTrue(TransportSecurity.isLocalOrigin("http://localhost"));
        assertTrue(TransportSecurity.isLocalOrigin("http://localhost:3000"));
        assertTrue(TransportSecurity.isLocalOrigin("https://localhost:5173"));
        assertTrue(TransportSecurity.isLocalOrigin("http://LOCALHOST:8080"));
        assertTrue(TransportSecurity.isLocalOrigin("http://localhost.:4200"));
        assertTrue(TransportSecurity.isLocalOrigin("http://127.0.0.1"));
        assertTrue(TransportSecurity.isLocalOrigin("https://127.0.0.1:8443"));
        assertTrue(TransportSecurity.isLocalOrigin("http://[::1]:4200"));
    }

    @Test
    public void rejectsPrefixLookalikeHosts() {
        assertFalse(TransportSecurity.isLocalOrigin("http://localhost.evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("http://127.0.0.1.evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("http://localhost-evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("https://localhost.evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("http://sub.localhost.evil.com"));
    }

    @Test
    public void rejectsRemoteAndNonHttpOrigins() {
        assertFalse(TransportSecurity.isLocalOrigin("http://evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("http://evil.com:4319"));
        assertFalse(TransportSecurity.isLocalOrigin("https://127.0.0.1.evil.com"));
        assertFalse(TransportSecurity.isLocalOrigin("ftp://localhost"));
        assertFalse(TransportSecurity.isLocalOrigin("chrome-extension://abcdef"));
    }

    @Test
    public void rejectsAbsentUnparseableOrNullStringOrigins() {
        assertFalse(TransportSecurity.isLocalOrigin(null));
        assertFalse(TransportSecurity.isLocalOrigin("  "));
        assertFalse(TransportSecurity.isLocalOrigin("null"));
        assertFalse(TransportSecurity.isLocalOrigin("http://local host"));
        assertFalse(TransportSecurity.isLocalOrigin("not a url"));
    }
}
