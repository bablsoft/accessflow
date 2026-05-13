package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.security.api.UserInvitationView;

import java.util.List;

public record UserInvitationPageResponse(
        List<UserInvitationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static UserInvitationPageResponse from(PageResponse<UserInvitationView> page) {
        return new UserInvitationPageResponse(
                page.content().stream().map(UserInvitationResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }
}
