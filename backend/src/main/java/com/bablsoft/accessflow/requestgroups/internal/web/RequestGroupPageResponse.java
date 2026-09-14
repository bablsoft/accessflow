package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;

import java.util.List;
import java.util.function.Function;

record RequestGroupPageResponse(List<RequestGroupResponse> content, int page, int size,
                                long totalElements, int totalPages) {

    static RequestGroupPageResponse from(PageResponse<RequestGroupView> page,
                                         Function<SqlReviewFinding, String> renderFinding) {
        return new RequestGroupPageResponse(
                page.content().stream().map(group -> RequestGroupResponse.from(group, renderFinding))
                        .toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
