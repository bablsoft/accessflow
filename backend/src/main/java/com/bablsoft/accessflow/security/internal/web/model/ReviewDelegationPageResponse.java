package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewDelegationView;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Paginated envelope for the org-wide delegation listing (#622). */
public record ReviewDelegationPageResponse(
        @JsonProperty("content") List<ReviewDelegationResponse> content,
        @JsonProperty("page") int page,
        @JsonProperty("size") int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages) {

    public static ReviewDelegationPageResponse from(PageResponse<ReviewDelegationView> page) {
        return new ReviewDelegationPageResponse(
                page.content().stream().map(ReviewDelegationResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
