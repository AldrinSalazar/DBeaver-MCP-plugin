package dev.astronauta.dbeaverMCP.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Textual SQL classification: single statement, read vs write.
 * One layer of read-only protection, not the security boundary on its own;
 * database-native enforcement happens in {@code db.readonly} strategies.
 */
public final class SqlGuard {

    private static final Set<String> READ_KEYWORDS = Set.of(
        "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE"
    );

    private static final Set<String> MODIFYING_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "MERGE"
    );

    private SqlGuard() {
    }

    public enum Kind {
        READ, WRITE
    }

    public record CheckResult(Kind kind, List<String> statements) {
    }

    /**
     * Splits the script into statements and classifies it.
     * Only a single statement per call is ever allowed.
     *
     * @throws BridgeException if the script is empty, holds more than one
     *                         statement, or is a write while write access is disabled
     */
    public static CheckResult check(String sql, boolean writeAllowed) throws BridgeException {
        if (sql == null || sql.isBlank()) {
            throw new BridgeException("SQL text is empty");
        }
        List<String> statements = splitStatements(sql);
        if (statements.isEmpty()) {
            throw new BridgeException("SQL text is empty");
        }
        if (statements.size() > 1) {
            throw new BridgeException("Only a single statement per call is allowed");
        }
        Kind kind = classify(statements.get(0));
        if (kind != Kind.READ && !writeAllowed) {
            throw new BridgeException(
                "Only read statements (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN/VALUES) are allowed "
                    + "while write access is disabled (enable it in Window > Preferences > MCP Server)");
        }
        return new CheckResult(kind, statements);
    }

    public static Kind classify(String statement) {
        String first = firstKeyword(statement);
        if (!READ_KEYWORDS.contains(first)) {
            return Kind.WRITE;
        }
        // Reads with hidden writes
        if ("SELECT".equals(first) && containsWord(statement, Set.of("INTO"))) {
            return Kind.WRITE; // SELECT ... INTO creates a table
        }
        if ("WITH".equals(first) && containsWord(statement, MODIFYING_KEYWORDS)) {
            return Kind.WRITE; // data-modifying CTE, e.g. WITH x AS (DELETE ...) SELECT ...
        }
        if ("EXPLAIN".equals(first) && containsWord(statement, MODIFYING_KEYWORDS)) {
            return Kind.WRITE; // EXPLAIN ANALYZE executes the statement
        }
        return Kind.READ;
    }

    /**
     * True if any of the words appears outside string literals and comments.
     * Whole words only: a column named "deleted_flag" does not match DELETE.
     */
    private static boolean containsWord(String sql, Set<String> words) {
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                if (words.contains(sql.substring(start, i).toUpperCase())) {
                    return true;
                }
                continue;
            }
            i++;
        }
        return false;
    }

    private static String firstKeyword(String statement) {
        StringBuilder word = new StringBuilder();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char quote = 0;
        for (int i = 0, n = statement.length(); i < n; i++) {
            char c = statement.charAt(i);
            char next = i + 1 < n ? statement.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    if (next == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!word.isEmpty()) {
                    break;
                }
                continue;
            }
            if (c == '(' || c == ';') {
                break;
            }
            if (!Character.isLetter(c) && word.isEmpty()) {
                continue;
            }
            word.append(Character.toUpperCase(c));
            if (word.length() > 16) {
                break;
            }
        }
        return word.toString();
    }

    /**
     * Splits on semicolons that are outside quotes and comments.
     */
    static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char quote = 0;
        for (int i = 0, n = sql.length(); i < n; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : 0;
            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    if (next == quote) {
                        current.append(next);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '-' && next == '-') {
                current.append(c).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                current.append(c).append(next);
                i++;
                inBlockComment = true;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                current.append(c);
                quote = c;
                continue;
            }
            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    result.add(stmt);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            result.add(tail);
        }
        return result;
    }
}
