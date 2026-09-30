package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Thrown by the admin-users surface ({@link UserAdminService#updateHumanUser},
 * {@link UserAdminService#deactivateHumanUser}) when the target is a {@code SERVICE_ACCOUNT}
 * (#1130). Service accounts are managed at {@code /admin/service-accounts/{id}}, behind
 * {@code SERVICE_ACCOUNT_MANAGE} and the bootstrap-managed guard.
 */
public class UserIsServiceAccountException extends RuntimeException {

    private final UUID userId;

    public UserIsServiceAccountException(UUID userId) {
        super("User " + userId + " is a service account");
        this.userId = userId;
    }

    public UUID userId() {
        return userId;
    }
}
