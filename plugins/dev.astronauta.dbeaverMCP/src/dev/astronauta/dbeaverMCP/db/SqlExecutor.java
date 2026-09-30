package dev.astronauta.dbeaverMCP.db;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.astronauta.dbeaverMCP.db.readonly.ProtectionLevel;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategies;
import dev.astronauta.dbeaverMCP.db.readonly.ReadOnlyStrategy;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;

/** Explicit read-only and read/write lifecycles on isolated sessions. */
public final class SqlExecutor {
    public static final int MAX_ROWS = 5000;

    @FunctionalInterface
    interface SessionFactory {
        IsolatedSession open(String purpose) throws Exception;
    }

    private final SessionFactory sessions;
    private final int timeoutSeconds;

    public SqlExecutor(DBPDataSource dataSource, int timeoutSeconds) {
        this(purpose -> IsolatedSession.open(dataSource, purpose), timeoutSeconds);
    }

    SqlExecutor(SessionFactory sessions, int timeoutSeconds) {
        this.sessions = sessions;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, Object> queryReadOnly(String sql, int maxRows) throws BridgeException {
        int limit = Math.min(Math.max(maxRows, 1), MAX_ROWS);
        try (IsolatedSession isolated = sessions.open("MCP query_sql")) {
            Connection jdbc = isolated.requireJdbc();
            ReadOnlyStrategy protection = ReadOnlyStrategies.forProduct(jdbc.getMetaData().getDatabaseProductName());
            String statement = SqlGuard.check(sql, false, isolated.dialect()).statements().get(0);
            try (var transaction = protection.protect(jdbc)) {
                Map<String, Object> result = execute(isolated, statement, DBCStatementType.QUERY, limit);
                result.put("mode", "query");
                result.put("protection", protectionInfo(protection));
                return result;
            }
        } catch (Exception e) {
            throw executionError("Query failed", e);
        }
    }

    public Map<String, Object> executeReadWrite(String sql) throws BridgeException {
        try (IsolatedSession isolated = sessions.open("MCP execute_sql")) {
            isolated.requireTransactionHandling();
            String statement = SqlGuard.check(sql, true, isolated.dialect()).statements().get(0);
            try {
                Map<String, Object> result = execute(isolated, statement, DBCStatementType.SCRIPT, MAX_ROWS);
                // Rows and update counts have the same transaction finalization.
                result.put("committed", isolated.commit().id());
                return result;
            } catch (Exception e) {
                isolated.rollbackAfterFailure(e);
                throw e;
            }
        } catch (Exception e) {
            throw executionError("SQL execution failed", e);
        }
    }

    private Map<String, Object> execute(IsolatedSession isolated, String sql, DBCStatementType type, int limit)
        throws Exception {
        try (DBCStatement statement = isolated.session().prepareStatement(type, sql, false, false, false)) {
            if (timeoutSeconds > 0) {
                statement.setStatementTimeout(timeoutSeconds);
            }
            statement.setLimit(0, limit + 1L);
            Map<String, Object> result = new LinkedHashMap<>();
            boolean hasRows = statement.executeStatement();
            result.put("mode", hasRows ? "query" : "update");
            result.put("limit", limit);
            if (hasRows) {
                try (var rows = statement.openResultSet()) {
                    result.putAll(ResultReader.read(rows, limit));
                }
            } else {
                result.putAll(ResultReader.empty());
                result.put("updateCount", statement.getUpdateRowCount());
            }
            if (type == DBCStatementType.SCRIPT) {
                result.put("hasMoreResults", statement.nextResults());
            }
            return result;
        }
    }

    private static Map<String, Object> protectionInfo(ReadOnlyStrategy strategy) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("level", strategy.protection().id());
        info.put("strategy", strategy.id());
        info.put("enforced", true);
        if (strategy.protection() == ProtectionLevel.BEST_EFFORT) {
            info.put("detail", "Driver hint plus validation and rollback; not database-enforced.");
        }
        return info;
    }

    private static BridgeException executionError(String prefix, Exception error) {
        if (error instanceof BridgeException bridge) {
            return bridge;
        }
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new BridgeException(prefix + ": " + detail, error);
    }
}
