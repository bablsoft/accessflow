package com.bablsoft.accessflow.lifecycle.api;

import java.util.List;

/**
 * Dry-run impact of a retention policy or erasure request: which tables would be touched, the
 * estimated affected-row count per table, and the method that would be applied — computed
 * <strong>without executing</strong>.
 */
public record LifecyclePreviewResult(
        LifecycleAction action,
        LifecycleTransform transformType,
        long totalEstimatedRows,
        List<TableImpact> tables) {

    public LifecyclePreviewResult {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /** Per-table dry-run impact. */
    public record TableImpact(String table, List<String> columns, long estimatedRows, String method) {
        public TableImpact {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }
}
