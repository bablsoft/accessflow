package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionCodec;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewCustomRuleRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewCustomRuleResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleTestRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleTestResponse;
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
 * Admin CRUD over custom SQL review rules and the draft test run (#1010), modelled on
 * {@link AdminSqlReviewRulesetController}: the service owns every decision, the controller binds,
 * decodes the condition JSON through the codec, delegates, maps and writes the audit row
 * synchronously so {@code ip_address} / {@code user_agent} come from the live request. The test
 * run writes no audit row.
 */
@RestController
@RequestMapping("/api/v1/admin/sql-review-rules")
@PreAuthorize("hasAuthority('PERM_SQL_REVIEW_MANAGE')")
@Tag(name = "SQL Review Custom Rules",
        description = "Admin management of organization-defined SQL review rules (epic #1008)")
@RequiredArgsConstructor
@Slf4j
class AdminSqlReviewCustomRuleController {

    private final SqlReviewCustomRuleService customRuleService;
    private final SqlRuleConditionCodec conditionCodec;
    private final SqlReviewFindingRenderer findingRenderer;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    @GetMapping
    @Operation(summary = "List the organization's custom SQL review rules (by rule id)")
    @ApiResponse(responseCode = "200", description = "Custom rules, enabled and disabled")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    List<SqlReviewCustomRuleResponse> list(Authentication authentication) {
        var caller = currentClaims(authentication);
        return customRuleService.list(caller.organizationId()).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a custom SQL review rule with its condition tree")
    @ApiResponse(responseCode = "200", description = "Custom rule")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    SqlReviewCustomRuleResponse get(@PathVariable UUID id, Authentication authentication) {
        var caller = currentClaims(authentication);
        return toResponse(customRuleService.get(caller.organizationId(), id));
    }

    @PostMapping
    @Operation(summary = "Create a custom SQL review rule")
    @ApiResponse(responseCode = "201", description = "Rule created")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "409", description = "The organization already has a rule with that rule id")
    @ApiResponse(responseCode = "422", description = "Malformed rule, or the organization's rule cap is reached")
    ResponseEntity<SqlReviewCustomRuleResponse> create(@Valid @RequestBody SqlReviewCustomRuleRequest body,
                                                       Authentication authentication,
                                                       RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var created = customRuleService.create(caller.organizationId(),
                body.toCommand(conditionCodec.fromJson(body.condition())));
        recordAudit(AuditAction.SQL_REVIEW_RULE_CREATED, created.id(), caller, auditContext, metadata(created));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(toResponse(created));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a custom SQL review rule — the rule id is immutable")
    @ApiResponse(responseCode = "200", description = "Rule updated")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    @ApiResponse(responseCode = "422", description = "Malformed rule or a changed rule id")
    SqlReviewCustomRuleResponse update(@PathVariable UUID id,
                                       @Valid @RequestBody SqlReviewCustomRuleRequest body,
                                       Authentication authentication,
                                       RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var updated = customRuleService.update(caller.organizationId(), id,
                body.toCommand(conditionCodec.fromJson(body.condition())));
        recordAudit(AuditAction.SQL_REVIEW_RULE_UPDATED, id, caller, auditContext, metadata(updated));
        return toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a custom SQL review rule and every ruleset config row naming it")
    @ApiResponse(responseCode = "204", description = "Rule deleted")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "404", description = "Rule not found")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication,
                                RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        customRuleService.delete(caller.organizationId(), id);
        recordAudit(AuditAction.SQL_REVIEW_RULE_DELETED, id, caller, auditContext, Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @Operation(summary = "Run a draft custom rule against SQL",
            description = "Returns the findings the draft would produce at its default severity. Nothing is "
                    + "persisted, audited or published, and nothing runs against a customer database.")
    @ApiResponse(responseCode = "200", description = "Findings with rendered messages")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    @ApiResponse(responseCode = "422", description = "Malformed draft, unsupported dialect, or unparseable SQL")
    SqlReviewRuleTestResponse test(@Valid @RequestBody SqlReviewRuleTestRequest body) {
        var draft = body.rule().toCommand(conditionCodec.fromJson(body.rule().condition()));
        var locale = LocaleContextHolder.getLocale();
        var result = customRuleService.test(draft, body.sql(), body.dialect());
        return SqlReviewRuleTestResponse.from(result, finding -> findingRenderer.message(finding, locale));
    }

    /**
     * A body that will not deserialize — an unknown {@code category}, {@code default_severity} or
     * {@code dialect} literal — is a client error, not the security catch-all's 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.sql_review_rule_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private SqlReviewCustomRuleResponse toResponse(SqlReviewCustomRuleView view) {
        return SqlReviewCustomRuleResponse.from(view, conditionCodec.toJson(view.condition()));
    }

    private static Map<String, Object> metadata(SqlReviewCustomRuleView view) {
        return Map.of(
                "rule_id", view.ruleId(),
                "name", view.name(),
                "category", view.category().name(),
                "default_severity", view.defaultSeverity().name(),
                "enabled", view.enabled());
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(AuditAction action, UUID resourceId, JwtClaims caller,
                             RequestAuditContext auditContext, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.SQL_REVIEW_RULE,
                    resourceId,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on sql_review_rule {}", action, resourceId, ex);
        }
    }
}
