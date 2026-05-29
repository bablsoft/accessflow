package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record QueryTemplatePageResponse(
        List<QueryTemplateResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static QueryTemplatePageResponse from(PageResponse<QueryTemplateResponse> page) {
        return new QueryTemplatePageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
