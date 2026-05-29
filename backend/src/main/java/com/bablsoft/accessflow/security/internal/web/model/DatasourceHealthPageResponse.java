package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record DatasourceHealthPageResponse(
        List<DatasourceHealthResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static DatasourceHealthPageResponse from(PageResponse<DatasourceHealthResponse> page) {
        return new DatasourceHealthPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages()
        );
    }
}
