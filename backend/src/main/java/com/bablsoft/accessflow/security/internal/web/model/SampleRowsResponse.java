package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.util.List;

/**
 * Response body for {@code GET /datasources/{id}/sample-rows} (issue AF-443). The {@code rows} are
 * already row-level-security filtered and column-masked by the executor — masked columns carry the
 * masked value (e.g. {@code "***"}), never the raw one. {@code restricted} flags which columns were
 * masked so the UI can badge them.
 */
public record SampleRowsResponse(
        List<Column> columns,
        List<List<Object>> rows,
        long rowCount,
        boolean truncated,
        long durationMs) {

    public record Column(String name, String type, boolean restricted) {
    }

    public static SampleRowsResponse from(SelectExecutionResult result) {
        var columns = result.columns().stream()
                .map(c -> new Column(c.name(), c.typeName(), c.restricted()))
                .toList();
        return new SampleRowsResponse(columns, result.rows(), result.rowCount(),
                result.truncated(), result.duration().toMillis());
    }
}
