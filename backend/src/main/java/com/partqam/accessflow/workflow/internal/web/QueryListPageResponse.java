package com.partqam.accessflow.workflow.internal.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** Standard paginated envelope for {@code GET /queries}. */
public record QueryListPageResponse(
        List<QueryListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static QueryListPageResponse from(Page<QueryListItem> page) {
        return new QueryListPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
