package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape of a service account. {@code mcpToolAllowList} is {@code null} for every tool and
 * empty for none; {@code apiKeys} is populated on a single-account read and empty on the list.
 */
public record ServiceAccountResponse(
        UUID id,
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
        List<ServiceAccountKeyResponse> apiKeys
) {
    public static ServiceAccountResponse from(ServiceAccountAdminView view) {
        return new ServiceAccountResponse(
                view.id(),
                view.email(),
                view.displayName(),
                view.role(),
                view.roleId(),
                view.roleName(),
                view.active(),
                view.managedBy(),
                view.description(),
                view.ownerUserId(),
                view.mcpToolAllowList(),
                view.rateLimitPerMinute(),
                view.rateLimitPerDay(),
                view.activeApiKeyCount(),
                view.lastUsedAt(),
                view.lastLoginAt(),
                view.createdAt(),
                view.updatedAt(),
                view.apiKeys().stream().map(ServiceAccountKeyResponse::from).toList());
    }
}
