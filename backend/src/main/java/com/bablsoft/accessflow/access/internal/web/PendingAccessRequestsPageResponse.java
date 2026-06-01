package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessReviewService.PendingAccessRequest;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record PendingAccessRequestsPageResponse(
        List<PendingAccessRequestItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PendingAccessRequestsPageResponse from(PageResponse<PendingAccessRequest> page) {
        return new PendingAccessRequestsPageResponse(
                page.content().stream().map(PendingAccessRequestItem::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
