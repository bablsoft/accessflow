package com.bablsoft.accessflow.core.api;

import java.util.Map;
import java.util.UUID;

/**
 * {@code roleId} (any role visible to the org — system or custom) wins over the legacy {@code role}
 * enum when both are given; both null leaves the role unchanged (AF-522).
 */
public record UpdateUserCommand(
        UserRoleType role,
        UUID roleId,
        Boolean active,
        String displayName,
        Map<String, String> attributes
) {
    /** Legacy convenience constructor for system-role callers. */
    public UpdateUserCommand(UserRoleType role, Boolean active, String displayName,
                             Map<String, String> attributes) {
        this(role, null, active, displayName, attributes);
    }
}
