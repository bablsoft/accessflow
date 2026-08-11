package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

/** Standard paginated envelope for {@code GET /queries/{id}/occurrences} (#627). */
public record QueryOccurrencePageResponse(
        List<QueryOccurrenceItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static QueryOccurrencePageResponse from(PageResponse<QueryOccurrenceItem> page) {
        return new QueryOccurrencePageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
