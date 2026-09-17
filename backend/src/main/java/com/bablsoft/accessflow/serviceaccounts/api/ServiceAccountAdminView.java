package com.bablsoft.accessflow.serviceaccounts.api;

import com.bablsoft.accessflow.core.api.UserRoleType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A service account as the admin surface sees it (#871): the {@code users} row and the
 * {@code service_accounts} detail row joined, plus a key summary. {@code apiKeys} is populated on
 * a single-account read and empty on the list; {@code activeApiKeyCount} counts keys neither
 * revoked nor expired and {@code lastUsedAt} is the latest use across all keys. {@code role} is the
 * legacy system-role enum (null on a custom role); {@code roleName} is always populated.
 */
public record ServiceAccountAdminView(
        UUID id,
        UUID organizationId,
        String email,
        String displayName,
        UserRoleType role,
        UUID roleId,
        String roleName,
        boolean active,
        ServiceAccountSource managedBy,
        String description,
        UUID ownerUserId,
        List<String> mcpToolAllowList,
        Integer rateLimitPerMinute,
        Integer rateLimitPerDay,
        int activeApiKeyCount,
        Instant lastUsedAt,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        List<ServiceAccountKeyView> apiKeys
) {
    public ServiceAccountAdminView {
        mcpToolAllowList = mcpToolAllowList == null ? null : List.copyOf(mcpToolAllowList);
        apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
    }
}
