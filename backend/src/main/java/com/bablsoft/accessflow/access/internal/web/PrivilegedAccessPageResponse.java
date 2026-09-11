package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.PrivilegedAccessRow;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record PrivilegedAccessPageResponse(
        List<PrivilegedAccessResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static PrivilegedAccessPageResponse from(PageResponse<PrivilegedAccessRow> page) {
        return new PrivilegedAccessPageResponse(
                page.content().stream().map(PrivilegedAccessResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
