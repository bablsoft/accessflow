package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessReviewService;
import com.bablsoft.accessflow.access.api.AccessReviewService.DecisionOutcome;
import com.bablsoft.accessflow.access.api.AccessReviewService.ReviewerContext;
import com.bablsoft.accessflow.audit.api.AuditAction;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/access-requests")
@Tag(name = "Access Requests (Admin)", description = "Reviewer/admin queue for access-grant requests")
@RequiredArgsConstructor
class AdminAccessRequestController {

    private final AccessReviewService accessReviewService;
    private final AccessRequestAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "List access requests the caller can act on as a reviewer")
    @ApiResponse(responseCode = "200", description = "Page of currently-actionable pending access requests")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer")
    PendingAccessRequestsPageResponse listPending(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = accessReviewService.listPendingForReviewer(toContext(caller),
                SpringPageableAdapter.toPageRequest(pageable));
        return PendingAccessRequestsPageResponse.from(page);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Approve an access request; final stage materialises a time-boxed grant")
    @ApiResponse(responseCode = "200", description = "Decision recorded; resulting status returned")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller is the requester, not a reviewer, or not eligible")
    @ApiResponse(responseCode = "404", description = "Access request not found")
    @ApiResponse(responseCode = "409", description = "Access request is not pending or a standing permission already exists")
    AccessDecisionResponse approve(@PathVariable UUID id,
                                   @Valid @RequestBody AccessDecisionRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = accessReviewService.approve(id, toContext(caller), body.comment());
        recordDecision(AuditAction.ACCESS_REQUEST_APPROVED, id, caller, outcome, body.comment(),
                auditContext);
        return AccessDecisionResponse.from(id, outcome);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Reject an access request")
    @ApiResponse(responseCode = "200", description = "Decision recorded; request transitioned to REJECTED")
    @ApiResponse(responseCode = "400", description = "Validation error (comment is required)")
    @ApiResponse(responseCode = "403", description = "Caller is the requester, not a reviewer, or not eligible")
    @ApiResponse(responseCode = "404", description = "Access request not found")
    @ApiResponse(responseCode = "409", description = "Access request is not pending")
    AccessDecisionResponse reject(@PathVariable UUID id,
                                  @Valid @RequestBody AccessRejectRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = accessReviewService.reject(id, toContext(caller), body.comment());
        recordDecision(AuditAction.ACCESS_REQUEST_REJECTED, id, caller, outcome, body.comment(),
                auditContext);
        return AccessDecisionResponse.from(id, outcome);
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke an active grant early, before its natural expiry")
    @ApiResponse(responseCode = "200", description = "Grant revoked (or no-op when already inactive)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    @ApiResponse(responseCode = "404", description = "Access request not found")
    AccessRevocationResponse revoke(@PathVariable UUID id,
                                    @Valid @RequestBody AccessDecisionRequest body,
                                    Authentication authentication,
                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = accessReviewService.revoke(id, toContext(caller), body.comment());
        if (!outcome.wasNoOp()) {
            var metadata = new HashMap<String, Object>();
            if (body.comment() != null && !body.comment().isBlank()) {
                metadata.put("comment", body.comment());
            }
            auditWriter.record(AuditAction.ACCESS_GRANT_REVOKED, id, caller, metadata, auditContext);
        }
        return AccessRevocationResponse.from(id, outcome);
    }

    private void recordDecision(AuditAction action, UUID id, JwtClaims caller,
                                DecisionOutcome outcome, String comment,
                                RequestAuditContext auditContext) {
        if (outcome.wasIdempotentReplay()) {
            return;
        }
        var metadata = new HashMap<String, Object>();
        if (comment != null && !comment.isBlank()) {
            metadata.put("comment", comment);
        }
        metadata.put("resulting_status", outcome.resultingStatus().name());
        auditWriter.record(action, id, caller, metadata, auditContext);
    }

    private static ReviewerContext toContext(JwtClaims caller) {
        return new ReviewerContext(caller.userId(), caller.organizationId(), caller.role());
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
