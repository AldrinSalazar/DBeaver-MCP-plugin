package dev.astronauta.dbeaverMCP.db;

/**
 * User-facing failure (unknown connection, missing object, denied access...).
 * The message is returned to the MCP client; no stack traces leak.
 */
public class BridgeException extends Exception {

    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
