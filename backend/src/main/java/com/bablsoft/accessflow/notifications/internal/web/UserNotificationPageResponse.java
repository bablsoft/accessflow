package com.bablsoft.accessflow.notifications.internal.web;

import org.springframework.data.domain.Page;

import java.util.List;

/** Paginated envelope for {@code GET /api/v1/notifications}. */
public record UserNotificationPageResponse(
        List<UserNotificationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {

    public static UserNotificationPageResponse from(Page<UserNotificationResponse> page) {
        return new UserNotificationPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
