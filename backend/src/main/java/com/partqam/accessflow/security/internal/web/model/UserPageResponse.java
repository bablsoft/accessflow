package com.partqam.accessflow.security.internal.web.model;

import org.springframework.data.domain.Page;

import java.util.List;

public record UserPageResponse(
        List<AdminUserResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static UserPageResponse from(Page<AdminUserResponse> page) {
        return new UserPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
