package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ReviewService.DecisionOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.UUID;

/**
 * Centralises the audit-log write that accompanies every review decision so the single-row
 * and bulk endpoints stay consistent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ReviewDecisionAuditWriter {

    private final AuditLogService auditLogService;

    void record(AuditAction action, UUID queryId, JwtClaims caller, DecisionOutcome outcome,
                String comment, RequestAuditContext auditContext) {
        if (outcome.wasIdempotentReplay()) {
            return;
        }
        try {
            var metadata = new HashMap<String, Object>();
            if (comment != null && !comment.isBlank()) {
                metadata.put("comment", comment);
            }
            metadata.put("resulting_status", outcome.resultingStatus().name());
            auditLogService.record(new AuditEntry(
                    action,
                    AuditResourceType.QUERY_REQUEST,
                    queryId,
                    caller.organizationId(),
                    caller.userId(),
                    metadata,
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on query {}", action, queryId, ex);
        }
    }
}
