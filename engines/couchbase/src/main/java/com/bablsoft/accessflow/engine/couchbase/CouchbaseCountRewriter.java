package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Kind;
import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * Rewrites a parsed UPDATE / DELETE into the non-mutating {@code SELECT COUNT(*)} probe backing
 * {@code countAffectedRows} (issue AF-624): the statement's target keyspace (original source text,
 * so case and backticks survive) and its top-level WHERE clause are carried over verbatim —
 * {@code SELECT COUNT(*) AS af_count FROM <keyspace> [AS <alias>] [WHERE <predicate>]} — and the
 * row-security applier then splices the request's directives into the count statement exactly as
 * the execute path would (same alias, same qualifier). Only the shapes the WHERE-splice rewriter
 * can provably reach are countable; anything else (CTE / subquery / JOIN / NEST / UNNEST /
 * USE KEYS / set operation / multi-keyspace / MERGE) returns {@code null} so the caller degrades
 * to {@code unsupported}. A {@code LIMIT} also returns {@code null}: it caps the mutation count,
 * which a plain COUNT(*) would overstate.
 */
final class CouchbaseCountRewriter {

    static final String COUNT_ALIAS = "af_count";

    /** Keywords that can follow the WHERE clause at depth 0 (= the clause boundary). */
    private static final Set<String> WHERE_TAIL = Set.of(
            "GROUP", "LETTING", "HAVING", "WINDOW", "ORDER", "LIMIT", "OFFSET", "RETURNING");

    private CouchbaseCountRewriter() {
    }

    /** The count statement, or {@code null} when the shape cannot be provably counted. */
    static String toCountStatement(CouchbaseStatement statement) {
        if (statement.kind() != CouchbaseStatementKind.UPDATE
                && statement.kind() != CouchbaseStatementKind.DELETE) {
            return null;
        }
        if (statement.hasCte() || statement.hasSubquery() || statement.hasJoinLike()
                || statement.hasUseKeys() || statement.hasSetOperation() || statement.hasLimit()
                || statement.target() == null || statement.keyspaces().size() > 1) {
            return null;
        }
        var target = targetSourceText(statement);
        if (target == null) {
            return null;
        }
        var count = new StringBuilder("SELECT COUNT(*) AS ").append(COUNT_ALIAS)
                .append(" FROM ").append(target);
        if (statement.targetAlias() != null) {
            count.append(" AS `").append(statement.targetAlias().replace("`", "``")).append('`');
        }
        var where = whereClauseText(statement);
        if (where != null) {
            count.append(" WHERE ").append(where);
        }
        return count.toString();
    }

    /**
     * The original source text of the target keyspace ref (after FROM for a DELETE, after the
     * UPDATE verb), including an optional {@code default:} namespace prefix — extracted by token
     * offsets so backticks and case are preserved exactly as submitted.
     */
    private static String targetSourceText(CouchbaseStatement statement) {
        var tokens = statement.tokens();
        int refStart = refStartIndex(statement, tokens);
        if (refStart < 0 || refStart >= tokens.size()) {
            return null;
        }
        int i = refStart;
        // Optional namespace prefix: default:<path>.
        if (tokens.get(i).isWord("DEFAULT") && i + 1 < tokens.size()
                && tokens.get(i + 1).isSymbol(":")) {
            i += 2;
        }
        int end = -1;
        while (i < tokens.size()) {
            var token = tokens.get(i);
            if (token.kind() != Kind.WORD && token.kind() != Kind.QUOTED_IDENT) {
                break;
            }
            end = token.end();
            if (i + 1 < tokens.size() && tokens.get(i + 1).isSymbol(".")) {
                i += 2;
                continue;
            }
            break;
        }
        if (end < 0) {
            return null;
        }
        return statement.sql().substring(tokens.get(refStart).start(), end);
    }

    private static int refStartIndex(CouchbaseStatement statement, List<Token> tokens) {
        if (statement.kind() == CouchbaseStatementKind.UPDATE) {
            // hasCte shapes were rejected above, so the UPDATE verb is the first token.
            return 1;
        }
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.isWord("FROM")) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The top-level WHERE expression text, or {@code null} when the statement has none. */
    private static String whereClauseText(CouchbaseStatement statement) {
        var tokens = statement.tokens();
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.isWord("WHERE")) {
                var clause = statement.sql()
                        .substring(token.end(), clauseEndOffset(tokens, i + 1)).strip();
                return clause.isEmpty() ? null : clause;
            }
        }
        return null;
    }

    /** Offset of the first depth-0 tail keyword at/after {@code from}, else the statement end. */
    private static int clauseEndOffset(List<Token> tokens, int from) {
        for (int i = from; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD
                    && WHERE_TAIL.contains(token.value())) {
                return token.start();
            }
        }
        return tokens.get(tokens.size() - 1).end();
    }
}
