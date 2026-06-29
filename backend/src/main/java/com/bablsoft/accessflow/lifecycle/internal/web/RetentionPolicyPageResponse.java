package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;

import java.util.List;

/** Paginated API response for retention policies. */
public record RetentionPolicyPageResponse(
        List<RetentionPolicyResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static RetentionPolicyPageResponse from(PageResponse<RetentionPolicyView> page) {
        return new RetentionPolicyPageResponse(
                page.content().stream().map(RetentionPolicyResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
