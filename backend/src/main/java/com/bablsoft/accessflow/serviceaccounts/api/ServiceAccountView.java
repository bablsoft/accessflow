package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The {@code service_accounts} detail row of a service-account user (#868). {@code userId} is the
 * {@code users.id} it extends — there is no separate identity id. {@code mcpToolAllowList} is
 * {@code null} when every tool is allowed and empty when none is (#872); the two rate limits are
 * {@code null} when unlimited (#873). {@code ownerUserId} is the human the account acts for, if
 * any.
 */
public record ServiceAccountView(
        UUID userId,
        UUID organizationId,
        String description,
        UUID ownerUserId,
        ServiceAccountSource managedBy,
        List<String> mcpToolAllowList,
        Integer rateLimitPerMinute,
        Integer rateLimitPerDay,
        Instant createdAt,
        Instant updatedAt
) {
}
