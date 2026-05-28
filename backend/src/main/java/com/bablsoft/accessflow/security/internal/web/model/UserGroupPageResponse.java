package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record UserGroupPageResponse(
        List<UserGroupResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static UserGroupPageResponse from(PageResponse<UserGroupResponse> page) {
        return new UserGroupPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages()
        );
    }
}
