package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiCallSimulationResult;
import com.bablsoft.accessflow.apigov.api.ApiCallSimulationService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
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
 * The API-call decision trace (issue AF-967): why one hypothetical governed call would be decided the
 * way it would.
 *
 * <p>Read-only — nothing is persisted, executed, published or analyzed, and the governed third-party
 * API is never contacted. Returns 200 rather than 201 for that reason: no resource is created.
 */
@RestController
@RequestMapping("/api/v1/admin/api-call-simulations")
@PreAuthorize("hasAuthority('PERM_API_CONNECTOR_MANAGE')")
@Tag(name = "API Governance Decision Trace",
        description = "Trace a hypothetical API call through the live evaluators (ADMIN only)")
@RequiredArgsConstructor
class AdminApiCallSimulationController {

    private final ApiCallSimulationService simulationService;
    private final ApiGovAuditWriter auditWriter;
    private final MessageSource messageSource;
    private final Clock clock;

    @PostMapping
    @Operation(summary = "Trace a hypothetical API call through the live decision evaluators",
            description = "Returns an ordered step-by-step trace of every stage a governed API call "
                    + "passes through, from the connector gate to execution-time response masking, "
                    + "plus the status it would end in. Read-only: no API request is created, no "
                    + "event is published, no AI call is made and the governed API is never called.")
    @ApiResponse(responseCode = "200", description = "Decision trace")
    @ApiResponse(responseCode = "400", description = "Validation error on the request body")
    @ApiResponse(responseCode = "403", description = "Caller cannot manage API connectors")
    @ApiResponse(responseCode = "404",
            description = "Connector or simulated user missing, or in another organization")
    ApiCallSimulationResponse simulate(@Valid @RequestBody SimulateApiCallRequest body,
                                       Authentication authentication,
                                       RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var result = simulationService.simulate(caller.organizationId(), body.toInput());
        recordSimulation(caller, body, result, auditContext);
        return ApiCallSimulationResponse.from(result, this::resolveReason);
    }

    private String resolveReason(String key, List<String> args) {
        return messageSource.getMessage(key, args.toArray(), LocaleContextHolder.getLocale());
    }

    /**
     * A trace discloses the organization's API access topology, so it is audited like the other
     * sensitive reads. It deliberately carries no request path, headers or body:
     * {@code API_CONNECTOR_MANAGE} does not otherwise grant read access to another user's call
     * content, and an audit row must not become a way around that.
     */
    private void recordSimulation(JwtClaims caller, SimulateApiCallRequest body,
                                  ApiCallSimulationResult result, RequestAuditContext auditContext) {
        var metadata = new HashMap<String, Object>();
        metadata.put("simulated_user_id", body.userId().toString());
        metadata.put("ai_outcome", body.toInput().aiOutcome().name());
        metadata.put("step_count", result.steps().size());
        if (body.riskLevel() != null) {
            metadata.put("risk_level", body.riskLevel().name());
        }
        if (result.resultingStatus() != null) {
            metadata.put("resulting_status", result.resultingStatus().name());
        }
        auditWriter.record(AuditAction.ACCESS_SIMULATION_RUN, AuditResourceType.API_CONNECTOR,
                body.connectorId(), caller, metadata, auditContext);
    }

    /**
     * A body that will not deserialize — most often a misspelled {@code ai_outcome} or
     * {@code risk_level} — is a client error. Nothing maps the parse failure globally, so without
     * this the security module's {@code Exception} catch-all turns it into a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.api_call_simulation_body_unreadable", null,
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
