package dev.astronauta.dbeaverMCP.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.astronauta.dbeaverMCP.db.SqlGuard.Dialect;

/**
 * One tokenizer for statement boundaries and unquoted keywords. Script mode adds the client-side
 * rules psql and the mysql client apply to files: PostgreSQL {@code BEGIN ATOMIC} routine bodies
 * and MySQL {@code DELIMITER} commands.
 */
final class SqlLexer {
    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");
    private static final String DELIMITER_COMMAND = "DELIMITER";
    // Matched anywhere in a script statement, so quoted names and set_config(...) count too.
    private static final Pattern QUOTING_SETTINGS =
        Pattern.compile("standard_conforming_strings|sql_mode", Pattern.CASE_INSENSITIVE);

    /** {@code start} is where {@code text} begins in the script; {@code contentStart} is its first token. */
    record Statement(String text, List<String> words, int start, int contentStart) {
    }

    /**
     * Server settings that decide how quotes are read: whether backslashes in plain literals are
     * escapes (PostgreSQL {@code standard_conforming_strings}, MySQL {@code NO_BACKSLASH_ESCAPES})
     * and whether MySQL double quotes delimit identifiers ({@code ANSI_QUOTES}). Null fields are
     * unknown, which rejects backslashes whose meaning depends on them.
     */
    record Quoting(Boolean backslashEscapes, Boolean ansiQuotes) {
        static final Quoting UNKNOWN = new Quoting(null, null);
    }

    private final String sql;
    private final Dialect dialect;
    private Quoting quoting;
    private final boolean script;
    private final List<Statement> statements = new ArrayList<>();
    private final List<String> words = new ArrayList<>();
    private String delimiter = ";";
    private int position;
    private int start;
    private int contentStart;
    private boolean hasContent;
    private int parenDepth;
    private int beginDepth;
    private boolean afterDot;

    private SqlLexer(String sql, Dialect dialect, Quoting quoting, boolean script) {
        this.sql = sql;
        this.dialect = dialect;
        this.quoting = quoting;
        this.script = script;
    }

    static List<Statement> scan(String sql, Dialect dialect) throws BridgeException {
        return new SqlLexer(sql, dialect, Quoting.UNKNOWN, false).scan();
    }

    /**
     * Splits a script file. Only for write-authorized scripts: DELIMITER would otherwise let one
     * guarded "statement" carry several.
     */
    static List<Statement> scanScript(String sql, Dialect dialect, Quoting quoting) throws BridgeException {
        return new SqlLexer(sql, dialect, quoting, true).scan();
    }

    private List<Statement> scan() throws BridgeException {
        while (position < sql.length()) {
            if (skipComment() || readDelimiterCommand()) {
                continue;
            }
            char current = sql.charAt(position);
            if (atDelimiter() && beginDepth == 0) {
                finishStatement();
                position += delimiter.length();
                start = position;
            } else if (Character.isWhitespace(current)) {
                position++;
            } else {
                if (!hasContent) {
                    contentStart = position;
                }
                hasContent = true;
                readToken(current);
            }
        }
        if (beginDepth > 0) {
            throw new BridgeException("Unterminated BEGIN ATOMIC block: a CREATE FUNCTION or CREATE PROCEDURE"
                + " body has no matching END.");
        }
        finishStatement();
        return List.copyOf(statements);
    }

    private boolean atDelimiter() {
        return sql.startsWith(delimiter, position);
    }

    /** The mysql client's DELIMITER line, recognized where a statement would start. */
    private boolean readDelimiterCommand() throws BridgeException {
        int end = position + DELIMITER_COMMAND.length();
        if (!script || dialect != Dialect.MYSQL || hasContent || end >= sql.length()
                || !sql.regionMatches(true, position, DELIMITER_COMMAND, 0, DELIMITER_COMMAND.length())
                || (sql.charAt(end) != ' ' && sql.charAt(end) != '\t')) {
            return false;
        }
        int lineEnd = end;
        while (lineEnd < sql.length() && sql.charAt(lineEnd) != '\n' && sql.charAt(lineEnd) != '\r') {
            lineEnd++;
        }
        String[] arguments = sql.substring(end, lineEnd).trim().split("\\s+");
        if (arguments[0].isEmpty()) {
            throw new BridgeException("DELIMITER needs a value, for example DELIMITER //");
        }
        delimiter = arguments[0];
        position = lineEnd;
        start = lineEnd;
        return true;
    }

    private void finishStatement() {
        if (hasContent) {
            int textStart = start;
            while (sql.charAt(textStart) <= ' ') {
                textStart++;
            }
            String text = sql.substring(textStart, position).trim();
            statements.add(new Statement(text, List.copyOf(words), textStart, contentStart));
            if (script && changesQuotingSetting(text)) {
                // Later statements run under settings this lexer cannot know.
                quoting = Quoting.UNKNOWN;
            }
        }
        words.clear();
        hasContent = false;
        parenDepth = 0;
        afterDot = false;
    }

