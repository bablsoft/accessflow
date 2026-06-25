package com.bablsoft.accessflow.dashboard.internal.web;

import jakarta.validation.constraints.NotNull;

/** Request body for toggling the weekly-digest opt-in (AF-498). */
public record UpdateDigestSubscriptionRequest(
        @NotNull(message = "{validation.dashboard.digest_enabled_required}") Boolean enabled) {
}
