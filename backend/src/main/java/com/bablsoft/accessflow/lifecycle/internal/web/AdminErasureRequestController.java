package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService;
import com.bablsoft.accessflow.lifecycle.api.ErasureReviewService.ReviewerContext;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/lifecycle/erasure-requests")
@Tag(name = "Erasure Requests (Admin)", description = "Admin review queue for right-to-erasure requests")
@RequiredArgsConstructor
class AdminErasureRequestController {

    private final ErasureReviewService erasureReviewService;
    private final LifecycleAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List erasure requests awaiting review (excludes the caller's own)")
    @ApiResponse(responseCode = "200", description = "Page of pending erasure requests")
    @ApiResponse(responseCode = "403", description = "Caller is not an admin")
    ErasureRequestPageResponse listPending(Authentication authentication, Pageable pageable) {
        var caller = currentClaims(authentication);
        var page = erasureReviewService.listPending(caller.organizationId(), caller.userId(),
                SpringPageableAdapter.toPageRequest(pageable));
        return ErasureRequestPageResponse.from(page);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Approve an erasure request")
    @ApiResponse(responseCode = "200", description = "Request approved")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter or not an admin")
    @ApiResponse(responseCode = "404", description = "Deletion request not found")
    @ApiResponse(responseCode = "409", description = "Request is not awaiting review")
    ErasureRequestResponse approve(@PathVariable UUID id,
                                   @Valid @RequestBody ErasureDecisionRequest body,
                                   Authentication authentication,
                                   RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = erasureReviewService.approve(id,
                new ReviewerContext(caller.userId(), caller.organizationId()), body.comment());
        auditWriter.record(AuditAction.DATA_ERASURE_APPROVED, AuditResourceType.DELETION_REQUEST,
                id, caller, metadata(view, body.comment()), auditContext);
        return ErasureRequestResponse.from(view);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reject an erasure request")
    @ApiResponse(responseCode = "200", description = "Request rejected")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter or not an admin")
    @ApiResponse(responseCode = "404", description = "Deletion request not found")
    @ApiResponse(responseCode = "409", description = "Request is not awaiting review")
    ErasureRequestResponse reject(@PathVariable UUID id,
                                  @Valid @RequestBody ErasureDecisionRequest body,
                                  Authentication authentication,
                                  RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var view = erasureReviewService.reject(id,
                new ReviewerContext(caller.userId(), caller.organizationId()), body.comment());
        auditWriter.record(AuditAction.DATA_ERASURE_REJECTED, AuditResourceType.DELETION_REQUEST,
                id, caller, metadata(view, body.comment()), auditContext);
        return ErasureRequestResponse.from(view);
    }

    private static Map<String, Object> metadata(ErasureRequestView view, String comment) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("subjectIdentifier", view.subjectIdentifier());
        meta.put("status", view.status().name());
        if (comment != null && !comment.isBlank()) {
            meta.put("comment", comment);
        }
        return meta;
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