    private void readToken(char current) throws BridgeException {
        // Words after a dot, even across comments, are name parts such as t.end, never block keywords.
        boolean qualified = afterDot;
        afterDot = false;
        if (current == '\'' || current == '"'
                || (current == '`' && (dialect == Dialect.MYSQL || dialect == Dialect.SQLITE))) {
            readQuoted(current, current, backslashEscapes(current));
        } else if (current == '[' && (dialect == Dialect.SQL_SERVER || dialect == Dialect.SQLITE)) {
            readQuoted('[', ']', false);
        } else if (current == '$' && dialect == Dialect.POSTGRESQL && readDollarString()) {
            // Entire dollar-quoted body was consumed.
        } else if (Character.isLetter(current) || current == '_') {
            int wordStart = position++;
            while (position < sql.length() && isWord(sql.charAt(position)) && !atDelimiter()) {
                position++;
            }
            words.add(sql.substring(wordStart, position).toUpperCase(Locale.ROOT));
            if (!qualified) {
                trackRoutineBody();
            }
        } else {
            if (current == '(') {
                parenDepth++;
            } else if (current == ')' && parenDepth > 0) {
                parenDepth--;
            }
            afterDot = current == '.';
            position++;
        }
    }

    /** Errs towards true: a false match only makes later setting-dependent backslashes fail closed. */
    private boolean changesQuotingSetting(String text) {
        String verb = words.isEmpty() ? "" : words.get(0);
        return QUOTING_SETTINGS.matcher(text).find()
            || ((verb.equals("RESET") || verb.equals("DISCARD")) && words.contains("ALL"));
    }

    /**
     * SQL-standard routine bodies, a stricter form of psql's heuristic: inside CREATE [OR REPLACE]
     * FUNCTION/PROCEDURE, semicolons between BEGIN ATOMIC and its END (counting nested CASE ... END)
     * do not end the statement. END and CASE are reserved words, so outside parentheses they only
     * appear unquoted as keywords, after a dot (handled by the caller), or as AS labels.
     */
    private void trackRoutineBody() {
        if (!script || dialect != Dialect.POSTGRESQL || parenDepth > 0 || !isRoutineDefinition()) {
            return;
        }
        String previous = words.size() > 1 ? words.get(words.size() - 2) : "";
        if (previous.equals("AS")) {
            return;
        }
        switch (words.get(words.size() - 1)) {
            case "ATOMIC" -> beginDepth += previous.equals("BEGIN") ? 1 : 0;
            case "CASE" -> beginDepth += beginDepth > 0 ? 1 : 0;
            case "END" -> beginDepth -= beginDepth > 0 ? 1 : 0;
            default -> {
            }
        }
    }

    private boolean isRoutineDefinition() {
        if (words.size() < 2 || !words.get(0).equals("CREATE")) {
            return false;
        }
        int kind = words.get(1).equals("OR") && words.size() > 3 && words.get(2).equals("REPLACE") ? 3 : 1;
        return words.get(kind).equals("FUNCTION") || words.get(kind).equals("PROCEDURE");
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

    /** @param escapes whether backslashes escape the next character; null when that is unknown. */
    private void readQuoted(char opening, char closing, Boolean escapes) throws BridgeException {
        position++;
        while (position < sql.length()) {
            char current = sql.charAt(position++);
            if (current == '\\' && Boolean.TRUE.equals(escapes)) {
                position++;
            } else if (current == '\\' && escapes == null) {
                // Session SQL modes can change quote boundaries; fail closed without guessing.
                throw new BridgeException(script
                    ? "Backslashes in quoted values are ambiguous here: the server's standard_conforming_strings"
                        + " or sql_mode setting is unknown or was changed earlier in the script. Nothing was"
                        + " executed; use doubled quotes, or E'...' strings on PostgreSQL."
                    : "Ambiguous backslashes in SQL quoted values are not supported;"
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

    private Boolean backslashEscapes(char opening) {
        if (opening == '\'' && isEscapeString()) {
            return true;
        }
        if (opening == '\'' && (dialect == Dialect.POSTGRESQL || dialect == Dialect.MYSQL)) {
            return quoting.backslashEscapes();
        }
        if (opening == '"' && dialect == Dialect.MYSQL) {
            // An ANSI_QUOTES identifier and a string without escapes both keep backslashes literal.
            if (Boolean.TRUE.equals(quoting.ansiQuotes()) || Boolean.FALSE.equals(quoting.backslashEscapes())) {
                return false;
            }
            return quoting.ansiQuotes() == null ? null : quoting.backslashEscapes();
        }
        return false;
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
