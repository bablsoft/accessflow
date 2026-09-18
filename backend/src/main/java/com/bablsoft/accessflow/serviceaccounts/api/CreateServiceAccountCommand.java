package com.bablsoft.accessflow.serviceaccounts.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.List;
import java.util.UUID;

/**
 * Input to {@link ServiceAccountAdminService#create}. {@code roleId} (any role visible to the org)
 * wins over the legacy {@code role} enum; both null means {@code READONLY} — deliberately narrow,
 * unlike the bootstrap reconciler's {@code ADMIN} default. {@code mcpToolAllowList} is null for
 * every tool and empty for none.
 */
public record CreateServiceAccountCommand(
        String email,
        String displayName,
        UserRoleType role,
        UUID roleId,
        String description,
        UUID ownerUserId,
        List<String> mcpToolAllowList,
        Integer rateLimitPerMinute,
        Integer rateLimitPerDay
) {
    public CreateServiceAccountCommand {
        mcpToolAllowList = mcpToolAllowList == null ? null : List.copyOf(mcpToolAllowList);
    }
}
