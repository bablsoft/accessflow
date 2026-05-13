package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record UserPageResponse(
        List<AdminUserResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static UserPageResponse from(PageResponse<AdminUserResponse> page) {
        return new UserPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages()
        );
    }
}
