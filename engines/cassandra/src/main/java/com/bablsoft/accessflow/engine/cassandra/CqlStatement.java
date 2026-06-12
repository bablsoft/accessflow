package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single CQL statement: its classification, the table it targets (for DML),
 * every table it references (for the host's allow-list), whether it carries a top-level WHERE
 * clause, and the token stream (with source offsets into {@link #sql()}) the row-security
 * WHERE-splice operates on.
 *
 * @param sql       the original submission text the token offsets index into
 * @param kind      statement class (SELECT / INSERT / UPDATE / DELETE / DDL)
 * @param target    the primary table (FROM for SELECT/DELETE, INTO for INSERT, the head ref for
 *                  UPDATE), or {@code null} when absent (e.g. keyspace DDL)
 * @param tables    normalized dotted refs of every referenced table
 * @param hasWhere  a top-level WHERE clause is present
 * @param tokens    the statement's tokens, offsets relative to {@link #sql()}
 */
record CqlStatement(
        String sql,
        CqlStatementKind kind,
        CqlTableRef target,
        Set<String> tables,
        boolean hasWhere,
        List<Token> tokens) {
}
