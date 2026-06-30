package com.bablsoft.accessflow.requestgroups.api;

import java.util.UUID;

/**
 * Narrowing of the group list. {@code submittedByUserId} null = whole org (admins only); everyone
 * else sets it to their own id. {@code status} null = any status.
 */
public record RequestGroupListFilter(
        UUID organizationId,
        UUID submittedByUserId,
        RequestGroupStatus status) {
}
