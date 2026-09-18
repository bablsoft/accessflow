package com.bablsoft.accessflow.serviceaccounts.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Input to {@link ServiceAccountAdminService#update}. Every field is null-means-unchanged — a
 * client that omits a field can never widen anything by accident. The declared fields —
 * {@code displayName}, {@code role} / {@code roleId} — are additionally refused when they would
 * change on a BOOTSTRAP account. The UI-owned fields are reset through {@code clear}: naming
 * {@code MCP_TOOL_ALLOW_LIST} there means "every tool" (an empty list means "no tool").
 */
public record UpdateServiceAccountCommand(
        String displayName,
        UserRoleType role,
        UUID roleId,
        Boolean active,
        String description,
        UUID ownerUserId,
        List<String> mcpToolAllowList,
        Integer rateLimitPerMinute,
        Integer rateLimitPerDay,
        Set<ServiceAccountClearableField> clear
) {
    public UpdateServiceAccountCommand {
        mcpToolAllowList = mcpToolAllowList == null ? null : List.copyOf(mcpToolAllowList);
        clear = clear == null ? Set.of() : Set.copyOf(clear);
    }
}
