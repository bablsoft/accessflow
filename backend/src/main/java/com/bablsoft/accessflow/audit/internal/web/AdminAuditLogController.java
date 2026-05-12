package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditLogVerificationResult;
import com.bablsoft.accessflow.audit.api.AuditLogView;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Audit Log", description = "Read access to the audit log (ADMIN only)")
@RequiredArgsConstructor
class AdminAuditLogController {

    private static final int MAX_PAGE_SIZE = 500;
    private static final Set<String> ALLOWED_SORT_PROPERTIES =
            Set.of("createdAt", "action", "resourceType");

    private final AuditLogService auditLogService;
    private final UserAdminService userAdminService;

    @GetMapping
    @Operation(summary = "Query audit-log rows for the caller's organization with optional filters")
    @ApiResponse(responseCode = "200", description = "Page of audit-log rows ordered by createdAt DESC")
    @ApiResponse(responseCode = "400", description = "Page size exceeds the maximum (500) or invalid filter")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AuditLogPageResponse list(
            @Parameter(description = "Filter by user who performed the action")
            @RequestParam(required = false) UUID actorId,
            @Parameter(description = "Filter by action type (e.g. QUERY_SUBMITTED)")
            @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Filter by resource type (snake_case form, e.g. query_request)")
            @RequestParam(required = false) String resourceType,
            @Parameter(description = "Filter by specific resource id")
            @RequestParam(required = false) UUID resourceId,
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadAuditQueryException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        validateSort(pageable.getSort());
        var resourceTypeEnum = parseResourceType(resourceType);
        var filter = new AuditLogQuery(actorId, action, resourceTypeEnum, resourceId, from, to);
        Page<AuditLogView> page = auditLogService.query(organizationId, filter, pageable);
        Map<UUID, UserView> actors = lookupActors(organizationId, page);
        return AuditLogPageResponse.from(page.map(view -> {
            UserView actor = view.actorId() == null ? null : actors.get(view.actorId());
            return AuditLogResponse.from(
                    view,
                    actor == null ? null : actor.email(),
                    actor == null ? null : actor.displayName());
        }));
    }

    @GetMapping("/verify")
    @Operation(summary = "Verify the audit-log hash chain for the caller's organization")
    @ApiResponse(responseCode = "200", description = "Verification outcome (ok or first bad row)")
    @ApiResponse(responseCode = "400", description = "Invalid time window ('from' after 'to')")
    @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN")
    AuditLogVerificationResult verify(
            @Parameter(description = "Inclusive lower bound on createdAt")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound on createdAt")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadAuditQueryException("'from' must be on or before 'to'");
        }
        return auditLogService.verify(organizationId, from, to);
    }

    private Map<UUID, UserView> lookupActors(UUID organizationId, Page<AuditLogView> page) {
        Set<UUID> actorIds = new HashSet<>();
        for (AuditLogView view : page.getContent()) {
            if (view.actorId() != null) {
                actorIds.add(view.actorId());
            }
        }
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return userAdminService.findByIds(organizationId, actorIds);
    }

    @ExceptionHandler(BadAuditQueryException.class)
    ResponseEntity<ProblemDetail> handleBadQuery(BadAuditQueryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setProperty("error", "BAD_AUDIT_QUERY");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    private static void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new BadAuditQueryException("Invalid sort property: " + order.getProperty());
            }
        }
    }

    private static AuditResourceType parseResourceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditResourceType.fromDbValue(value);
        } catch (IllegalArgumentException ex) {
            throw new BadAuditQueryException("Unknown resource_type: " + value);
        }
    }

    static final class BadAuditQueryException extends RuntimeException {
        BadAuditQueryException(String message) {
            super(message);
        }
    }
}
