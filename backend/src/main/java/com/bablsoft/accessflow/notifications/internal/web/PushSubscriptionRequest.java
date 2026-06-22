package com.bablsoft.accessflow.notifications.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Mirrors the browser's {@code PushSubscription.toJSON()} shape (AF-444): the push-service
 * {@code endpoint} plus the {@code keys.p256dh} / {@code keys.auth} pair the server needs to
 * encrypt messages. {@code userAgent} is optional and only used for display/diagnostics.
 */
public record PushSubscriptionRequest(
        @NotBlank(message = "{validation.push.endpoint_required}")
        @Size(max = 2048, message = "{validation.push.endpoint_size}")
        String endpoint,
        @NotNull(message = "{validation.push.keys_required}") @Valid Keys keys,
        @Size(max = 512, message = "{validation.push.user_agent_size}") String userAgent) {

    public record Keys(
            @NotBlank(message = "{validation.push.p256dh_required}") String p256dh,
            @NotBlank(message = "{validation.push.auth_required}") String auth) {
    }
}
