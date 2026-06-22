package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.AnomalyListFilter;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyAdminService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyView;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin / auditor read + admin acknowledge/dismiss over detected behavioural anomalies (UBA,
 * AF-383). Reads are available to ADMIN and AUDITOR; the acknowledge / dismiss mutations are
 * ADMIN-only. Binding-only — all logic lives in {@link BehaviorAnomalyAdminService}.
 */
@RestController
@RequestMapping("/api/v1/admin/anomalies")
@Tag(name = "Admin Behavior Anomalies",
        description = "Behavioural anomaly detection (UBA) — read (ADMIN/AUDITOR), acknowledge/dismiss (ADMIN)")
@RequiredArgsConstructor
@Slf4j
class AdminBehaviorAnomalyController {

    private static final int MAX_PAGE_SIZE = 200;

    private final BehaviorAnomalyAdminService anomalyAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "List detected behavioural anomalies for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Page of anomalies ordered by detectedAt DESC")
    @ApiResponse(responseCode = "400", description = "Page size exceeds the maximum (200)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN or AUDITOR")
    AnomalyPageResponse list(
            @Parameter(description = "Filter by user") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Filter by datasource") @RequestParam(required = false) UUID datasourceId,
            @Parameter(description = "Filter by feature (e.g. query_count, active_hours)")
            @RequestParam(required = false) String feature,
            @Parameter(description = "Filter by status (OPEN/ACKNOWLEDGED/DISMISSED)")
            @RequestParam(required = false) BehaviorAnomalyStatus status,
            @Parameter(description = "Inclusive lower bound on detectedAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on detectedAt")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @PageableDefault(size = 20, sort = "detectedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        var filter = new AnomalyListFilter(userId, datasourceId, feature, status, from, to);
        PageResponse<BehaviorAnomalyView> page = anomalyAdminService.list(organizationId, filter,
                SpringPageableAdapter.toPageRequest(pageable));
        return AnomalyPageResponse.from(page.map(AnomalyResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    @Operation(summary = "Get a single anomaly by id")
    @ApiResponse(responseCode = "200", description = "The anomaly")
    @ApiResponse(responseCode = "404", description = "Not found in this organization")
    AnomalyResponse get(@PathVariable UUID id,
                        @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return AnomalyResponse.from(anomalyAdminService.get(organizationId, id));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Acknowledge an OPEN anomaly")
    @ApiResponse(responseCode = "200", description = "Acknowledged")
    @ApiResponse(responseCode = "404", description = "Not found in this organization")
    @ApiResponse(responseCode = "409", description = "Anomaly is not OPEN")
    AnomalyResponse acknowledge(@PathVariable UUID id,
                                @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
                                @AuthenticationPrincipal(expression = "userId") UUID actorUserId,
                                RequestAuditContext auditContext) {
        var view = anomalyAdminService.acknowledge(organizationId, id, actorUserId);
        recordAudit(AuditAction.ANOMALY_ACKNOWLEDGED, id, organizationId, actorUserId, auditContext, view);
        return AnomalyResponse.from(view);
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Dismiss an anomaly (false positive / accepted)")
    @ApiResponse(responseCode = "200", description = "Dismissed")
    @ApiResponse(responseCode = "404", description = "Not found in this organization")
    @ApiResponse(responseCode = "409", description = "Anomaly is already dismissed")
    AnomalyResponse dismiss(@PathVariable UUID id,
                            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
                            @AuthenticationPrincipal(expression = "userId") UUID actorUserId,
                            RequestAuditContext auditContext) {
        var view = anomalyAdminService.dismiss(organizationId, id, actorUserId);
        recordAudit(AuditAction.ANOMALY_DISMISSED, id, organizationId, actorUserId, auditContext, view);
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
                            "datasource_id", view.datasourceId().toString()),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on anomaly {}", action, anomalyId, ex);
        }
    }
}
