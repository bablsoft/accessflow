package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationResult;
import com.bablsoft.accessflow.deploygov.internal.DeploygovAuditWriter;
import com.bablsoft.accessflow.deploygov.api.DeploymentSimulationService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;

/**
 * The deployment decision trace (issue AF-967): why one hypothetical release would be decided — and
 * gated — the way it would.
 *
 * <p>Read-only — nothing is persisted, executed, published or analyzed. Returns 200 rather than 201
 * for that reason: no resource is created.
 */
@RestController
@RequestMapping("/api/v1/admin/deployment-simulations")
@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')")
@Tag(name = "Deployment Governance Decision Trace",
        description = "Trace a hypothetical deployment through the live evaluators and the release "
                + "gate (ADMIN only)")
@RequiredArgsConstructor
class AdminDeploymentSimulationController {

    private final DeploymentSimulationService simulationService;
    private final DeploygovAuditWriter auditWriter;
    private final MessageSource messageSource;
    private final Clock clock;

    @PostMapping
    @Operation(summary = "Trace a hypothetical deployment through the live decision evaluators",
            description = "Returns an ordered step-by-step trace of every stage a deployment passes "
                    + "through, from the pipeline gate to the release gate, plus the status it "
                    + "would end in and whether the CI job's gate poll would release it. Optionally "
                    + "evaluated at a hypothetical instant, so a freeze window or a policy's "
                    + "maintenance hour can be checked without waiting for it. Read-only: no "
                    + "deployment request is created, no event is published and no AI call is made.")
    @ApiResponse(responseCode = "200", description = "Decision trace")
    @ApiResponse(responseCode = "400", description = "Validation error on the request body")
    @ApiResponse(responseCode = "403", description = "Caller cannot manage deployment pipelines")
    @ApiResponse(responseCode = "404",
            description = "Pipeline, environment or simulated user missing, or in another "
                    + "organization")
    DeploymentSimulationResponse simulate(@Valid @RequestBody SimulateDeploymentRequest body,
                                          Authentication authentication,
                                          RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var result = simulationService.simulate(caller.organizationId(), body.toInput());
        recordSimulation(caller, body, result, auditContext);
        return DeploymentSimulationResponse.from(result, this::resolveReason);
    }

    private String resolveReason(String key, List<String> args) {
        return messageSource.getMessage(key, args.toArray(), LocaleContextHolder.getLocale());
    }

    /**
     * A trace discloses the organization's release topology — freeze windows, approvers, break-glass
     * eligibility — so it is audited like the other sensitive reads. Swallowed on failure by the
     * writer: an audit outage must not deny an admin the explainer.
     */
    private void recordSimulation(JwtClaims caller, SimulateDeploymentRequest body,
                                  DeploymentSimulationResult result,
                                  RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("simulated_user_id", body.userId().toString());
        metadata.put("environment_id", body.environmentId().toString());
        metadata.put("ai_outcome", body.toInput().aiOutcome().name());
        metadata.put("evaluated_at", result.evaluatedAt().toString());
        metadata.put("step_count", result.steps().size());
        metadata.put("releasable", result.releasable());
        if (body.riskLevel() != null) {
            metadata.put("risk_level", body.riskLevel().name());
        }
        if (result.resultingStatus() != null) {
            metadata.put("resulting_status", result.resultingStatus().name());
        }
        auditWriter.record(AuditAction.ACCESS_SIMULATION_RUN, AuditResourceType.DEPLOYMENT_PIPELINE,
                body.pipelineId(), caller.organizationId(), caller.userId(), metadata,
                auditContext == null ? null : auditContext.ipAddress(),
                auditContext == null ? null : auditContext.userAgent());
    }

    /**
     * A body that will not deserialize — most often a misspelled {@code ai_outcome} or
     * {@code risk_level}, or an instant that is not ISO-8601 — is a client error. Nothing maps the
     * parse failure globally, so without this the security module's {@code Exception} catch-all
     * turns it into a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.deployment_simulation_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
