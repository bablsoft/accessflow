package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentVersionView;

import java.util.List;

public record DeploymentEnvironmentVersionPageResponse(
        List<DeploymentEnvironmentVersionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentEnvironmentVersionPageResponse from(
            PageResponse<DeploymentEnvironmentVersionView> page) {
        return new DeploymentEnvironmentVersionPageResponse(
                page.content().stream().map(DeploymentEnvironmentVersionResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
