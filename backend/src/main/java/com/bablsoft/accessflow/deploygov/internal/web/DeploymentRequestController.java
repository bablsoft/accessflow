package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestService;
import com.bablsoft.accessflow.deploygov.api.DeploymentReviewService;
import com.bablsoft.accessflow.deploygov.internal.DeploygovAuditWriter;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pipeline-facing deployment trigger and the human-facing request list/detail (#691).
 *
 * <p>Deliberately carries <strong>no class-level {@code @PreAuthorize}</strong>: triggering is
 * authorized by the per-pipeline {@code can_trigger} grant inside the service, not by a functional
 * permission. A CI runner authenticates with an AccessFlow API key, which
 * {@code ApiKeyAuthenticationFilter} resolves into the same {@link JwtClaims} principal as the JWT
 * path — so this controller needs no auth code of its own.
 */
@RestController
@RequestMapping("/api/v1/deployment-requests")
@Tag(name = "Deployment Requests",
        description = "Trigger and track governed CI/CD deployments (#691, epic #682)")
@RequiredArgsConstructor
class DeploymentRequestController {

    private final DeploymentRequestService requestService;
    private final DeploymentReviewService reviewService;
    private final DeploygovAuditWriter auditWriter;

    @PostMapping
    @Operation(summary = "Trigger a governed deployment (JWT or API key; idempotent on the CI run)")
    @ApiResponse(responseCode = "202", description = "Deployment request accepted")
    @ApiResponse(responseCode = "200", description = "The same CI run was already triggered; the "
            + "existing request is returned and nothing was created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller holds no trigger permission on the pipeline")
    @ApiResponse(responseCode = "404", description = "Pipeline or environment not found")
    ResponseEntity<DeploymentRequestResponse> submit(
            @Valid @RequestBody SubmitDeploymentRequestRequest body,
            Authentication authentication, RequestAuditContext auditContext) {
        var caller = claims(authentication);
        var result = requestService.submit(body.toCommand(caller.organizationId(), caller.userId(),
                isAdmin(caller), auditContext.ipAddress()));
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(DeploymentRequestResponse.from(result.request()));
    }

    @GetMapping
    @Operation(summary = "List deployment requests visible to the caller")
    @ApiResponse(responseCode = "200", description = "Page of deployment requests")
    DeploymentRequestPageResponse list(
            @RequestParam(name = "status", required = false) QueryStatus status,
            @RequestParam(name = "pipeline_id", required = false) UUID pipelineId,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "submitted_by", required = false) UUID submittedBy,
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            Authentication authentication, Pageable pageable) {
        var caller = claims(authentication);
        // submitted_by is honoured only for callers who may see the whole organization — the same
        // predicate the detail endpoint uses, so listing and reading agree on visibility. Everyone
        // else is hard-scoped to their own submissions.
        var submitter = requestService.canViewAll(caller.permissions())
                ? submittedBy : caller.userId();
        var filter = new DeploymentRequestListFilter(caller.organizationId(), submitter, pipelineId,
                environment, version, status, from, to);
        return DeploymentRequestPageResponse.from(
                requestService.list(filter, SpringPageableAdapter.toPageRequest(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a deployment request with its AI analysis and review decisions")
    @ApiResponse(responseCode = "200", description = "Deployment request")
    @ApiResponse(responseCode = "404", description = "Not found, or not visible to the caller")
    DeploymentRequestResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = claims(authentication);
        // canReview is the reviewer's own eligibility, resolved server-side (#770): the review
        // plan's approver rules are not derivable from this payload, so the UI cannot decide on
        // its own whether to offer a decision. Answered after the visibility guard has passed.
        var view = requestService.get(id, caller.organizationId(), caller.userId(),
                caller.permissions());
        return DeploymentRequestResponse.from(view, reviewService.canReview(id, reviewer(caller)));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Submitter cancels a deployment awaiting review, or a scheduled approved one")
    @ApiResponse(responseCode = "204", description = "Deployment request cancelled")
    @ApiResponse(responseCode = "403", description = "Caller is not the submitter")
    @ApiResponse(responseCode = "404", description = "Deployment request not found")
    @ApiResponse(responseCode = "409", description = "The request is in no cancellable state")
    void cancel(@PathVariable UUID id, Authentication authentication,
                RequestAuditContext auditContext) {
        var caller = claims(authentication);
        requestService.cancel(id, caller.organizationId(), caller.userId());
        // #695: audited after the service call so a rejected cancel never writes a row.
        auditWriter.record(AuditAction.DEPLOYMENT_CANCELLED, AuditResourceType.DEPLOYMENT_REQUEST,
                id, caller.organizationId(), caller.userId(), Map.of(),
                auditContext.ipAddress(), auditContext.userAgent());
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private static DeploymentReviewService.ReviewerContext reviewer(JwtClaims caller) {
        return new DeploymentReviewService.ReviewerContext(caller.userId(), caller.organizationId(),
                caller.roleName(), caller.permissions());
    }

    private static boolean isAdmin(JwtClaims caller) {
        return caller.has(Permission.QUERY_ADMIN);
    }
}
