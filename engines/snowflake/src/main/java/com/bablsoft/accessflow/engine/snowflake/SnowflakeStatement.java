package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single Snowflake SQL statement: its classification, the primary target
 * table (FROM for SELECT/DELETE, INTO for INSERT/MERGE, the head ref for UPDATE, the object name
 * for TRUNCATE/DDL), every table it references (for the host's allow-list — including tables read
 * inside subselects), whether it opens with a CTE, the top-level WHERE/LIMIT flags, and the token
 * stream (with source offsets into {@link #sql()}) the row-security WHERE-splice operates on.
 *
 * @param sql      the original submission text the token offsets index into
 * @param kind     statement class (SELECT / INSERT / UPDATE / DELETE / MERGE / DDL)
 * @param target   the primary target table, or {@code null} for a table-less SELECT
 * @param tables   normalized refs of every referenced table (CTE names excluded)
 * @param hasCte   the statement opens with a {@code WITH} clause
 * @param hasWhere a top-level WHERE clause is present
 * @param hasLimit a top-level LIMIT / FETCH clause is present
 * @param tokens   the statement's tokens, offsets relative to {@link #sql()}
 */
record SnowflakeStatement(
        String sql,
        SnowflakeStatementKind kind,
        SnowflakeTableRef target,
        Set<String> tables,
        boolean hasCte,
        boolean hasWhere,
        boolean hasLimit,
        List<Token> tokens) {
}
