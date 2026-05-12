package com.bablsoft.accessflow.security.internal.web.model;

import org.springframework.data.domain.Page;

import java.util.List;

public record DatasourcePageResponse(
        List<DatasourceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static DatasourcePageResponse from(Page<DatasourceResponse> page) {
        return new DatasourcePageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
