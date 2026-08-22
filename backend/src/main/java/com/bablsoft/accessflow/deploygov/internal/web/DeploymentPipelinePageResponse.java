package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineView;

import java.util.List;

public record DeploymentPipelinePageResponse(
        List<DeploymentPipelineResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentPipelinePageResponse from(PageResponse<DeploymentPipelineView> page) {
        return new DeploymentPipelinePageResponse(
                page.content().stream().map(DeploymentPipelineResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
