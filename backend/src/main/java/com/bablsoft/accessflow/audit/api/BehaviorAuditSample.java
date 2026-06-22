package com.bablsoft.accessflow.audit.api;

import java.time.Instant;
import java.util.List;

/**
 * One query event projected from {@code audit_log} for behavioural feature extraction (AF-383).
 * Derived from audit <strong>metadata</strong> only — never query result data. {@code success} is
 * true for {@code QUERY_EXECUTED}, false for {@code QUERY_FAILED}. {@code queryType} /
 * {@code referencedTables} / {@code rowsReturned} come from the enriched QUERY_EXECUTED metadata and
 * may be null / empty for rows written before that enrichment (handled fail-soft by the extractor).
 */
public record BehaviorAuditSample(
        Instant occurredAt,
        boolean success,
        String queryType,
        List<String> referencedTables,
        Long rowsReturned) {

    public BehaviorAuditSample {
        referencedTables = referencedTables == null ? List.of() : List.copyOf(referencedTables);
    }
}
