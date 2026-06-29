package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.proxy.api.QueryDryRunService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Computes the dry-run impact of a retention policy without executing it. The eligible-row estimate
 * is obtained from the proxy's non-committing {@link QueryDryRunService} over a governed
 * {@code SELECT … WHERE <timestamp_column> < <cutoff>} — best-effort, so a failure or an engine with
 * no plan concept yields a null estimate while the structural impact is always returned.
 */
@Component
@RequiredArgsConstructor
class LifecyclePreviewCalculator {

    private static final Logger log = LoggerFactory.getLogger(LifecyclePreviewCalculator.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QueryDryRunService queryDryRunService;
    private final Clock clock;

    LifecyclePreviewResult preview(RetentionPolicyEntity policy) {
        long estimated = estimateEligibleRows(policy);
        String method = methodLabel(policy.getAction(), policy.getTransformType(),
                policy.getSoftDeleteColumn());
        List<String> columns = policy.getTargetColumns() == null
                ? List.of() : List.of(policy.getTargetColumns());
        var impact = new LifecyclePreviewResult.TableImpact(
                policy.getTargetTable(), columns, estimated, method);
        long total = estimated < 0 ? 0 : estimated;
        return new LifecyclePreviewResult(policy.getAction(), policy.getTransformType(), total,
                List.of(impact));
    }

    /** @return the eligible-row estimate, or {@code -1} when it could not be computed. */
    private long estimateEligibleRows(RetentionPolicyEntity policy) {
        if (policy.getTargetTable() == null || policy.getTargetTable().isBlank()) {
            return -1;
        }
        try {
            ZonedDateTime cutoff = RetentionWindow.parse(policy.getRetentionWindow())
                    .cutoffFrom(ZonedDateTime.now(clock));
            String sql = "SELECT * FROM " + policy.getTargetTable()
                    + " WHERE " + policy.getTimestampColumn()
                    + " < TIMESTAMP '" + TS.format(cutoff) + "'";
            // Admin-context dry-run: the policy is an org-admin artefact; no per-user row security.
            QueryDryRunResult result = queryDryRunService.dryRun(
                    policy.getDatasourceId(), sql, policy.getCreatedBy(),
                    policy.getOrganizationId(), true);
            Long rows = result == null ? null : result.estimatedRows();
            return rows == null ? -1 : rows;
        } catch (RuntimeException ex) {
            log.warn("Retention preview row estimate failed for policy {}: {}",
                    policy.getId(), ex.getMessage());
            return -1;
        }
    }

    static String methodLabel(LifecycleAction action, LifecycleTransform transform,
                              String softDeleteColumn) {
        return switch (action) {
            case HARD_DELETE -> "HARD_DELETE";
            case SOFT_DELETE -> "SOFT_DELETE("
                    + (softDeleteColumn == null ? "deleted_at" : softDeleteColumn) + ")";
            case PSEUDONYMIZE -> "PSEUDONYMIZE(" + (transform == null ? "" : transform.name()) + ")";
        };
    }
}
