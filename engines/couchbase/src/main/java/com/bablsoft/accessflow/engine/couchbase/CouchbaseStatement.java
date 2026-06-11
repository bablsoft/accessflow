package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.engine.couchbase.SqlPlusPlusTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single SQL++ statement: its classification, the keyspaces it touches, the
 * structural flags the row-security applier needs to decide rewritable vs fail-closed, and the
 * token stream (with source offsets into {@link #sql()}) the WHERE-splice rewriter operates on.
 *
 * @param sql            the original submission text the token offsets index into
 * @param kind           statement class (SELECT / INSERT / UPSERT / UPDATE / MERGE / DELETE / DDL)
 * @param target         the primary keyspace (FROM for SELECT/DELETE, INTO for INSERT/UPSERT/MERGE,
 *                       the UPDATE head ref), or {@code null} when absent (e.g. {@code SELECT 1},
 *                       DDL)
 * @param targetAlias    the explicit alias of the target keyspace, or {@code null}
 * @param keyspaces      normalized dotted paths of every referenced keyspace (any depth)
 * @param hasWhere       a top-level WHERE clause is present
 * @param hasLimit       a top-level LIMIT clause is present
 * @param hasCte         the statement opens with a WITH (CTE) clause
 * @param hasSubquery    a nested SELECT appears anywhere (sub-select, INSERT … SELECT source)
 * @param hasJoinLike    a JOIN / NEST / UNNEST appears anywhere
 * @param hasUseKeys     a USE KEYS hint appears
 * @param hasSetOperation a top-level UNION / INTERSECT / EXCEPT appears
 * @param tokens         the statement's tokens, offsets relative to {@link #sql()}
 */
record CouchbaseStatement(
        String sql,
        CouchbaseStatementKind kind,
        KeyspaceRef target,
        String targetAlias,
        Set<String> keyspaces,
        boolean hasWhere,
        boolean hasLimit,
        boolean hasCte,
        boolean hasSubquery,
        boolean hasJoinLike,
        boolean hasUseKeys,
        boolean hasSetOperation,
        List<Token> tokens) {
}
