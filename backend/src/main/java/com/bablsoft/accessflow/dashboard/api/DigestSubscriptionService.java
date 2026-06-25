package com.bablsoft.accessflow.dashboard.api;

import java.util.UUID;

/**
 * The current user's opt-in for the scheduled weekly-digest email (AF-498). Server-persisted (the
 * digest job runs server-side, so this can't live in a browser preference). Self-scoped.
 */
public interface DigestSubscriptionService {

    /** The user's current subscription (defaults to disabled when never set). */
    DigestSubscriptionView get(UUID organizationId, UUID userId);

    /** Enable or disable the weekly digest for the user; returns the resulting state. */
    DigestSubscriptionView set(UUID organizationId, UUID userId, boolean enabled);
}
