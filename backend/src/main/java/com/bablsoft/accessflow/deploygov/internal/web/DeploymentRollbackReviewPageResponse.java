package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView;

import java.util.List;

public record DeploymentRollbackReviewPageResponse(
        List<DeploymentRollbackReviewResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentRollbackReviewPageResponse from(
            PageResponse<DeploymentRollbackReviewView> page) {
        return new DeploymentRollbackReviewPageResponse(
                page.content().stream().map(DeploymentRollbackReviewResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
