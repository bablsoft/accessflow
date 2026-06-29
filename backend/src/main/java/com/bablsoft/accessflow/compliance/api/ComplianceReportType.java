package com.bablsoft.accessflow.compliance.api;

/**
 * Pre-built compliance report kinds (#459). Both run over a time period against the immutable
 * {@code query_snapshots} forensic record.
 *
 * <ul>
 *   <li>{@link #CLASSIFIED_ACCESS} — executed queries that touched objects tagged PII/PCI/PHI/GDPR/
 *       FINANCIAL/SENSITIVE, joined to the data-classification tags.</li>
 *   <li>{@link #REGULATORY_AUDIT_TRAIL} — executed DDL/DELETE operations with the names of the
 *       reviewers who approved them.</li>
 * </ul>
 */
public enum ComplianceReportType {
    CLASSIFIED_ACCESS,
    REGULATORY_AUDIT_TRAIL,
    /**
     * Retention-adherence / deletion-history: lifecycle runs (retention + erasure actions) over the
     * period, for proof of data retirement (AF-499).
     */
    RETENTION_ADHERENCE
}
