package com.bablsoft.accessflow.deploygov.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentVersionHistoryEntryView;

import java.util.List;

public record DeploymentVersionHistoryPageResponse(
        List<DeploymentVersionHistoryEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static DeploymentVersionHistoryPageResponse from(
            PageResponse<DeploymentVersionHistoryEntryView> page) {
        return new DeploymentVersionHistoryPageResponse(
                page.content().stream().map(DeploymentVersionHistoryEntryResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
