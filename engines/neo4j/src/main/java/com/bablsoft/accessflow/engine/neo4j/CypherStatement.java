package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Token;

import java.util.List;
import java.util.Set;

/**
 * The parsed view of a single Cypher statement: its classification, every label / relationship type
 * it references (for the host's allow-list), the clause-level node patterns (for the row-security
 * WHERE-splice), and the token stream (with source offsets into {@link #cypher()}) the splice
 * operates on.
 *
 * @param cypher        the original submission text the token offsets index into
 * @param kind          statement class (SELECT / INSERT / UPDATE / DELETE / DDL)
 * @param references    normalized (lowercased) node labels + relationship types referenced
 * @param nodePatterns  clause-level node patterns with their bound variable, labels, and clause anchor
 * @param tokens        the statement's tokens, offsets relative to {@link #cypher()}
 */
record CypherStatement(
        String cypher,
        CypherStatementKind kind,
        Set<String> references,
        List<CypherNodePattern> nodePatterns,
        List<Token> tokens) {
}
