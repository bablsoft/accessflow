package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One executed DDL/DELETE operation with its approvers, for the
 * {@link ComplianceReportType#REGULATORY_AUDIT_TRAIL} report (#459).
 */
public record RegulatoryAuditTrailRow(
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        UUID submittedBy,
        String submitterEmail,
        QueryType queryType,
        String sqlText,
        List<Approver> approvers,
        Instant executedAt) {

    public RegulatoryAuditTrailRow {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
    }
}
