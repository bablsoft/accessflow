package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;

import java.util.List;

/** Dry-run impact response for a retention policy or erasure request. */
public record LifecyclePreviewResponse(
        LifecycleAction action,
        LifecycleTransform transformType,
        long totalEstimatedRows,
        List<TableImpactResponse> tables) {

    record TableImpactResponse(String table, List<String> columns, long estimatedRows, String method) {
    }

    static LifecyclePreviewResponse from(LifecyclePreviewResult r) {
        return new LifecyclePreviewResponse(r.action(), r.transformType(), r.totalEstimatedRows(),
                r.tables().stream()
                        .map(t -> new TableImpactResponse(t.table(), t.columns(), t.estimatedRows(),
                                t.method()))
                        .toList());
    }
}
