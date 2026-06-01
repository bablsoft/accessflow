package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessRequestView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record AccessRequestPageResponse(
        List<AccessRequestResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AccessRequestPageResponse from(PageResponse<AccessRequestView> page) {
        return new AccessRequestPageResponse(
                page.content().stream().map(AccessRequestResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
