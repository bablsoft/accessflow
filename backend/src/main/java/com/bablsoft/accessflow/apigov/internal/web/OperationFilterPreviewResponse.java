package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.OperationFilterPreview;

import java.util.List;

/** Dry-run result: total parsed, kept after the filter, and the operations it would drop. */
public record OperationFilterPreviewResponse(
        int totalCount,
        int keptCount,
        List<ApiOperationResponse> excluded) {

    static OperationFilterPreviewResponse from(OperationFilterPreview p) {
        return new OperationFilterPreviewResponse(p.totalCount(), p.keptCount(),
                p.excluded().stream().map(ApiOperationResponse::from).toList());
    }
}
