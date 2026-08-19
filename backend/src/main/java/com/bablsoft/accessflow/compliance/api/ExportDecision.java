package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;

import java.util.List;
import java.util.UUID;

/**
 * The effective result-export decision for one exporter on one query's persisted result (#626).
 * Computed by combining the exporter's applicable export policies most-restrictive-wins
 * ({@code ALLOW < WATERMARK < ROW_CAP < DENY_CLASSIFIED}) with the classifications actually
 * present in the result. {@code policyIds} are the policies that participated in the decision —
 * a {@code DENY_CLASSIFIED} policy whose classifications were absent from the result is excluded.
 *
 * @param allowed                 false when the effective mode is {@code DENY_CLASSIFIED}
 * @param effectiveMode           the winning mode; {@code ALLOW} when no policy applies
 * @param rowCap                  minimum row cap across applicable {@code ROW_CAP} policies;
 *                                null unless the effective mode is {@code ROW_CAP}
 * @param watermark               true for {@code WATERMARK} and {@code ROW_CAP} — a capped file
 *                                must carry its cap provenance
 * @param classificationsPresent  distinct classifications matching the result's columns, sorted
 *                                by enum order (computed even when no policy applies — it also
 *                                drives the sensitive-export notification)
 */
public record ExportDecision(
        boolean allowed,
        ExportPolicyMode effectiveMode,
        Integer rowCap,
        boolean watermark,
        List<UUID> policyIds,
        List<DataClassification> classificationsPresent) {

    public ExportDecision {
        policyIds = policyIds == null ? List.of() : List.copyOf(policyIds);
        classificationsPresent = classificationsPresent == null ? List.of()
                : List.copyOf(classificationsPresent);
    }
}
