package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public class UserGroupMembershipNotFoundException extends RuntimeException {

    private final UUID groupId;
    private final UUID userId;

    public UserGroupMembershipNotFoundException(UUID groupId, UUID userId) {
        super("User " + userId + " is not a member of group " + groupId);
        this.groupId = groupId;
        this.userId = userId;
    }

    public UUID groupId() {
        return groupId;
    }

    public UUID userId() {
        return userId;
    }
}
