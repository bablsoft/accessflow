package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateQueryInvalidException;
import com.bablsoft.accessflow.deploygov.api.DeploymentGateService;
import com.bablsoft.accessflow.deploygov.api.DeploymentOutcomeService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * The pipeline-facing gate, execution confirmation, and outcome reporting (#693).
 *
 * <p>Like {@code DeploymentRequestController}, this deliberately carries <strong>no class-level
 * {@code @PreAuthorize}</strong>: a CI runner authenticates with an AccessFlow API key, which
 * {@code ApiKeyAuthenticationFilter} resolves into the same {@link JwtClaims} principal as the
 * JWT path, and authorization (visibility for the gate, the actor rule for the mutations) lives
 * in the services.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Deployment Gate",
        description = "Fail-closed deployment gate, execution confirmation and outcome reporting "
                + "(#693, epic #682)")
@RequiredArgsConstructor
class DeploymentGateController {

    private final DeploymentGateService gateService;
    private final DeploymentOutcomeService outcomeService;

    @GetMapping("/deployment-gate")
    @Operation(summary = "Poll releasability for a deployment (JWT or API key)",
            description = "Pass either request_id, or all three of pipeline, version and "
                    + "environment. The newest request for the tuple wins; any 404 means "
                    + "not-releasable to the CI wrappers.")
    @ApiResponse(responseCode = "200", description = "Gate answer — releasable may be false")
    @ApiResponse(responseCode = "400", description = "Invalid parameter combination")
    @ApiResponse(responseCode = "404",
            description = "Unknown pipeline, environment, tuple or id — or not visible")
    DeploymentGateResponse gate(
            @RequestParam(name = "pipeline", required = false) String pipeline,
            @RequestParam(name = "version", required = false) String version,
            @RequestParam(name = "environment", required = false) String environment,
            @RequestParam(name = "request_id", required = false) UUID requestId,
            Authentication authentication) {
        var caller = claims(authentication);
        boolean tuple = pipeline != null && version != null && environment != null;
        if (requestId != null && (pipeline != null || version != null || environment != null)) {
            throw new DeploymentGateQueryInvalidException();
        }
        if (requestId == null && !tuple) {
            throw new DeploymentGateQueryInvalidException();
        }
        var view = requestId != null
                ? gateService.gateByRequestId(requestId, caller.organizationId(), caller.userId(),
                        caller.permissions())
                : gateService.gate(pipeline, environment, version, caller.organizationId(),
                        caller.userId(), caller.permissions());
        return DeploymentGateResponse.from(view);
    }

    @PostMapping("/deployment-requests/{id}/confirm-execution")
    @Operation(summary = "Pipeline confirms it proceeded once the gate answered releasable")
    @ApiResponse(responseCode = "200", description = "Request is EXECUTED (idempotent)")
    @ApiResponse(responseCode = "403", description = "Caller may not act on this request")
    @ApiResponse(responseCode = "404", description = "Deployment request not found")
    @ApiResponse(responseCode = "409",
            description = "Not APPROVED, or approved but not currently releasable")
    DeploymentRequestResponse confirmExecution(@PathVariable UUID id,
                                               Authentication authentication,
                                               RequestAuditContext auditContext) {
        var caller = claims(authentication);
        return DeploymentRequestResponse.from(gateService.confirmExecution(id,
                caller.organizationId(), caller.userId(), caller.permissions(),
                auditContext.ipAddress()));
    }

    @PostMapping("/deployment-requests/{id}/outcome")
    @Operation(summary = "Report the post-execution outcome (idempotent per outcome)")
    @ApiResponse(responseCode = "200", description = "Outcome recorded, or identical repeat")
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "403", description = "Caller may not act on this request")
    @ApiResponse(responseCode = "404", description = "Deployment request not found")
    @ApiResponse(responseCode = "409",
            description = "Not executed yet, or a different outcome was already reported")
    DeploymentRequestResponse reportOutcome(@PathVariable UUID id,
                                            @Valid @RequestBody ReportDeploymentOutcomeRequest body,
                                            Authentication authentication,
                                            RequestAuditContext auditContext) {
        var caller = claims(authentication);
        return DeploymentRequestResponse.from(outcomeService.reportOutcome(id, body.outcome(),
                body.detail(), caller.organizationId(), caller.userId(), caller.permissions(),
                auditContext.ipAddress()));
    }

    private static JwtClaims claims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
