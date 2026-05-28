package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserGroupView;

import java.time.Instant;
import java.util.UUID;

public record UserGroupResponse(
        UUID id,
        UUID organizationId,
        String name,
        String description,
        long memberCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserGroupResponse from(UserGroupView view) {
        return new UserGroupResponse(
                view.id(),
                view.organizationId(),
                view.name(),
                view.description(),
                view.memberCount(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
