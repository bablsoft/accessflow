package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * A resolved row-security predicate plus the policy's configured value source and every reason it
 * targets the user (#946) — the read-only explanation of what {@link ResolvedRowSecurityPredicate}
 * enforcement will apply.
 */
public record ExplainedRowSecurityPredicate(
        ResolvedRowSecurityPredicate predicate,
        RowSecurityValueType valueType,
        String valueExpression,
        List<AccessTargetMatch> matchedBy) {

    public ExplainedRowSecurityPredicate {
        matchedBy = matchedBy == null ? List.of() : List.copyOf(matchedBy);
    }
}
