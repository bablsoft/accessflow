package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;

import java.util.Locale;
import java.util.Optional;

/**
 * Selectable ticket-creation trigger for the ServiceNow / Jira channels (AF-453). A ticketing
 * channel only opens tickets for the triggers an operator enables, and each maps to exactly one
 * {@link NotificationEventType}: {@code QUERY_REJECTED} → a reviewer or routing policy rejected
 * the query, {@code REVIEW_TIMEOUT} → the query auto-rejected after the plan's approval timeout,
 * and {@code QUERY_ESCALATED} → a routing policy escalated the query (raised the approval bar).
 */
public enum TicketingTrigger {
    QUERY_REJECTED(NotificationEventType.QUERY_REJECTED),
    REVIEW_TIMEOUT(NotificationEventType.REVIEW_TIMEOUT),
    QUERY_ESCALATED(NotificationEventType.QUERY_ESCALATED);

    private final NotificationEventType eventType;

    TicketingTrigger(NotificationEventType eventType) {
        this.eventType = eventType;
    }

    public NotificationEventType eventType() {
        return eventType;
    }

    public static Optional<TicketingTrigger> forEvent(NotificationEventType eventType) {
        for (TicketingTrigger trigger : values()) {
            if (trigger.eventType == eventType) {
                return Optional.of(trigger);
            }
        }
        return Optional.empty();
    }

    public static TicketingTrigger fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new NotificationChannelConfigException(
                    "Config key '" + ChannelConfigCodec.KEY_TRIGGERS + "' contains a blank value");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotificationChannelConfigException(
                    "Config key '" + ChannelConfigCodec.KEY_TRIGGERS
                            + "' must contain only QUERY_REJECTED, REVIEW_TIMEOUT or "
                            + "QUERY_ESCALATED", ex);
        }
    }
}
