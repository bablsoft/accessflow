package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generated compliance report (#459). Exactly one of {@code classifiedAccess} /
 * {@code auditTrail} carries rows depending on {@link #type}; the other is empty. {@code truncated}
 * is true when the underlying snapshot scan hit the configured row cap.
 */
public record ComplianceReport(
        ComplianceReportType type,
        UUID organizationId,
        Instant periodFrom,
        Instant periodTo,
        Instant generatedAt,
        UUID datasourceId,
        List<ClassifiedAccessReportRow> classifiedAccess,
        List<RegulatoryAuditTrailRow> auditTrail,
        boolean truncated) {

    public ComplianceReport {
        classifiedAccess = classifiedAccess == null ? List.of() : List.copyOf(classifiedAccess);
        auditTrail = auditTrail == null ? List.of() : List.copyOf(auditTrail);
    }

    /** Total number of rows in whichever list this report carries. */
    public int rowCount() {
        return classifiedAccess.size() + auditTrail.size();
    }
}
