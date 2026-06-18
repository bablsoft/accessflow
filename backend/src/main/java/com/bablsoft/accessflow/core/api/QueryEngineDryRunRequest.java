package com.bablsoft.accessflow.core.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything a {@link QueryEngine} needs to dry-run one query (issue AF-445): the engine-neutral
 * {@link QueryExecutionRequest} (query text, type, row-security directives) and the target
 * datasource's connection descriptor, plus the host-computed effective statement timeout. There is
 * no row cap — a dry-run only plans, it never returns rows. Mirrors {@link
 * QueryEngineExecutionRequest}; the engine must apply the request's row-security directives so the
 * plan reflects the governed query, and must never mutate data.
 */
public record QueryEngineDryRunRequest(QueryExecutionRequest request,
                                       DatasourceConnectionDescriptor descriptor,
                                       Duration effectiveTimeout) {

    public QueryEngineDryRunRequest {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(effectiveTimeout, "effectiveTimeout must not be null");
    }
}
