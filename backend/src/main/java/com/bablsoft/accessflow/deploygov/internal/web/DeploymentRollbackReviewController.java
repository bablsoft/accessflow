package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
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

/**
 * The rollback follow-up worklist (#693) — JWT-side, the human half: a {@code ROLLED_BACK}
 * outcome on a review-requiring environment opens a record here that a reviewer (never the
 * deployment's submitter) must acknowledge.
 */
@RestController
@RequestMapping("/api/v1/deployment-rollback-reviews")
@Tag(name = "Deployment Rollback Reviews",
        description = "Acknowledge rolled-back deployments (#693, epic #682)")
@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_REVIEW')")
@RequiredArgsConstructor
class DeploymentRollbackReviewController {

    private final DeploymentRollbackReviewService reviewService;

    @GetMapping
    @Operation(summary = "List the organization's rollback reviews, newest first")
    @ApiResponse(responseCode = "200", description = "Page of rollback reviews")
    DeploymentRollbackReviewPageResponse list(
            @RequestParam(name = "status", required = false) DeploymentRollbackReviewStatus status,
            Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        return DeploymentRollbackReviewPageResponse.from(reviewService.list(
                caller.organizationId(), status, SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one rollback review")
    @ApiResponse(responseCode = "200", description = "Rollback review")
    @ApiResponse(responseCode = "404", description = "Rollback review not found")
    DeploymentRollbackReviewResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentRollbackReviewResponse.from(
                reviewService.get(id, caller.organizationId()));
    }

    @PostMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge a rollback (idempotent; never the deployment's submitter)")
    @ApiResponse(responseCode = "200", description = "Acknowledged, or already reviewed")
    @ApiResponse(responseCode = "404", description = "Rollback review not found")
    @ApiResponse(responseCode = "409", description = "The submitter cannot acknowledge their own rollback")
    DeploymentRollbackReviewResponse acknowledge(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) AcknowledgeDeploymentRollbackRequest body,
            Authentication authentication) {
        var caller = claims(authentication);
        return DeploymentRollbackReviewResponse.from(reviewService.acknowledge(id,
                caller.organizationId(), caller.userId(), body == null ? null : body.comment()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
