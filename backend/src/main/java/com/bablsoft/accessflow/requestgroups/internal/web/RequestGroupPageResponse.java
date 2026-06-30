package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;

import java.util.List;

record RequestGroupPageResponse(List<RequestGroupResponse> content, int page, int size,
                                long totalElements, int totalPages) {

    static RequestGroupPageResponse from(PageResponse<RequestGroupView> page) {
        return new RequestGroupPageResponse(
                page.content().stream().map(RequestGroupResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
