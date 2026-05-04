package com.partqam.accessflow.core.api;

import java.util.UUID;

public final class UserNotFoundException extends UserAdminException {

    public UserNotFoundException(UUID id) {
        super("User not found: " + id);
    }
}
