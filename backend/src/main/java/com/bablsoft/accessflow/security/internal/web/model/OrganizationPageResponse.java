package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record OrganizationPageResponse(
        List<OrganizationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static OrganizationPageResponse from(PageResponse<OrganizationResponse> page) {
        return new OrganizationPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
