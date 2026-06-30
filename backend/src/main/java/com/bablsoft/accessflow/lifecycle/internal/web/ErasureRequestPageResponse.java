package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.ErasureRequestView;

import java.util.List;

/** Paginated API response for erasure requests. */
public record ErasureRequestPageResponse(
        List<ErasureRequestResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static ErasureRequestPageResponse from(PageResponse<ErasureRequestView> page) {
        return new ErasureRequestPageResponse(
                page.content().stream().map(ErasureRequestResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
