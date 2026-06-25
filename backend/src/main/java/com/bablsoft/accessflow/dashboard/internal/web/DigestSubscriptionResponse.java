package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.dashboard.api.DigestSubscriptionView;

import java.time.Instant;

/** API envelope for the user's weekly-digest subscription state (AF-498). */
public record DigestSubscriptionResponse(boolean enabled, Instant lastSentAt) {

    public static DigestSubscriptionResponse from(DigestSubscriptionView view) {
        return new DigestSubscriptionResponse(view.enabled(), view.lastSentAt());
    }
}
