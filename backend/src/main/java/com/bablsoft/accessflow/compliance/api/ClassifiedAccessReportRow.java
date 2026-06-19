package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.QueryType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One executed query that touched at least one classified object, for the
 * {@link ComplianceReportType#CLASSIFIED_ACCESS} report (#459).
 */
public record ClassifiedAccessReportRow(
        UUID queryRequestId,
        UUID datasourceId,
        String datasourceName,
        UUID submittedBy,
        String submitterEmail,
        QueryType queryType,
        List<String> referencedTables,
        List<MatchedClassification> matched,
        Long rowsAffected,
        Instant executedAt) {

    public ClassifiedAccessReportRow {
        referencedTables = referencedTables == null ? List.of() : List.copyOf(referencedTables);
        matched = matched == null ? List.of() : List.copyOf(matched);
    }
}
