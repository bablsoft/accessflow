package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;

import java.util.List;

public record DeploymentFreezeWindowPageResponse(
        List<DeploymentFreezeWindowResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentFreezeWindowPageResponse from(PageResponse<DeploymentFreezeWindowView> page) {
        return new DeploymentFreezeWindowPageResponse(
                page.content().stream().map(DeploymentFreezeWindowResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
