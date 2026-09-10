package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.AccessSimulationResult;
import com.bablsoft.accessflow.workflow.api.AccessSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;

/**
 * The access explainer's decision trace (AF-859): why one hypothetical request would be decided the
 * way it would.
 *
 * <p>Read-only — nothing is persisted, executed, published or analyzed. Returns 200 rather than 201
 * for that reason: no resource is created.
 */
@RestController
@RequestMapping("/api/v1/admin/access-simulations")
@PreAuthorize("hasAuthority('PERM_DATASOURCE_PERMISSION_MANAGE')")
@Tag(name = "Access Explainer",
        description = "Trace a hypothetical request through the live evaluators (ADMIN only)")
@RequiredArgsConstructor
@Slf4j
class AdminAccessSimulationController {

    private final AccessSimulationService accessSimulationService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;
    private final Clock clock;

    @PostMapping
    @Operation(summary = "Trace a hypothetical request through the live decision evaluators",
            description = "Returns an ordered step-by-step trace of every stage a query passes "
                    + "through, from the submission gate to execution-time masking, plus the "
                    + "status it would end in. Read-only: no query request is created, no event is "
                    + "published, no AI call is made and no customer database is contacted.")
    @ApiResponse(responseCode = "200", description = "Decision trace")
    @ApiResponse(responseCode = "400", description = "Validation error on the request body")
    @ApiResponse(responseCode = "403", description = "Caller cannot manage datasource permissions")
    @ApiResponse(responseCode = "404",
            description = "Datasource or simulated user missing, or in another organization")
    AccessSimulationResponse simulate(@Valid @RequestBody SimulateAccessRequest body,
                                      Authentication authentication,
                                      RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var input = body.toInput();
        var result = accessSimulationService.simulate(caller.organizationId(), input);
        recordSimulation(caller, body, result, auditContext);
        return AccessSimulationResponse.from(result, this::resolveReason);
    }

    private String resolveReason(String key, List<String> args) {
        return messageSource.getMessage(key, args.toArray(), LocaleContextHolder.getLocale());
    }

    /**
     * A trace discloses the organization's access topology, so it is audited like the other
     * sensitive reads. It deliberately carries no SQL: {@code DATASOURCE_PERMISSION_MANAGE} does not
     * otherwise grant read access to query text, and an audit row must not become a way around that.
     * Swallowed on failure — an audit outage must not deny an admin the explainer.
     */
    private void recordSimulation(JwtClaims caller, SimulateAccessRequest body,
                                  AccessSimulationResult result, RequestAuditContext auditContext) {
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
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.ACCESS_SIMULATION_RUN,
                    AuditResourceType.DATASOURCE,
                    body.datasourceId(),
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for ACCESS_SIMULATION_RUN", ex);
        }
    }

    /**
     * A body that will not deserialize — most often a misspelled {@code ai_outcome} or
     * {@code risk_level} — is a client error. Nothing maps the parse failure globally, so without
     * this the security module's {@code Exception} catch-all turns it into a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.access_simulation_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    /**
     * A misspelled path or query value is a client error. Nothing maps
     * {@code MethodArgumentTypeMismatchException} globally, so without this the security module's
     * {@code Exception} catch-all turns it into a 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleBadParameter(MethodArgumentTypeMismatchException ex) {
        var detail = messageSource.getMessage("error.invalid_request_parameter",
                new Object[]{ex.getName()}, LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", clock.instant().toString());
        return problem;
    }

    private static JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }
}
