package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ReviewerContext;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.RowStatus;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews/attestations")
@Tag(name = "Attestation Review",
        description = "Reviewer worklist for access-recertification items")
@RequiredArgsConstructor
class AttestationReviewController {

    private final AttestationReviewService reviewService;
    private final AttestationAuditWriter auditWriter;

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_REVIEW')")
    @Operation(summary = "List attestation items the caller can act on across open campaigns")
    @ApiResponse(responseCode = "200", description = "Page of actionable items")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer")
    AttestationItemPageResponse listItems(Authentication authentication, Pageable pageable) {
        var page = reviewService.listPendingForReviewer(toContext(currentClaims(authentication)),
                SpringPageableAdapter.toPageRequest(pageable));
        return AttestationItemPageResponse.from(page);
    }

    @PostMapping("/items/{itemId}/certify")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_REVIEW')")
    @Operation(summary = "Certify an item — the subject still needs this access")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Caller is the subject or not an eligible reviewer")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @ApiResponse(responseCode = "409", description = "Campaign is not OPEN")
    AttestationDecisionResponse certify(@PathVariable UUID itemId,
                                        @Valid @RequestBody AttestationCertifyRequest body,
                                        Authentication authentication,
                                        RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.certify(itemId, toContext(caller), body.comment());
        recordDecisionAudit(AuditAction.ATTESTATION_ITEM_CERTIFIED, itemId, caller, body.comment(),
                outcome, auditContext);
        return AttestationDecisionResponse.from(outcome);
    }

    @PostMapping("/items/{itemId}/revoke")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_REVIEW')")
    @Operation(summary = "Revoke an item — removes the underlying grant")
    @ApiResponse(responseCode = "200", description = "Decision recorded; grant revoked")
    @ApiResponse(responseCode = "400", description = "Validation error (comment is required)")
    @ApiResponse(responseCode = "403", description = "Caller is the subject or not an eligible reviewer")
    @ApiResponse(responseCode = "404", description = "Item not found")
    @ApiResponse(responseCode = "409", description = "Campaign is not OPEN")
    AttestationDecisionResponse revoke(@PathVariable UUID itemId,
                                       @Valid @RequestBody AttestationRevokeRequest body,
                                       Authentication authentication,
                                       RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.revoke(itemId, toContext(caller), body.comment());
        recordDecisionAudit(AuditAction.ATTESTATION_ITEM_REVOKED, itemId, caller, body.comment(),
                outcome, auditContext);
        return AttestationDecisionResponse.from(outcome);
    }

    @PostMapping("/items/bulk")
    @PreAuthorize("hasAuthority('PERM_ATTESTATION_REVIEW')")
    @Operation(summary = "Apply the same decision to multiple items; rows are evaluated independently")
    @ApiResponse(responseCode = "200", description = "Per-row outcomes")
    @ApiResponse(responseCode = "400", description = "Validation error (empty/oversized list, non-terminal decision)")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer")
    BulkAttestationDecisionResponse bulk(@Valid @RequestBody BulkAttestationDecisionRequest body,
                                         Authentication authentication,
                                         RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.bulkDecide(body.itemIds(), body.decision(), toContext(caller),
                body.comment());
        var auditAction = body.decision() == AttestationItemDecision.REVOKED
                ? AuditAction.ATTESTATION_ITEM_REVOKED
                : AuditAction.ATTESTATION_ITEM_CERTIFIED;
        outcome.rows().stream()
                .filter(r -> r.status() == RowStatus.SUCCESS
                        && r.outcome() != null && !r.outcome().wasIdempotentReplay())
                .forEach(r -> recordBulkRowAudit(auditAction, r.itemId(), caller, body.comment(),
                        auditContext));
        return BulkAttestationDecisionResponse.from(outcome);
    }

    private void recordDecisionAudit(AuditAction action, UUID itemId, JwtClaims caller,
                                     String comment, ItemDecisionOutcome outcome,
                                     RequestAuditContext auditContext) {
        if (outcome.wasIdempotentReplay()) {
            return;
        }
        recordBulkRowAudit(action, itemId, caller, comment, auditContext);
    }

    private void recordBulkRowAudit(AuditAction action, UUID itemId, JwtClaims caller,
                                    String comment, RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        if (comment != null && !comment.isBlank()) {
            metadata.put("comment", comment);
        }
        auditWriter.record(action, AuditResourceType.ATTESTATION_ITEM, itemId, caller, metadata,
                auditContext);
    }

    private static ReviewerContext toContext(JwtClaims caller) {
        return new ReviewerContext(caller.userId(), caller.organizationId(), caller.roleName(),
                caller.permissions());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
