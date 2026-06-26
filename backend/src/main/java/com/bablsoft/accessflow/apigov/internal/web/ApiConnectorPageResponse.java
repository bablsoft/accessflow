package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiConnectorView;
import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

public record ApiConnectorPageResponse(
        List<ApiConnectorResponse> content, int page, int size, long totalElements, int totalPages) {

    static ApiConnectorPageResponse from(PageResponse<ApiConnectorView> page) {
        return new ApiConnectorPageResponse(
                page.content().stream().map(ApiConnectorResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
