package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public class UserGroupNotFoundException extends RuntimeException {

    private final UUID groupId;

    public UserGroupNotFoundException(UUID groupId) {
        super("User group not found: " + groupId);
        this.groupId = groupId;
    }

    public UUID groupId() {
        return groupId;
    }
}
