package com.bablsoft.accessflow.serviceaccounts.api;

import java.time.Instant;
import java.util.UUID;

/** Grant {@code serviceAccountUserId} the right to act for {@code principalUserId}; {@code expiresAt} null = open-ended (#874). */
public record GrantServiceAccountDelegationCommand(UUID serviceAccountUserId, UUID principalUserId, Instant expiresAt) {
}
