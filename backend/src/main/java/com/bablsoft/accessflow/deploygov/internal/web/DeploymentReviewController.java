package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
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

import java.util.UUID;

// Audit rows for approve/reject land with the deployment audit fan-out (#695).
@RestController
@RequestMapping("/api/v1/deployment-reviews")
@Tag(name = "Deployment Reviews", description = "Approve or reject governed deployments")
@RequiredArgsConstructor
class DeploymentReviewController {

    private final DeploymentReviewService reviewService;

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
                                       Authentication authentication) {
        return DeploymentDecisionResponse.from(
                reviewService.approve(id, context(claims(authentication)), body.comment()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_DEPLOYMENT_REVIEW')")
    @Operation(summary = "Reject a deployment request")
    @ApiResponse(responseCode = "200", description = "Decision recorded")
    @ApiResponse(responseCode = "403", description = "Caller is not an eligible approver")
    @ApiResponse(responseCode = "409", description = "Self-approval, or not awaiting review")
    DeploymentDecisionResponse reject(@PathVariable UUID id,
                                      @Valid @RequestBody DeploymentDecisionRequest body,
                                      Authentication authentication) {
        return DeploymentDecisionResponse.from(
                reviewService.reject(id, context(claims(authentication)), body.comment()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static DeploymentReviewService.ReviewerContext context(JwtClaims caller) {
        return new DeploymentReviewService.ReviewerContext(caller.userId(), caller.organizationId(),
                caller.roleName(), caller.permissions());
    }
}
