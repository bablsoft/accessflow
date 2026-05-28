package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupMembershipView;

import java.time.Instant;
import java.util.UUID;

public record UserGroupMemberResponse(
        UUID userId,
        UUID groupId,
        String email,
        String displayName,
        UserGroupMembershipSourceType source,
        Instant joinedAt
) {
    public static UserGroupMemberResponse from(UserGroupMembershipView view) {
        return new UserGroupMemberResponse(
                view.userId(),
                view.groupId(),
                view.userEmail(),
                view.userDisplayName(),
                view.source(),
                view.joinedAt()
        );
    }
}
