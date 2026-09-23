package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingView;

import java.util.List;

public record SchemaDriftFindingPageResponse(
        List<SchemaDriftFindingResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static SchemaDriftFindingPageResponse from(PageResponse<SchemaDriftFindingView> page) {
        return new SchemaDriftFindingPageResponse(
                page.content().stream().map(SchemaDriftFindingResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
