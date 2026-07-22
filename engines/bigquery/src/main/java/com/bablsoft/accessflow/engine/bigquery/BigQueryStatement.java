package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single GoogleSQL statement: its classification, the table it targets, every
 * table it references (for the host's allow-list — subquery and {@code MERGE … USING} sources
 * included), the structural facts the row-security rewriter needs ({@code WHERE} presence, CTE
 * presence, comma-joins in {@code FROM}), and the token stream (with source offsets into
 * {@link #sql()}) the WHERE-splice operates on.
 *
 * @param sql          the original submission text the token offsets index into
 * @param kind         statement class (SELECT / INSERT / UPDATE / DELETE / MERGE / DDL)
 * @param target       the target table (FROM for SELECT/DELETE, INTO for INSERT/MERGE, the head
 *                     ref for UPDATE, the object ref for DDL), or {@code null} when absent
 * @param tables       normalized lowercase refs of every referenced table
 * @param hasWhere     a top-level WHERE clause is present
 * @param hasLimit     a top-level LIMIT clause is present
 * @param hasCte       the statement starts with a WITH prologue
 * @param hasCommaJoin a top-level FROM clause lists more than one item (comma-join)
 * @param tokens       the statement's tokens, offsets relative to {@link #sql()}
 */
record BigQueryStatement(
        String sql,
        BigQueryStatementKind kind,
        BigQueryTableRef target,
        Set<String> tables,
        boolean hasWhere,
        boolean hasLimit,
        boolean hasCte,
        boolean hasCommaJoin,
        List<Token> tokens) {
}
