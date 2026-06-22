package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record AnomalyPageResponse(
        List<AnomalyResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AnomalyPageResponse from(PageResponse<AnomalyResponse> page) {
        return new AnomalyPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
