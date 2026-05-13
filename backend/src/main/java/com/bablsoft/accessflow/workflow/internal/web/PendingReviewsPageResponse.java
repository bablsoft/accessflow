package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record PendingReviewsPageResponse(
        List<PendingReviewItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PendingReviewsPageResponse from(PageResponse<PendingReviewItem> page) {
        return new PendingReviewsPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
