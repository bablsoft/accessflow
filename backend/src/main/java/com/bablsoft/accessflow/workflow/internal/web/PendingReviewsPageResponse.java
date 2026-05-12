package com.bablsoft.accessflow.workflow.internal.web;

import org.springframework.data.domain.Page;

import java.util.List;

public record PendingReviewsPageResponse(
        List<PendingReviewItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PendingReviewsPageResponse from(Page<PendingReviewItem> page) {
        return new PendingReviewsPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
