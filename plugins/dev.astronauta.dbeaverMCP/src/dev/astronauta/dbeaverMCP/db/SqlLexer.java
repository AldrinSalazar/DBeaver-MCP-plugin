package dev.astronauta.dbeaverMCP.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.astronauta.dbeaverMCP.db.SqlGuard.Dialect;

/** One tokenizer for statement boundaries and unquoted keywords. */
final class SqlLexer {
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");

    record Statement(String text, List<String> words) {
    }

    private final String sql;
    private final Dialect dialect;
    private final List<Statement> statements = new ArrayList<>();
    private final List<String> words = new ArrayList<>();
    private int position;
    private int start;
    private boolean hasContent;

    private SqlLexer(String sql, Dialect dialect) {
        this.sql = sql;
        this.dialect = dialect;
    }

    static List<Statement> scan(String sql, Dialect dialect) throws BridgeException {
        return new SqlLexer(sql, dialect).scan();
    }

    private List<Statement> scan() throws BridgeException {
        while (position < sql.length()) {
            if (skipComment()) {
                continue;
            }
            char current = sql.charAt(position);
            if (current == ';') {
                finishStatement();
                start = ++position;
            } else if (Character.isWhitespace(current)) {
                position++;
            } else {
                hasContent = true;
                readToken(current);
            }
        }
        finishStatement();
        return List.copyOf(statements);
    }

    private void finishStatement() {
        if (hasContent) {
            statements.add(new Statement(sql.substring(start, position).trim(), List.copyOf(words)));
        }
        words.clear();
        hasContent = false;
    }

    private void readToken(char current) throws BridgeException {
        if (current == '\'' || current == '"'
                || (current == '`' && (dialect == Dialect.MYSQL || dialect == Dialect.SQLITE))) {
            readQuoted(current, current, current == '\'' && isEscapeString());
        } else if (current == '[' && (dialect == Dialect.SQL_SERVER || dialect == Dialect.SQLITE)) {
            readQuoted('[', ']', false);
        } else if (current == '$' && dialect == Dialect.POSTGRESQL && readDollarString()) {
            // Entire dollar-quoted body was consumed.
        } else if (Character.isLetter(current) || current == '_') {
            int wordStart = position++;
            while (position < sql.length() && isWord(sql.charAt(position))) {
                position++;
            }
            words.add(sql.substring(wordStart, position).toUpperCase(Locale.ROOT));
        } else {
            position++;
        }
    }

    private boolean skipComment() throws BridgeException {
        if (isLineComment() || (dialect == Dialect.MYSQL && sql.charAt(position) == '#')) {
            while (position < sql.length() && sql.charAt(position) != '\n' && sql.charAt(position) != '\r') {
                position++;
            }
            return true;
        }
        if (!sql.startsWith("/*", position)) {
            return false;
        }
        if (sql.startsWith("/*!", position)
                || (dialect == Dialect.MYSQL && (sql.startsWith("/*M!", position) || sql.startsWith("/*m!", position)))) {
            throw new BridgeException("Executable SQL comments are not supported.");
        }
        position += 2;
        int depth = 1;
        while (position < sql.length()) {
            if (sql.startsWith("*/", position)) {
                position += 2;
                if (--depth == 0) {
                    return true;
                }
            } else if (sql.startsWith("/*", position)
                    && (dialect == Dialect.POSTGRESQL || dialect == Dialect.SQL_SERVER)) {
                depth++;
                position += 2;
            } else {
                position++;
            }
        }
        throw new BridgeException("Unterminated SQL comment.");
    }

    private boolean isLineComment() {
        if (!sql.startsWith("--", position)) {
            return false;
        }
        return dialect != Dialect.MYSQL || position + 2 == sql.length()
            || sql.charAt(position + 2) <= ' ' || sql.charAt(position + 2) == '\u007f';
    }

    private void readQuoted(char opening, char closing, boolean escapes) throws BridgeException {
        position++;
        while (position < sql.length()) {
            char current = sql.charAt(position++);
            if (current == '\\' && escapes) {
                position++;
            } else if (current == '\\' && ambiguousBackslash(opening)) {
                // Session SQL modes can change quote boundaries; fail closed without guessing.
                throw new BridgeException("Ambiguous backslashes in SQL quoted values are not supported;"
                    + " use doubled quotes or PostgreSQL E-string syntax.");
            } else if (current == closing) {
                if (position < sql.length() && sql.charAt(position) == closing) {
                    position++;
                } else {
                    return;
                }
            }
        }
        throw new BridgeException("Unterminated SQL quoted value.");
    }

    private boolean ambiguousBackslash(char opening) {
        return (dialect == Dialect.MYSQL && (opening == '\'' || opening == '"'))
            || (dialect == Dialect.POSTGRESQL && opening == '\'');
    }

    private boolean isEscapeString() {
        return dialect == Dialect.POSTGRESQL && position > 0
            && (sql.charAt(position - 1) == 'E' || sql.charAt(position - 1) == 'e')
            && (position == 1 || !isWord(sql.charAt(position - 2)));
    }

    private boolean readDollarString() throws BridgeException {
        Matcher matcher = DOLLAR_QUOTE.matcher(sql).region(position, sql.length());
        if (!matcher.lookingAt()) {
            return false;
        }
        String delimiter = matcher.group();
        int end = sql.indexOf(delimiter, matcher.end());
        if (end < 0) {
            throw new BridgeException("Unterminated SQL dollar-quoted value.");
        }
        position = end + delimiter.length();
        return true;
    }

    private static boolean isWord(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }
}
