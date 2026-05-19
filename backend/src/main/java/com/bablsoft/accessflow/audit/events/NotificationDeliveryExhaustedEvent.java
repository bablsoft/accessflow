package com.bablsoft.accessflow.audit.events;

import java.util.UUID;

/**
 * Published by the notifications module after a channel exhausts its retry budget. Today only the
 * webhook strategy publishes this — Slack/Discord/Teams/Telegram/Email have different retry shapes
 * and are not yet audited on exhaustion.
 *
 * <p>{@code channelType} and {@code eventType} are carried as strings rather than as the
 * {@code NotificationChannelType} / {@code NotificationEventType} enums so the audit module does
 * not take a compile-time dependency on the notifications module — keeping the module graph a
 * DAG with the audit slice as a sink. The publisher passes {@code Enum.name()}; consumers can
 * round-trip via {@code Enum.valueOf} if needed.
 *
 * <p>Consumed by the audit module's listener which writes a {@code NOTIFICATION_DELIVERY_EXHAUSTED}
 * row with {@code actor_id = NULL}, {@code resource_type = notification_channel}, and
 * {@code metadata.source = "DISPATCHER"} plus channel/event/attempt context.
 */
public record NotificationDeliveryExhaustedEvent(
        UUID organizationId,
        UUID channelId,
        String channelType,
        String eventType,
        int attemptCount,
        Integer lastHttpStatus,
        String lastError) {

    public NotificationDeliveryExhaustedEvent {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (channelId == null) {
            throw new IllegalArgumentException("channelId is required");
        }
        if (channelType == null || channelType.isBlank()) {
            throw new IllegalArgumentException("channelType is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be >= 1");
        }
    }
}
