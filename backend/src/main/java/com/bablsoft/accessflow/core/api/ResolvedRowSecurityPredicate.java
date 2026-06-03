package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * A row-security policy that <em>applies</em> to a given submitter, resolved for one query
 * execution: the policy's value source has been resolved to concrete bound {@code values}. Carries
 * the originating {@code policyId} for audit and the normalized {@code tableRef} so the proxy can
 * match it against parsed table references.
 *
 * <p>An empty {@code values} list is the deliberate fail-closed signal — it means the variable
 * could not be resolved (missing attribute, or a {@code :user.groups} for a user in no groups) and
 * the proxy must emit an always-false predicate so the submitter sees nothing rather than
 * everything.
 */
public record ResolvedRowSecurityPredicate(
        UUID policyId,
        String tableRef,
        String columnName,
        RowSecurityOperator operator,
        List<Object> values) {

    public ResolvedRowSecurityPredicate {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
