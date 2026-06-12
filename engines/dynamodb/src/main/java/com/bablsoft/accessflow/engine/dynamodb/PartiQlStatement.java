package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single PartiQL statement: its classification, the table it targets, every
 * table it references (for the host's allow-list — DynamoDB is single-table, so this is just the
 * target), whether it carries a top-level WHERE clause, and the token stream (with source offsets
 * into {@link #sql()}) the row-security WHERE-splice operates on.
 *
 * @param sql      the original submission text the token offsets index into
 * @param kind     statement class (SELECT / INSERT / UPDATE / DELETE)
 * @param target   the target table (FROM for SELECT/DELETE, INTO for INSERT, the head ref for
 *                 UPDATE), or {@code null} when absent
 * @param tables   normalized refs of every referenced table
 * @param hasWhere a top-level WHERE clause is present
 * @param tokens   the statement's tokens, offsets relative to {@link #sql()}
 */
record PartiQlStatement(
        String sql,
        PartiQlStatementKind kind,
        PartiQlTableRef target,
        Set<String> tables,
        boolean hasWhere,
        List<Token> tokens) {
}
