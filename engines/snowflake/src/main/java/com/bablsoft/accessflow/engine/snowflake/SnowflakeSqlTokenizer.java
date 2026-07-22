package com.bablsoft.accessflow.engine.snowflake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for Snowflake SQL. Produces a flat token stream with enough structure for the
 * classifier ({@link SnowflakeQueryParser}) and the WHERE-splice rewriter
 * ({@link SnowflakeRowSecurityApplier}): each token carries its source offsets and the paren
 * nesting depth at which it appears (so clause boundaries are only recognized at the statement's
 * top level), and the {@code ?} placeholder is a single symbol. Comments ({@code --}, {@code //},
 * {@code /* … *}{@code /}) are skipped; single-quoted strings (with {@code ''} doubling <em>and</em>
 * Snowflake's backslash escapes), {@code "quoted identifiers"} (with {@code ""} doubling), and
 * {@code $$dollar-quoted$$} blocks (procedure bodies — treated as opaque strings) are single
 * tokens, so keywords inside them can never be mistaken for clause boundaries. Unterminated
 * literals/comments or unbalanced parens raise {@link SnowflakeParseException}
 * ({@code error.snowflake.unbalanced}).
 */
final class SnowflakeSqlTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (quotes included for QUOTED_IDENT/STRING)
     * @param value normalized value — unquoted identifier text for QUOTED_IDENT, uppercase keyword
     *              for WORD, raw text otherwise
     * @param depth paren nesting depth at the token's start (0 = top level)
     * @param start inclusive start offset in the original statement text
     * @param end   exclusive end offset in the original statement text
     */
    record Token(Kind kind, String text, String value, int depth, int start, int end) {

        boolean isWord(String upper) {
            return kind == Kind.WORD && value.equals(upper);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        /**
         * The identifier text: the case-preserved inner text for a {@code "quoted"} identifier,
         * the raw word otherwise (Snowflake folds unquoted identifiers itself).
         */
        String identifier() {
            return kind == Kind.QUOTED_IDENT ? value : text;
        }
    }

    private SnowflakeSqlTokenizer() {
    }

    /** Tokenize a full submission (possibly several {@code ;}-separated statements). */
    static List<Token> tokenize(String sql) {
        var tokens = new ArrayList<Token>();
        int depth = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if ((c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')
                    || (c == '/' && i + 1 < n && sql.charAt(i + 1) == '/')) {
                i = lineCommentEnd(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = blockCommentEnd(sql, i);
                continue;
            }
            if (c == '$' && i + 1 < n && sql.charAt(i + 1) == '$') {
                int end = dollarQuotedEnd(sql, i);
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '\'') {
                int end = stringEnd(sql, i);
                tokens.add(new Token(Kind.STRING, sql.substring(i, end), sql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '"') {
                int end = quotedIdentEnd(sql, i);
                var inner = sql.substring(i + 1, end - 1).replace("\"\"", "\"");
                tokens.add(new Token(Kind.QUOTED_IDENT, sql.substring(i, end), inner, depth, i, end));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && isWordPart(sql.charAt(end))) {
                    end++;
                }
                var word = sql.substring(i, end);
                tokens.add(new Token(Kind.WORD, word, word.toUpperCase(Locale.ROOT), depth, i, end));
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '.')) {
                    end++;
                }
                var num = sql.substring(i, end);
                tokens.add(new Token(Kind.NUMBER, num, num, depth, i, end));
                i = end;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(Kind.SYMBOL, "(", "(", depth, i, i + 1));
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw new SnowflakeParseException("error.snowflake.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, ")", ")", depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new SnowflakeParseException("error.snowflake.unbalanced");
        }
        return tokens;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int lineCommentEnd(String sql, int from) {
        int end = sql.indexOf('\n', from);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int blockCommentEnd(String sql, int from) {
        int end = sql.indexOf("*/", from + 2);
        if (end < 0) {
            throw new SnowflakeParseException("error.snowflake.unbalanced");
        }
        return end + 2;
    }

    /** End offset (exclusive) of a {@code $$…$$} block starting at {@code from}. */
    private static int dollarQuotedEnd(String sql, int from) {
        int end = sql.indexOf("$$", from + 2);
        if (end < 0) {
            throw new SnowflakeParseException("error.snowflake.unbalanced");
        }
        return end + 2;
    }

    /**
     * End offset (exclusive) of a single-quoted string starting at {@code from}; handles both
     * {@code ''} doubling and Snowflake's backslash escapes ({@code \'}, {@code \\}).
     */
    private static int stringEnd(String sql, int from) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == '\'') {
                if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new SnowflakeParseException("error.snowflake.unbalanced");
    }

    /** End offset (exclusive) of a {@code "quoted identifier"} starting at {@code from}. */
    private static int quotedIdentEnd(String sql, int from) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < n && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new SnowflakeParseException("error.snowflake.unbalanced");
    }
}
