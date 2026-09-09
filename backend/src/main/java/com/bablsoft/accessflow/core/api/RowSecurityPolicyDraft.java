package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * An unsaved row-security policy, evaluated by the policy simulator (issue AF-630) as if it were
 * persisted. Same fields as {@link CreateRowSecurityPolicyCommand} plus {@code replacesPolicyId}:
 * when set, the named policy is replaced by this draft in the simulated set (the "edit an existing
 * policy" case); when null the draft is added (the "new policy" case).
 *
 * <p>Nothing here ever reaches a repository.
 */
public record RowSecurityPolicyDraft(
        UUID replacesPolicyId,
        String tableName,
        String columnName,
        RowSecurityOperator operator,
        RowSecurityValueType valueType,
        String valueExpression,
        List<String> appliesToRoles,
        List<UUID> appliesToGroupIds,
        List<UUID> appliesToUserIds,
        boolean enabled) {

    public RowSecurityPolicyDraft {
        appliesToRoles = appliesToRoles == null ? List.of() : List.copyOf(appliesToRoles);
        appliesToGroupIds = appliesToGroupIds == null ? List.of() : List.copyOf(appliesToGroupIds);
        appliesToUserIds = appliesToUserIds == null ? List.of() : List.copyOf(appliesToUserIds);
    }
}
