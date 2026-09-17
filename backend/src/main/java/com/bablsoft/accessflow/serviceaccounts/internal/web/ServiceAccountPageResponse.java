package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminView;

import java.util.List;

public record ServiceAccountPageResponse(
        List<ServiceAccountResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ServiceAccountPageResponse from(PageResponse<ServiceAccountAdminView> page) {
        return new ServiceAccountPageResponse(
                page.content().stream().map(ServiceAccountResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
