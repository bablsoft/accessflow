package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessRow;

import java.util.List;

/** A page of the reverse index (AF-859). */
record EffectiveAccessPageResponse(List<EffectiveAccessRowResponse> content, int page, int size,
                                   long totalElements, int totalPages) {

    static EffectiveAccessPageResponse from(PageResponse<EffectiveAccessRow> page) {
        return new EffectiveAccessPageResponse(
                page.content().stream().map(EffectiveAccessRowResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}
