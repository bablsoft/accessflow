package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record DatasourcePageResponse(
        List<DatasourceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static DatasourcePageResponse from(PageResponse<DatasourceResponse> page) {
        return new DatasourcePageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
