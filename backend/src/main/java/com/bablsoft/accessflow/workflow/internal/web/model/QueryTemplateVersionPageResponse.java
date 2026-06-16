package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record QueryTemplateVersionPageResponse(
        List<QueryTemplateVersionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static QueryTemplateVersionPageResponse from(PageResponse<QueryTemplateVersionResponse> page) {
        return new QueryTemplateVersionPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
