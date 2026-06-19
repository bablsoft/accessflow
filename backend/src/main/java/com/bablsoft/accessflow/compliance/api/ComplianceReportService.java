package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * Produces pre-built compliance reports (#459) over the immutable {@code query_snapshots} forensic
 * record, joined to data-classification tags for the {@link ComplianceReportType#CLASSIFIED_ACCESS}
 * report and to the snapshotted approval decisions for
 * {@link ComplianceReportType#REGULATORY_AUDIT_TRAIL}.
 */
public interface ComplianceReportService {

    /**
     * Generates the report described by {@code request} for the given organization.
     *
     * @throws InvalidReportPeriodException when the period is missing, inverted, or exceeds the
     *                                       configured maximum window.
     */
    ComplianceReport generate(UUID organizationId, ComplianceReportRequest request);
}
