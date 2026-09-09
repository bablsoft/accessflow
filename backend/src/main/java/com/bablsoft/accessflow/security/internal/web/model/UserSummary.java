package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.UserView;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The signed-in user as every authentication response carries it. {@code governsApis} /
 * {@code governsDeployments} are the organization's governance-domain hints (AF-898) — the
 * frontend's single source of truth for which discovery surfaces (sidebar sub-sections,
 * review-hub tabs, dashboard widgets) it renders (#926). They are visibility only: no route,
 * permission or endpoint authorization derives from them.
 */
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
        String preferredLanguage,
        boolean governsApis,
        boolean governsDeployments
) {
    /** {@code role} carries the effective role NAME (system or custom) — AF-522. */
    public static UserSummary from(UserView view, Set<Permission> permissions,
                                   boolean governsApis, boolean governsDeployments) {
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
                view.preferredLanguage(),
                governsApis,
                governsDeployments);
    }
}
