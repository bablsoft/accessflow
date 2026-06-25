package com.bablsoft.accessflow.dashboard.api;

import java.time.Instant;

/** The current user's weekly-digest subscription state (AF-498). */
public record DigestSubscriptionView(boolean enabled, Instant lastSentAt) {
}
