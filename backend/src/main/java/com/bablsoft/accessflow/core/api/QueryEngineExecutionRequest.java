package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything a {@link QueryEngine} needs to execute one approved query: the engine-neutral
 * {@link QueryExecutionRequest} (query text, type, restricted columns, masks, row-security
 * directives), the target datasource's connection descriptor, and the host-computed effective
 * limits — the row cap (request override clamped to datasource and global maxima) and the
 * statement timeout. The host owns limit policy; engines only enforce it.
 */
public record QueryEngineExecutionRequest(QueryExecutionRequest request,
                                          DatasourceConnectionDescriptor descriptor,
                                          int effectiveMaxRows,
                                          Duration effectiveTimeout) {

    public QueryEngineExecutionRequest {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(effectiveTimeout, "effectiveTimeout must not be null");
        if (effectiveMaxRows < 1) {
            throw new IllegalArgumentException("effectiveMaxRows must be positive");
        }
    }
}
