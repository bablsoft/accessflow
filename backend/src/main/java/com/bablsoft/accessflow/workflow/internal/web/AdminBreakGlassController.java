package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.workflow.api.BreakGlassAdminService;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventFilter;
import com.bablsoft.accessflow.workflow.api.BreakGlassEventView;
import com.bablsoft.accessflow.workflow.api.BreakGlassStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admin "Break-glass log" (AF-385). Reads the retro-review events (ADMIN/AUDITOR) and acknowledges
 * (reconciles) them (ADMIN-only). The submitter of a break-glass query cannot acknowledge their own
 * event — mirrors the no-self-approve invariant. Binding-only; logic lives in
 * {@link BreakGlassAdminService}.
 */
@RestController
@RequestMapping("/api/v1/admin/break-glass")
@Tag(name = "Admin Break-glass", description = "Break-glass log — read (ADMIN/AUDITOR), acknowledge (ADMIN)")
@RequiredArgsConstructor
@Slf4j
class AdminBreakGlassController {

    private static final int MAX_PAGE_SIZE = 200;

    private final BreakGlassAdminService breakGlassAdminService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_BREAK_GLASS_VIEW')")
    @Operation(summary = "List break-glass events for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Page of break-glass events, newest first")
    @ApiResponse(responseCode = "400", description = "Page size exceeds the maximum (200)")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN or AUDITOR")
    BreakGlassEventPageResponse list(
            @Parameter(description = "Filter by retro-review status (PENDING_REVIEW = unreconciled)")
            @RequestParam(required = false) BreakGlassStatus status,
            @Parameter(description = "Filter by datasource") @RequestParam(required = false) UUID datasourceId,
            @Parameter(description = "Filter by submitter") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        var filter = new BreakGlassEventFilter(status, datasourceId, userId, from, to);
        PageResponse<BreakGlassEventView> page = breakGlassAdminService.list(organizationId, filter,
                SpringPageableAdapter.toPageRequest(pageable));
        return BreakGlassEventPageResponse.from(page.map(BreakGlassEventResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_BREAK_GLASS_VIEW')")
    @Operation(summary = "Get a single break-glass event by id")
    @ApiResponse(responseCode = "200", description = "The break-glass event")
    @ApiResponse(responseCode = "404", description = "Not found in this organization")
    BreakGlassEventResponse get(@PathVariable UUID id,
                                @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        return BreakGlassEventResponse.from(breakGlassAdminService.get(organizationId, id));
    }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAuthority('PERM_BREAK_GLASS_REVIEW')")
    @Operation(summary = "Acknowledge (reconcile) a pending break-glass event")
    @ApiResponse(responseCode = "200", description = "Acknowledged")
    @ApiResponse(responseCode = "403", description = "Caller is the submitter (self-acknowledge not allowed)")
    @ApiResponse(responseCode = "404", description = "Not found in this organization")
    @ApiResponse(responseCode = "409", description = "Event is already reviewed")
    BreakGlassEventResponse acknowledge(@PathVariable UUID id,
                                        @Valid @RequestBody(required = false) AcknowledgeBreakGlassRequest body,
                                        @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
                                        @AuthenticationPrincipal(expression = "userId") UUID actorUserId,
                                        RequestAuditContext auditContext) {
        var comment = body == null ? null : body.comment();
        var view = breakGlassAdminService.acknowledge(organizationId, id, actorUserId, comment);
        recordAudit(id, organizationId, actorUserId, auditContext, view);
        return BreakGlassEventResponse.from(view);
    }

    private void recordAudit(UUID eventId, UUID organizationId, UUID actorUserId,
                             RequestAuditContext auditContext, BreakGlassEventView view) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.BREAK_GLASS_REVIEWED,
                    AuditResourceType.BREAK_GLASS_EVENT,
                    eventId,
                    organizationId,
                    actorUserId,
                    Map.of("query_request_id", view.queryRequestId().toString(),
                            "datasource_id", view.datasourceId().toString(),
                            "submitted_by", view.submittedByUserId().toString()),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for BREAK_GLASS_REVIEWED on event {}", eventId, ex);
        }
    }
}
