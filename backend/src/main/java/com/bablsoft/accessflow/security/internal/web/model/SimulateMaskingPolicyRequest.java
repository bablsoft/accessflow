package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.MaskingPolicyDraft;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dry-run request for a draft masking policy (issue AF-630): the create body, plus the window to
 * replay and an optional {@code replacesPolicyId} for the "edit an existing policy" case.
 */
public record SimulateMaskingPolicyRequest(
        @NotNull(message = "{validation.simulation_period.required}") Instant from,
        @NotNull(message = "{validation.simulation_period.required}") Instant to,
        @NotNull(message = "{validation.simulation_draft.required}") @Valid Draft draft) {

    /** The unsaved policy, carrying every constraint its persisted counterpart carries. */
    public record Draft(
            UUID replacesPolicyId,
            @NotBlank(message = "{validation.masking_column_ref.required}")
            @Size(max = 512, message = "{validation.masking_column_ref.size}")
            String columnRef,
            @NotNull(message = "{validation.masking_strategy.required}")
            MaskingStrategy strategy,
            Map<String, String> strategyParams,
            List<String> revealToRoles,
            List<UUID> revealToGroupIds,
            List<UUID> revealToUserIds,
            Boolean enabled) {

        public MaskingPolicyDraft toCommand() {
            return new MaskingPolicyDraft(replacesPolicyId, columnRef, strategy, strategyParams,
                    revealToRoles, revealToGroupIds, revealToUserIds, enabled == null || enabled);
        }
    }
}
