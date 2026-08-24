package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestView;

import java.util.List;

public record DeploymentRequestPageResponse(
        List<DeploymentRequestResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentRequestPageResponse from(PageResponse<DeploymentRequestView> page) {
        return new DeploymentRequestPageResponse(
                page.content().stream().map(DeploymentRequestResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
