package com.partqam.accessflow.audit.internal.web;

import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditLogQuery;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Audit Log", description = "Read access to the audit log (ADMIN only)")
@RequiredArgsConstructor
class AdminAuditLogController {

    private static final int MAX_PAGE_SIZE = 500;

    private final AuditLogService auditLogService;

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
            Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            throw new BadAuditQueryException("Page size cannot exceed " + MAX_PAGE_SIZE);
        }
        var resourceTypeEnum = parseResourceType(resourceType);
        var filter = new AuditLogQuery(actorId, action, resourceTypeEnum, resourceId, from, to);
        var page = auditLogService.query(organizationId, filter, pageable)
                .map(AuditLogResponse::from);
        return AuditLogPageResponse.from(page);
    }

    @ExceptionHandler(BadAuditQueryException.class)
    ResponseEntity<ProblemDetail> handleBadQuery(BadAuditQueryException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setProperty("error", "BAD_AUDIT_QUERY");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
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
