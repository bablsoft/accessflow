package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.internal.DeploygovAuditWriter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployment-reviews")
@Tag(name = "Deployment Reviews", description = "Approve or reject governed deployments")
@RequiredArgsConstructor
class DeploymentReviewController {

    private final DeploymentReviewService reviewService;
    private final DeploygovAuditWriter auditWriter;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DEPLOYMENT_REVIEW')")
    @Operation(summary = "List deployment requests awaiting the caller's review")
    @ApiResponse(responseCode = "200", description = "Page of pending deployment reviews")
    PendingDeploymentReviewResponse.Page pending(Authentication authentication, Pageable pageable,
                                                 @RequestParam(name = "pipeline_id", required = false)
                                                 UUID pipelineId) {
        var caller = claims(authentication);
        var filter = new DeploymentReviewService.PendingDeploymentReviewFilter(pipelineId);
        return PendingDeploymentReviewResponse.Page.from(reviewService.listPending(context(caller),
                filter, SpringPageableAdapter.toPageRequest(pageable)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_DEPLOYMENT_REVIEW')")
    @Operation(summary = "Approve a deployment request (submitter can never self-approve)")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Caller is not an eligible approver")
    @ApiResponse(responseCode = "409", description = "Self-approval, or not awaiting review")
    DeploymentDecisionResponse approve(@PathVariable UUID id,
                                       @Valid @RequestBody DeploymentDecisionRequest body,
                                       Authentication authentication,
                                       RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var outcome = reviewService.approve(id, context(caller), body.comment());
        // Recorded per decision (before quorum) like the apigov sibling — every reviewer verdict
        // is its own audit row (#695) — but an idempotent replay of an existing decision is not a
        // new verdict and writes nothing.
        if (!outcome.duplicate()) {
            auditWriter.record(AuditAction.DEPLOYMENT_APPROVED,
                    AuditResourceType.DEPLOYMENT_REQUEST, id, caller.organizationId(),
                    caller.userId(), Map.of(), auditContext.ipAddress(),
                    auditContext.userAgent());
        }
        return DeploymentDecisionResponse.from(outcome);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_DEPLOYMENT_REVIEW')")
    @Operation(summary = "Reject a deployment request")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Caller is not an eligible approver")
    @ApiResponse(responseCode = "409", description = "Self-approval, or not awaiting review")
    DeploymentDecisionResponse reject(@PathVariable UUID id,
                                      @Valid @RequestBody DeploymentDecisionRequest body,
                                      Authentication authentication,
                                      RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var outcome = reviewService.reject(id, context(caller), body.comment());
        if (!outcome.duplicate()) {
            auditWriter.record(AuditAction.DEPLOYMENT_REJECTED,
                    AuditResourceType.DEPLOYMENT_REQUEST, id, caller.organizationId(),
                    caller.userId(), Map.of(), auditContext.ipAddress(),
                    auditContext.userAgent());
        }
        return DeploymentDecisionResponse.from(outcome);
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static DeploymentReviewService.ReviewerContext context(JwtClaims caller) {
        return new DeploymentReviewService.ReviewerContext(caller.userId(), caller.organizationId(),
                caller.roleName(), caller.permissions());
    }
}
