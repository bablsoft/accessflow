package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

/** Standard paginated envelope for {@code GET /queries}. */
public record QueryListPageResponse(
        List<QueryListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static QueryListPageResponse from(PageResponse<QueryListItem> page) {
        return new QueryListPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.page() + 1 >= page.totalPages());
    }
}
