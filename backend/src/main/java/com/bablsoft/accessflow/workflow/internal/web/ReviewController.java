package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ReviewService;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import com.bablsoft.accessflow.workflow.api.ReviewService.ReviewerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Reviewer workflow endpoints")
@RequiredArgsConstructor
@Slf4j
class ReviewController {

    private final ReviewService reviewService;
    private final AuditLogService auditLogService;

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "List queries the caller can act on as a reviewer")
    @ApiResponse(responseCode = "200", description = "Page of currently-actionable pending queries")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is not a reviewer")
    PendingReviewsPageResponse listPending(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = reviewService.listPendingForReviewer(toContext(caller),
                        SpringPageableAdapter.toPageRequest(pageable))
                .map(PendingReviewItem::from);
        return PendingReviewsPageResponse.from(page);
    }

    @PostMapping("/{queryId}/approve")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Record an approval for a query awaiting review")
    @ApiResponse(responseCode = "200", description = "Decision recorded; resulting status returned")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse approve(@PathVariable UUID queryId,
                                   @Valid @RequestBody ReviewDecisionRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.approve(queryId, toContext(caller), body.comment());
        recordDecisionAudit(AuditAction.QUERY_APPROVED, queryId, caller, outcome, body.comment(),
                auditContext);
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    @PostMapping("/{queryId}/reject")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Reject a query awaiting review")
    @ApiResponse(responseCode = "200", description = "Decision recorded; query transitioned to REJECTED")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse reject(@PathVariable UUID queryId,
                                  @Valid @RequestBody ReviewDecisionRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.reject(queryId, toContext(caller), body.comment());
        recordDecisionAudit(AuditAction.QUERY_REJECTED, queryId, caller, outcome, body.comment(),
                auditContext);
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    @PostMapping("/{queryId}/request-changes")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    @Operation(summary = "Request changes from the submitter; query stays PENDING_REVIEW")
    @ApiResponse(responseCode = "200", description = "Decision recorded; status unchanged")
    @ApiResponse(responseCode = "400", description = "Validation error (comment is required)")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter, not a reviewer, or not an eligible approver")
    @ApiResponse(responseCode = "404", description = "Query not found")
    @ApiResponse(responseCode = "409", description = "Query is not in PENDING_REVIEW")
    ReviewDecisionResponse requestChanges(@PathVariable UUID queryId,
                                          @Valid @RequestBody ReviewChangeRequestRequest body,
                                          Authentication authentication) {
        var caller = currentClaims(authentication);
        var outcome = reviewService.requestChanges(queryId, toContext(caller), body.comment());
        return ReviewDecisionResponse.from(queryId, outcome);
    }

    private void recordDecisionAudit(AuditAction action, UUID queryId, JwtClaims caller,
                                     DecisionOutcome outcome, String comment,
                                     RequestAuditContext auditContext) {
        if (outcome.wasIdempotentReplay()) {
            return;
        }
        try {
            var metadata = new HashMap<String, Object>();
            if (comment != null && !comment.isBlank()) {
                metadata.put("comment", comment);
            }
            metadata.put("resulting_status", outcome.resultingStatus().name());
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.QUERY_REQUEST,
                    queryId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query {}", action, queryId, ex);
        }
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static ReviewerContext toContext(JwtClaims caller) {
        return new ReviewerContext(caller.userId(), caller.organizationId(), caller.role());
    }
}
