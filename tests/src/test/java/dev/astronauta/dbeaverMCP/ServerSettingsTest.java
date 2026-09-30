package dev.astronauta.dbeaverMCP;

import static org.junit.Assert.*;

import org.junit.Test;

public class ServerSettingsTest {
    private static ServerSettings settings(String host, String port, String timeout) {
        return ServerSettings.parse(true, true, host, port, true, "token", timeout);
    }

    @Test
    public void rejectsInvalidFieldsBeforePersistence() {
        assertThrows(IllegalArgumentException.class, () -> settings("localhost", "bad", "60"));
        assertThrows(IllegalArgumentException.class, () -> settings("localhost", "65536", "60"));
        assertThrows(IllegalArgumentException.class, () -> settings("localhost", "4319", "-1"));
        assertThrows(IllegalArgumentException.class, () -> settings("http://localhost/path", "4319", "60"));
        assertThrows(IllegalArgumentException.class, () -> settings("bad host", "4319", "60"));
    }

    @Test
    public void acceptsAddressesWithoutDnsAndNormalizesBlankHost() {
        assertEquals("127.0.0.1", settings("", "4319", "0").host());
        assertEquals("::1", settings("::1", "4319", "60").host());
        assertEquals("db.internal.example", settings("db.internal.example", "4319", "60").host());
        ServerSettings generated = ServerSettings.parse(true, true, "localhost", "4319", true, "", "60");
        assertEquals(64, generated.token().length());
    }
}
