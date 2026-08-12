package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.time.Instant;
import java.util.List;

public record OverProvisionedAccessPageResponse(
        List<OverProvisionedAccessResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static OverProvisionedAccessPageResponse from(PageResponse<GrantUsageView> page,
                                                         Instant now) {
        return new OverProvisionedAccessPageResponse(
                page.content().stream().map(view -> OverProvisionedAccessResponse.from(view, now))
                        .toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
