package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.BulkItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.RowStatus;

import java.util.List;
import java.util.UUID;

public record BulkAttestationDecisionResponse(List<Row> results) {

    public record Row(
            UUID itemId,
            RowStatus status,
            AttestationItemDecision decision,
            boolean wasIdempotentReplay,
            String errorCode,
            String errorMessage) {
    }

    public static BulkAttestationDecisionResponse from(BulkItemDecisionOutcome outcome) {
        var rows = outcome.rows().stream()
                .map(r -> new Row(
                        r.itemId(),
                        r.status(),
                        r.outcome() != null ? r.outcome().decision() : null,
                        r.outcome() != null && r.outcome().wasIdempotentReplay(),
                        r.errorCode(),
                        r.errorMessage()))
                .toList();
        return new BulkAttestationDecisionResponse(rows);
    }
}
