package com.bablsoft.accessflow.discovery.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingView;

import java.util.List;

public record DiscoveryFindingPageResponse(
        List<DiscoveryFindingResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static DiscoveryFindingPageResponse from(PageResponse<DiscoveryFindingView> page) {
        return new DiscoveryFindingPageResponse(
                page.content().stream().map(DiscoveryFindingResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
