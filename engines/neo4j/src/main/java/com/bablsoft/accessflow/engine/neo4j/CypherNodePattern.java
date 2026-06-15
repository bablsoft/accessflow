package com.bablsoft.accessflow.engine.neo4j;

import java.util.List;

/**
 * A clause-level node pattern {@code (variable:Label …)} the parser extracted from the pattern
 * region of a {@code MATCH} / {@code OPTIONAL MATCH} / {@code CREATE} / {@code MERGE} clause. It
 * carries the bound variable (or {@code null} for an anonymous {@code (:Label)}), the labels on the
 * node, the owning clause's kind, and the token index of that clause's leading keyword — the anchor
 * {@link Neo4jRowSecurityApplier} uses to locate the clause's {@code WHERE} for the row-security
 * splice.
 *
 * @param variable       the bound variable, or {@code null} when the node is anonymous
 * @param labels         the labels declared on the node (case-preserved)
 * @param clauseKind     whether the owning clause reads ({@code MATCH}) or writes ({@code CREATE} /
 *                       {@code MERGE}) the node
 * @param clauseKeyword  the owning clause's leading keyword token index in the statement's token list
 */
record CypherNodePattern(String variable, List<String> labels, ClauseKind clauseKind,
                         int clauseKeyword) {

    /** Whether the owning clause reads (filterable) or writes (creates) the node. */
    enum ClauseKind { MATCH, WRITE }

    boolean hasVariable() {
        return variable != null && !variable.isBlank();
    }
}
