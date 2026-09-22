package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;

import java.util.List;
import java.util.function.Function;

public record SchemaChangeSetPageResponse(
        List<SchemaChangeSetResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static SchemaChangeSetPageResponse from(PageResponse<SchemaChangeSetView> page,
                                            Function<SchemaChangeStatementFinding, String> render) {
        return new SchemaChangeSetPageResponse(
                page.content().stream().map(v -> SchemaChangeSetResponse.from(v, render)).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
