package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/reviews/attestations/items/bulk}. {@code decision} must be
 * CERTIFIED or REVOKED (PENDING is rejected by the controller).
 */
public record BulkAttestationDecisionRequest(
        @NotEmpty(message = "{validation.attestation.item_ids.required}")
        @Size(max = 100, message = "{validation.attestation.item_ids.max}")
        List<UUID> itemIds,

        @NotNull(message = "{validation.attestation.decision.required}")
        AttestationItemDecision decision,

        @Size(max = 4000, message = "{validation.review_comment.max}")
        String comment) {

    @AssertTrue(message = "{validation.attestation.decision.terminal}")
    public boolean isDecisionTerminal() {
        return decision == null || decision != AttestationItemDecision.PENDING;
    }
}
