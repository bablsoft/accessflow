package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;

import java.util.List;

record HelpChatSessionPageResponse(
        List<HelpChatSessionResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static HelpChatSessionPageResponse from(PageResponse<HelpChatSessionResponse> page) {
        return new HelpChatSessionPageResponse(page.content(), page.page(), page.size(),
                page.totalElements(), page.totalPages());
    }
}
