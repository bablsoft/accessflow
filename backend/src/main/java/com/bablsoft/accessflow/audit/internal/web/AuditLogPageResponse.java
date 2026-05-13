package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AuditLogPageResponse from(PageResponse<AuditLogResponse> page) {
        return new AuditLogPageResponse(
                page.content(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
