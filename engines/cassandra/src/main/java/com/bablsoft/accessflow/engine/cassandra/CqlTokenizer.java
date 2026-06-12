package com.bablsoft.accessflow.engine.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer for CQL. Produces a flat token stream with enough structure for the classifier
 * ({@link CqlQueryParser}) and the WHERE-splice rewriter ({@link CassandraRowSecurityApplier}):
 * each token carries its source offsets and the paren/bracket/brace nesting depth at which it
 * appears. Comments ({@code --}, {@code //} and {@code /* … *}{@code /}) are skipped; string
 * literals ({@code '…'}, with {@code ''} doubling) and double-quoted identifiers ({@code "…"}, with
 * {@code ""} doubling) are single tokens, so keywords inside them can never be mistaken for clause
 * boundaries. Unterminated literals/comments or unbalanced brackets raise {@link CqlParseException}
 * ({@code error.cassandra.unbalanced}).
 */
final class CqlTokenizer {

    enum Kind { WORD, QUOTED_IDENT, STRING, NUMBER, SYMBOL }

    /**
     * @param kind  lexical class of the token
     * @param text  raw source slice (quotes included for QUOTED_IDENT/STRING)
     * @param value normalized value — unquoted identifier text for QUOTED_IDENT, uppercase keyword
     *              for WORD, raw text otherwise
     * @param depth paren/bracket/brace nesting depth at the token's start (0 = top level)
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

        /** The CQL internal-form identifier: lowercased for WORD, case-preserved for QUOTED_IDENT. */
        String identifier() {
            return kind == Kind.QUOTED_IDENT ? value : value.toLowerCase(Locale.ROOT);
        }
    }

    private CqlTokenizer() {
    }

    /** Tokenize a full submission (possibly several {@code ;}-separated statements). */
    static List<Token> tokenize(String cql) {
        var tokens = new ArrayList<Token>();
        int depth = 0;
        int i = 0;
        int n = cql.length();
        while (i < n) {
            char c = cql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if ((c == '-' && i + 1 < n && cql.charAt(i + 1) == '-')
                    || (c == '/' && i + 1 < n && cql.charAt(i + 1) == '/')) {
                i = lineCommentEnd(cql, i);
                continue;
            }
            if (c == '/' && i + 1 < n && cql.charAt(i + 1) == '*') {
                i = blockCommentEnd(cql, i);
                continue;
            }
            if (c == '\'') {
                int end = quotedEnd(cql, i, '\'');
                tokens.add(new Token(Kind.STRING, cql.substring(i, end), cql.substring(i, end),
                        depth, i, end));
                i = end;
                continue;
            }
            if (c == '"') {
                int end = quotedEnd(cql, i, '"');
                var inner = cql.substring(i + 1, end - 1).replace("\"\"", "\"");
                tokens.add(new Token(Kind.QUOTED_IDENT, cql.substring(i, end), inner, depth, i, end));
                i = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(cql.charAt(end)) || cql.charAt(end) == '_')) {
                    end++;
                }
                var word = cql.substring(i, end);
                tokens.add(new Token(Kind.WORD, word, word.toUpperCase(Locale.ROOT), depth, i, end));
                i = end;
                continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(cql.charAt(end)) || cql.charAt(end) == '.')) {
                    end++;
                }
                var num = cql.substring(i, end);
                tokens.add(new Token(Kind.NUMBER, num, num, depth, i, end));
                i = end;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                depth++;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth < 0) {
                    throw new CqlParseException("error.cassandra.unbalanced");
                }
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
                i++;
                continue;
            }
            tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), String.valueOf(c), depth, i, i + 1));
            i++;
        }
        if (depth != 0) {
            throw new CqlParseException("error.cassandra.unbalanced");
        }
        return tokens;
    }

    private static int lineCommentEnd(String cql, int from) {
        int end = cql.indexOf('\n', from);
        return end < 0 ? cql.length() : end + 1;
    }

    private static int blockCommentEnd(String cql, int from) {
        int end = cql.indexOf("*/", from + 2);
        if (end < 0) {
            throw new CqlParseException("error.cassandra.unbalanced");
        }
        return end + 2;
    }

    /** End offset (exclusive) of a quoted run starting at {@code from}; handles {@code ''}/{@code ""} doubling. */
    private static int quotedEnd(String cql, int from, char quote) {
        int i = from + 1;
        int n = cql.length();
        while (i < n) {
            char c = cql.charAt(i);
            if (c == quote) {
                if (i + 1 < n && cql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new CqlParseException("error.cassandra.unbalanced");
    }
}
