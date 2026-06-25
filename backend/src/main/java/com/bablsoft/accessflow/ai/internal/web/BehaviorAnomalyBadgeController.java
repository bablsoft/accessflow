package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.BehaviorAnomalyLookupService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;
import com.bablsoft.accessflow.ai.api.UserBehaviorAnomalyService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The current user's <em>own</em> anomalies (UBA, AF-383): the open-anomaly badge plus a self-scoped
 * list and acknowledge/dismiss (AF-498 dashboard widget). Available to any authenticated user; every
 * read and mutation is bound to the caller's own id, so it never leaks or mutates another user's
 * anomalies. The org-wide ADMIN/AUDITOR surface is {@code AdminBehaviorAnomalyController}.
 */
@RestController
@RequestMapping("/api/v1/anomalies")
@Tag(name = "Behavior Anomaly Badge", description = "The caller's own anomaly badge, list and lifecycle")
@RequiredArgsConstructor
@Slf4j
class BehaviorAnomalyBadgeController {

    private static final int MAX_PAGE_SIZE = 200;

    private final BehaviorAnomalyLookupService anomalyLookupService;
    private final UserBehaviorAnomalyService userAnomalyService;
    private final AuditLogService auditLogService;

    @GetMapping("/badge")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Open-anomaly badge for the current user (optionally one datasource)")
    @ApiResponse(responseCode = "200", description = "The badge (openCount + maxScore)")
    AnomalyBadgeResponse badge(
            @Parameter(description = "Optional datasource scope") @RequestParam(required = false) UUID datasourceId,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        var view = datasourceId == null
                ? anomalyLookupService.badgeForUser(organizationId, userId)
                : anomalyLookupService.badgeForUserDatasource(organizationId, userId, datasourceId);
        return AnomalyBadgeResponse.from(view);
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the current user's own anomalies (optionally filtered by status)")
    @ApiResponse(responseCode = "200", description = "Page of the caller's anomalies, detectedAt DESC")
    @ApiResponse(responseCode = "400", description = "Page size exceeds the maximum (200)")
    AnomalyPageResponse mine(
            @Parameter(description = "Filter by status (OPEN/ACKNOWLEDGED/DISMISSED)")
            @RequestParam(required = false) BehaviorAnomalyStatus status,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @PageableDefault(size = 20, sort = "detectedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        PageResponse<BehaviorAnomalyView> page = userAnomalyService.listForUser(organizationId, userId,
                status, SpringPageableAdapter.toPageRequest(pageable));
        return AnomalyPageResponse.from(page.map(AnomalyResponse::from));
    }

    @PostMapping("/mine/{id}/acknowledge")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Acknowledge one of the current user's own OPEN anomalies")
    @ApiResponse(responseCode = "200", description = "Acknowledged")
    @ApiResponse(responseCode = "404", description = "Not found / not owned by the caller")
    @ApiResponse(responseCode = "409", description = "Anomaly is not OPEN")
    AnomalyResponse acknowledgeOwn(@PathVariable UUID id,
                                   @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
                                   @AuthenticationPrincipal(expression = "userId") UUID userId,
                                   RequestAuditContext auditContext) {
        var view = userAnomalyService.acknowledgeOwn(organizationId, userId, id);
        recordAudit(AuditAction.ANOMALY_ACKNOWLEDGED, id, organizationId, userId, auditContext, view);
        return AnomalyResponse.from(view);
    }

    @PostMapping("/mine/{id}/dismiss")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Dismiss one of the current user's own anomalies")
    @ApiResponse(responseCode = "200", description = "Dismissed")
    @ApiResponse(responseCode = "404", description = "Not found / not owned by the caller")
    @ApiResponse(responseCode = "409", description = "Anomaly is already dismissed")
    AnomalyResponse dismissOwn(@PathVariable UUID id,
                               @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
                               @AuthenticationPrincipal(expression = "userId") UUID userId,
                               RequestAuditContext auditContext) {
        var view = userAnomalyService.dismissOwn(organizationId, userId, id);
        recordAudit(AuditAction.ANOMALY_DISMISSED, id, organizationId, userId, auditContext, view);
        return AnomalyResponse.from(view);
    }

    private void recordAudit(AuditAction action, UUID anomalyId, UUID organizationId, UUID actorUserId,
                             RequestAuditContext auditContext, BehaviorAnomalyView view) {
        try {
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.BEHAVIOR_ANOMALY,
                    anomalyId,
                    organizationId,
                    actorUserId,
                    Map.of("feature", view.feature(), "user_id", view.userId().toString(),
                            "datasource_id", view.datasourceId().toString(), "self_service", true),
                    auditContext == null ? null : auditContext.ipAddress(),
                    auditContext == null ? null : auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on anomaly {}", action, anomalyId, ex);
        }
    }
}
