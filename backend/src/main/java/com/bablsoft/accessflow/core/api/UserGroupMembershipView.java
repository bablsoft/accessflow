package com.bablsoft.accessflow.core.api;

import java.time.Instant;
import java.util.UUID;

public record UserGroupMembershipView(
        UUID userId,
        UUID groupId,
        String userEmail,
        String userDisplayName,
        UserGroupMembershipSourceType source,
        Instant joinedAt
) {}
