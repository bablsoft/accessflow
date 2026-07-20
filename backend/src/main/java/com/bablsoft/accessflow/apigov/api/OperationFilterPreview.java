package com.bablsoft.accessflow.apigov.api;

import java.util.List;

/**
 * Result of a dry-run of an {@link OperationFilter} against a parsed schema: how many operations the
 * document defines, how many survive the filter, and the operations it would drop — computed without
 * persisting anything.
 */
public record OperationFilterPreview(
        int totalCount,
        int keptCount,
        List<ApiOperation> excluded) {

    public OperationFilterPreview {
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
    }
}
