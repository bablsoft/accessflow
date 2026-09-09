package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An unsaved masking policy, evaluated by the policy simulator (issue AF-630) as if it were
 * persisted. Same fields as {@link CreateMaskingPolicyCommand} plus {@code replacesPolicyId}:
 * when set, the named policy is replaced by this draft in the simulated set; when null the draft is
 * added.
 *
 * <p>Nothing here ever reaches a repository.
 */
public record MaskingPolicyDraft(
        UUID replacesPolicyId,
        String columnRef,
        MaskingStrategy strategy,
        Map<String, String> strategyParams,
        List<String> revealToRoles,
        List<UUID> revealToGroupIds,
        List<UUID> revealToUserIds,
        boolean enabled) {

    public MaskingPolicyDraft {
        strategyParams = strategyParams == null ? Map.of() : Map.copyOf(strategyParams);
        revealToRoles = revealToRoles == null ? List.of() : List.copyOf(revealToRoles);
        revealToGroupIds = revealToGroupIds == null ? List.of() : List.copyOf(revealToGroupIds);
        revealToUserIds = revealToUserIds == null ? List.of() : List.copyOf(revealToUserIds);
    }
}
