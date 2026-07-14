package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserView;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        UUID roleId,
        List<String> permissions,
        String authProvider,
        boolean totpEnabled,
        boolean platformAdmin,
        String preferredLanguage
) {
    /** {@code role} carries the effective role NAME (system or custom) — AF-522. */
    public static UserSummary from(UserView view, Set<Permission> permissions) {
        return new UserSummary(
                view.id(),
                view.email(),
                view.displayName(),
                view.roleName(),
                view.roleId(),
                permissions.stream().map(Permission::name).sorted().toList(),
                view.authProvider().name(),
                view.totpEnabled(),
                view.platformAdmin(),
                view.preferredLanguage());
    }
}
