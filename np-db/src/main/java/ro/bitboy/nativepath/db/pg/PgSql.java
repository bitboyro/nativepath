package ro.bitboy.nativepath.db.pg;

import java.sql.SQLException;
import java.util.Locale;

/** SQL text handling: JDBC {@code ?} placeholders to PostgreSQL {@code $n}, and generated-key rewrites. */
final class PgSql {

    /** SQL with {@code $n} placeholders and the number of parameters. */
    record Rewritten(String sql, int parameterCount) {}

    /**
     * Replaces each {@code ?} outside string literals, quoted identifiers, comments and dollar-quoted
     * strings with {@code $1, $2, ...}. {@code ??} stands for a literal {@code ?} (the JSONB operator),
     * as in pgjdbc.
     */
    static Rewritten rewritePlaceholders(String sql) throws SQLException {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int n = 0;
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            switch (c) {
                case '\'' -> {
                    boolean escapes = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                            && (i < 2 || !Character.isLetterOrDigit(sql.charAt(i - 2)));
                    i = copyQuoted(sql, i, '\'', escapes, out);
                }
                case '"' -> i = copyQuoted(sql, i, '"', false, out);
                case '-' -> {
                    if (i + 1 < len && sql.charAt(i + 1) == '-') {
                        int end = sql.indexOf('\n', i);
                        end = end < 0 ? len : end;
                        out.append(sql, i, end);
                        i = end;
                    } else {
                        out.append(c);
                        i++;
                    }
                }
                case '/' -> {
                    if (i + 1 < len && sql.charAt(i + 1) == '*') {
                        i = copyBlockComment(sql, i, out);
                    } else {
                        out.append(c);
                        i++;
                    }
                }
                case '$' -> {
                    int end = dollarQuoteEnd(sql, i);
                    if (end > i) {
                        out.append(sql, i, end);
                        i = end;
                    } else {
                        out.append(c);
                        i++;
                    }
                }
                case '?' -> {
                    if (i + 1 < len && sql.charAt(i + 1) == '?') {
                        out.append('?');
                        i += 2;
                    } else {
                        out.append('$').append(++n);
                        i++;
                    }
                }
                default -> {
                    out.append(c);
                    i++;
                }
            }
        }
        return new Rewritten(out.toString(), n);
    }

    private static int copyQuoted(String sql, int start, char quote, boolean backslashEscapes, StringBuilder out) throws SQLException {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                out.append(sql, start, i + 1);
                return i + 1;
            }
            i++;
        }
        throw new SQLException("unterminated " + (quote == '\'' ? "string literal" : "quoted identifier"), "42601");
    }

    private static int copyBlockComment(String sql, int start, StringBuilder out) throws SQLException {
        int depth = 0;
        int i = start;
        while (i < sql.length() - 1) {
            if (sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                depth++;
                i += 2;
            } else if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) {
                    out.append(sql, start, i);
                    return i;
                }
            } else {
                i++;
            }
        }
        throw new SQLException("unterminated block comment", "42601");
    }

    /** End index (exclusive) of a dollar-quoted string starting at {@code start}, or {@code start} if there is none. */
    private static int dollarQuoteEnd(String sql, int start) throws SQLException {
        if (start > 0) {
            char prev = sql.charAt(start - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_') return start; // part of an identifier
        }
        int i = start + 1;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
            if (i == start + 1 && Character.isDigit(sql.charAt(i))) return start; // $1 positional parameter
            i++;
        }
        if (i >= sql.length() || sql.charAt(i) != '$') return start;
        String tag = sql.substring(start, i + 1);
        int close = sql.indexOf(tag, i + 1);
        if (close < 0) throw new SQLException("unterminated dollar-quoted string", "42601");
        return close + tag.length();
    }

    /**
     * Appends {@code RETURNING} for generated keys to INSERT, UPDATE, DELETE and MERGE statements
     * that do not already have one, as pgjdbc does. {@code columns == null} returns every column.
     */
    static String withReturning(String sql, String[] columns) {
        String trimmed = stripTrailingSemicolon(sql.strip());
        String lower = trimmed.toLowerCase(Locale.ROOT);
        boolean dml = lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete") || lower.startsWith("merge");
        if (!dml || lower.matches("(?s).*\\breturning\\b.*")) return sql;
        if (columns == null || columns.length == 0) return trimmed + " RETURNING *";
        StringBuilder sb = new StringBuilder(trimmed).append(" RETURNING ");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) sb.append(", ");
            // Plain names stay unquoted so they fold to lower case like the rest of the SQL.
            sb.append(columns[i].matches("[A-Za-z_][A-Za-z0-9_]*") ? columns[i] : quoteIdentifier(columns[i]));
        }
        return sb.toString();
    }

    static String quoteIdentifier(String id) {
        return '"' + id.replace("\"", "\"\"") + '"';
    }

    private static String stripTrailingSemicolon(String s) {
        return s.endsWith(";") ? s.substring(0, s.length() - 1).stripTrailing() : s;
    }

    private PgSql() {}
}
