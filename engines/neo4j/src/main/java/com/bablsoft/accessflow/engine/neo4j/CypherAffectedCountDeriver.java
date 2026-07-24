package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Kind;

import java.util.Set;

/**
 * Derives the non-mutating affected-row count query for an UPDATE / DELETE Cypher statement
 * (issue AF-624): the statement's read prefix — its {@code MATCH} / {@code OPTIONAL MATCH} /
 * {@code WHERE} clauses before the first write clause — with {@code RETURN count(*)} appended in
 * place of the write clauses (and any trailing {@code RETURN}). Only the provably-simple shape is
 * derived: {@code MATCH} / {@code OPTIONAL MATCH} / {@code WHERE} clauses, then {@code SET} /
 * {@code DELETE} / {@code DETACH DELETE} / {@code REMOVE} segments, then an optional trailing
 * {@code RETURN} (with {@code ORDER BY} / {@code SKIP} / {@code LIMIT}). Anything else — {@code MERGE}, {@code CREATE}-then-{@code SET}, {@code FOREACH},
 * {@code CALL} subqueries, {@code WITH} / {@code UNWIND} pipelines, reads resuming after the write
 * — yields {@code null} so the caller degrades to an unsupported count rather than risk a wrong
 * one. {@code count(*)} of the matched rows approximates the affected entities (the same rows the
 * write clauses operate on), which is the accepted AF-624 semantics.
 */
final class CypherAffectedCountDeriver {

    /** Depth-0 keywords that begin / end a clause (mirrors the parser's clause boundaries). */
    private static final Set<String> CLAUSE_KEYWORDS = Set.of(
            "MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "CREATE", "MERGE", "SET", "DELETE",
            "DETACH", "REMOVE", "UNWIND", "CALL", "FOREACH", "ORDER", "SKIP", "LIMIT", "UNION",
            "USE", "ON", "YIELD");
    /** Clause keywords permitted in the read prefix ahead of the first write clause. */
    private static final Set<String> PREFIX_READ = Set.of("MATCH", "OPTIONAL", "WHERE");
    /** Clause keywords that start the write region of an UPDATE / DELETE statement. */
    private static final Set<String> WRITE_STARTERS = Set.of("SET", "DELETE", "DETACH", "REMOVE");
    /** Clause keywords permitted after the write region (further writes + a trailing RETURN). */
    private static final Set<String> AFTER_WRITE = Set.of(
            "SET", "DELETE", "DETACH", "REMOVE", "RETURN", "ORDER", "SKIP", "LIMIT");

    private CypherAffectedCountDeriver() {
    }

    /**
     * The count query for {@code statement}, or {@code null} when the statement is not an
     * UPDATE / DELETE or its shape makes the read-prefix extraction ambiguous.
     */
    static String derive(CypherStatement statement) {
        if (statement.kind() != CypherStatementKind.UPDATE
                && statement.kind() != CypherStatementKind.DELETE) {
            return null;
        }
        int writeStart = -1;
        boolean sawMatch = false;
        for (var token : statement.tokens()) {
            if (token.depth() != 0 || token.kind() != Kind.WORD
                    || !CLAUSE_KEYWORDS.contains(token.value())) {
                continue;
            }
            var value = token.value();
            if (writeStart < 0) {
                if (WRITE_STARTERS.contains(value)) {
                    writeStart = token.start();
                } else if (value.equals("MATCH")) {
                    sawMatch = true;
                } else if (!PREFIX_READ.contains(value)) {
                    return null; // WITH / UNWIND / CALL / CREATE / MERGE / FOREACH / … before the write
                }
            } else if (!AFTER_WRITE.contains(value)) {
                return null; // a read pipeline resumes after the write — ambiguous
            }
        }
        if (writeStart < 0 || !sawMatch) {
            return null; // nothing to count: no write clause, or no MATCH selecting the rows
        }
        return statement.cypher().substring(0, writeStart).strip() + " RETURN count(*) AS affected";
    }
}
