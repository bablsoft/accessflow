package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record ApiRequestPageResponse(
        List<ApiRequestResponse> content, int page, int size, long totalElements, int totalPages) {

    static ApiRequestPageResponse from(PageResponse<ApiRequestView> page) {
        return new ApiRequestPageResponse(
                page.content().stream().map(ApiRequestResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
