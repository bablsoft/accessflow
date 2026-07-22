package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single Databricks SQL statement: its classification, every table it
 * references (normalized, for the host's allow-list), whether it carries a top-level WHERE / LIMIT
 * clause, whether a CTE ({@code WITH}) is present, and the token stream (with source offsets into
 * {@link #sql()}) the row-security WHERE-splice operates on.
 *
 * @param sql      the original submission text the token offsets index into
 * @param kind     statement class (SELECT / INSERT / UPDATE / DELETE / MERGE / DDL)
 * @param tables   normalized ({@code lowercase.dot.joined}) refs of every referenced table
 * @param hasWhere a top-level WHERE clause is present
 * @param hasLimit a top-level LIMIT clause is present
 * @param hasCte   the statement starts with a WITH (common-table-expression) clause
 * @param tokens   the statement's tokens, offsets relative to {@link #sql()}
 */
record DatabricksStatement(
        String sql,
        DatabricksStatementKind kind,
        Set<String> tables,
        boolean hasWhere,
        boolean hasLimit,
        boolean hasCte,
        List<Token> tokens) {
}
