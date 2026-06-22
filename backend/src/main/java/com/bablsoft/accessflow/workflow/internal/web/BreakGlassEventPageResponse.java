package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record BreakGlassEventPageResponse(
        List<BreakGlassEventResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static BreakGlassEventPageResponse from(PageResponse<BreakGlassEventResponse> page) {
        return new BreakGlassEventPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
