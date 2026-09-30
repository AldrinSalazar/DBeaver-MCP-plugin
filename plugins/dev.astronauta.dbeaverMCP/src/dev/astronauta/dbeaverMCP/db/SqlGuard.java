package dev.astronauta.dbeaverMCP.db;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Supplemental SQL validation. Session/database protection remains authoritative. */
public final class SqlGuard {
    private static final Set<String> READ_KEYWORDS = Set.of(
        "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");
    private static final Set<String> WRITE_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "CREATE", "ALTER", "DROP", "TRUNCATE");

    public enum Kind { READ, WRITE }

    public enum Dialect {
        STANDARD, POSTGRESQL, MYSQL, SQL_SERVER, SQLITE;

        public static Dialect forProduct(String product) {
            String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
            if (name.contains("postgresql")) {
                return POSTGRESQL;
            }
            if (name.contains("mysql") || name.contains("mariadb")) {
                return MYSQL;
            }
            if (name.contains("sql server")) {
                return SQL_SERVER;
            }
            if (name.contains("sqlite")) {
                return SQLITE;
            }
            return STANDARD;
        }
    }

    public record CheckResult(Kind kind, List<String> statements) {
    }

    private SqlGuard() {
    }

    public static CheckResult check(String sql, boolean writeAllowed) throws BridgeException {
        return check(sql, writeAllowed, Dialect.STANDARD);
    }

    public static CheckResult check(String sql, boolean writeAllowed, Dialect dialect) throws BridgeException {
        if (sql == null || sql.isBlank()) {
            throw new BridgeException("SQL text is empty");
        }
        List<SqlLexer.Statement> statements = SqlLexer.scan(sql, dialect);
        if (statements.isEmpty()) {
            throw new BridgeException("SQL text is empty");
        }
        if (statements.size() != 1) {
            throw new BridgeException("Only a single statement per call is allowed");
        }
        SqlLexer.Statement statement = statements.get(0);
        Kind kind = classifyWords(statement.words());
        if (kind == Kind.WRITE && !writeAllowed) {
            throw new BridgeException("Only read statements are allowed by query_sql.");
        }
        return new CheckResult(kind, List.of(statement.text()));
    }

    public static Kind classify(String statement) {
        try {
            return check(statement, true).kind();
        } catch (BridgeException e) {
            return Kind.WRITE;
        }
    }

    private static Kind classifyWords(List<String> words) {
        if (words.isEmpty() || !READ_KEYWORDS.contains(words.get(0))) {
            return Kind.WRITE;
        }
        return words.stream().anyMatch(WRITE_KEYWORDS::contains) ? Kind.WRITE : Kind.READ;
    }

    static List<String> splitStatements(String sql) throws BridgeException {
        return SqlLexer.scan(sql, Dialect.STANDARD).stream().map(SqlLexer.Statement::text).toList();
    }
}
