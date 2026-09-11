package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetView;
import com.bablsoft.accessflow.sqlreview.internal.web.model.CreateSqlReviewRulesetRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRulesetResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.UpdateSqlReviewRulesetRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD over SQL review rulesets (#863), modelled on the routing-policy controller: the service
 * owns every decision, the controller binds, delegates, maps and writes the audit row synchronously
 * so {@code ip_address} / {@code user_agent} come from the live request.
 */
@RestController
@RequestMapping("/api/v1/admin/sql-review-rulesets")
@PreAuthorize("hasAuthority('PERM_SQL_REVIEW_MANAGE')")
@Tag(name = "SQL Review Rulesets",
        description = "Admin management of deterministic SQL review rulesets — per-environment rule "
                + "severities and params (epic #860)")
@RequiredArgsConstructor
@Slf4j
class AdminSqlReviewRulesetController {

    private final SqlReviewRulesetService rulesetService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    @GetMapping
    @Operation(summary = "List the organization's SQL review rulesets (by name)")
    @ApiResponse(responseCode = "200", description = "Rulesets with their rule configs")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    List<SqlReviewRulesetResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return rulesetService.list(caller.organizationId()).stream()
                .map(SqlReviewRulesetResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a SQL review ruleset with its per-rule severities and params")
    @ApiResponse(responseCode = "200", description = "Ruleset")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Ruleset not found")
    SqlReviewRulesetResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return SqlReviewRulesetResponse.from(rulesetService.get(caller.organizationId(), id));
    }

    @PostMapping
    @Operation(summary = "Create a SQL review ruleset")
    @ApiResponse(responseCode = "201", description = "Ruleset created")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "409", description = "Environment already bound, or a default already exists")
    @ApiResponse(responseCode = "422", description = "Unknown or duplicate rule id, or malformed params")
    ResponseEntity<SqlReviewRulesetResponse> create(@Valid @RequestBody CreateSqlReviewRulesetRequest body,
                                                    Authentication authentication,
                                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = rulesetService.create(caller.organizationId(), body.toCommand());
        recordAudit(AuditAction.SQL_REVIEW_RULESET_CREATED, created.id(), caller, auditContext, metadata(created));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(SqlReviewRulesetResponse.from(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a SQL review ruleset — name, description, environment, enabled and "
            + "the full rule-config set")
    @ApiResponse(responseCode = "200", description = "Ruleset updated")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Ruleset not found")
    @ApiResponse(responseCode = "409", description = "Environment already bound to another ruleset, or a "
            + "default already exists")
    @ApiResponse(responseCode = "422", description = "Unknown or duplicate rule id, or malformed params")
    SqlReviewRulesetResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateSqlReviewRulesetRequest body,
                                    Authentication authentication,
                                    RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = rulesetService.update(caller.organizationId(), id, body.toCommand());
        recordAudit(AuditAction.SQL_REVIEW_RULESET_UPDATED, id, caller, auditContext, metadata(updated));
        return SqlReviewRulesetResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a SQL review ruleset and its rule configs")
    @ApiResponse(responseCode = "204", description = "Ruleset deleted")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Ruleset not found")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        rulesetService.delete(caller.organizationId(), id);
        recordAudit(AuditAction.SQL_REVIEW_RULESET_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    /**
     * A body that will not deserialize — most often an unknown {@code severity} or
     * {@code environment} literal — is a client error. Nothing maps the parse failure globally, so
     * without this the security module's {@code Exception} catch-all turns it into a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.sql_review_ruleset_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private static Map<String, Object> metadata(SqlReviewRulesetView view) {
        return Map.of(
                "name", view.name(),
                "environment", view.environment() == null ? "DEFAULT" : view.environment().name(),
                "enabled", view.enabled(),
                "rule_count", view.rules().size());
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SQL_REVIEW_RULESET,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on sql_review_ruleset {}", action, resourceId, ex);
        }
    }
}
