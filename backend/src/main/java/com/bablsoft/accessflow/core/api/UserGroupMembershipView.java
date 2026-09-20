package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record UserGroupMembershipView(
        UUID userId,
        UUID groupId,
        String userEmail,
        String userDisplayName,
        UserGroupMembershipSourceType source,
        Instant joinedAt,
        PrincipalType principalType
) {
    /** Legacy shape without the principal type — defaults to a person (#875). */
    public UserGroupMembershipView(UUID userId, UUID groupId, String userEmail, String userDisplayName,
                                   UserGroupMembershipSourceType source, Instant joinedAt) {
        this(userId, groupId, userEmail, userDisplayName, source, joinedAt, PrincipalType.HUMAN);
    }
}
