package com.bablsoft.accessflow.compliance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generated compliance report (#459). Exactly one row list carries rows depending on
 * {@link #type}; the others are empty. {@code truncated}
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
        List<RetentionAdherenceReportRow> retentionAdherence,
        List<DeploymentMetricsReportRow> deploymentMetrics,
        boolean truncated) {

    public ComplianceReport {
        classifiedAccess = classifiedAccess == null ? List.of() : List.copyOf(classifiedAccess);
        auditTrail = auditTrail == null ? List.of() : List.copyOf(auditTrail);
        retentionAdherence = retentionAdherence == null ? List.of() : List.copyOf(retentionAdherence);
        deploymentMetrics = deploymentMetrics == null ? List.of() : List.copyOf(deploymentMetrics);
    }

    /** Backward-compatible constructor without the retention-adherence list (defaults to none). */
    public ComplianceReport(ComplianceReportType type, UUID organizationId, Instant periodFrom,
                            Instant periodTo, Instant generatedAt, UUID datasourceId,
                            List<ClassifiedAccessReportRow> classifiedAccess,
                            List<RegulatoryAuditTrailRow> auditTrail, boolean truncated) {
        this(type, organizationId, periodFrom, periodTo, generatedAt, datasourceId, classifiedAccess,
                auditTrail, List.of(), List.of(), truncated);
    }

    /** Backward-compatible constructor without the deployment-metrics list (defaults to none). */
    public ComplianceReport(ComplianceReportType type, UUID organizationId, Instant periodFrom,
                            Instant periodTo, Instant generatedAt, UUID datasourceId,
                            List<ClassifiedAccessReportRow> classifiedAccess,
                            List<RegulatoryAuditTrailRow> auditTrail,
                            List<RetentionAdherenceReportRow> retentionAdherence, boolean truncated) {
        this(type, organizationId, periodFrom, periodTo, generatedAt, datasourceId, classifiedAccess,
                auditTrail, retentionAdherence, List.of(), truncated);
    }

    /** Total number of rows in whichever list this report carries. */
    public int rowCount() {
        return classifiedAccess.size() + auditTrail.size() + retentionAdherence.size()
                + deploymentMetrics.size();
    }
}
