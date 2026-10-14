package dev.astronauta.dbeaverMCP.server.tools;

/**
 * Tool output reported with {@code isError}, for failures that still carry a full
 * structured body (for example the statements a script ran before it failed).
 */
public record FailedResult(Object body) {
}
