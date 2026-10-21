package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Selectable ticket-creation trigger for the ServiceNow / Jira channels (AF-453). A ticketing
 * channel only opens tickets for the triggers an operator enables: {@code QUERY_REJECTED} → a
 * reviewer or routing policy rejected the query, {@code REVIEW_TIMEOUT} → the query auto-rejected
 * after the plan's approval timeout, and {@code QUERY_ESCALATED} → a routing policy escalated the
 * query (raised the approval bar). {@code DEPLOYMENT_SUBMITTED} (#991) is the one trigger that maps
 * two events: a deployment change ticket opens at the first of {@code DEPLOYMENT_SUBMITTED} or
 * {@code DEPLOYMENT_APPROVED}, because an auto-approved deployment never reaches review.
 */
public enum TicketingTrigger {
    QUERY_REJECTED(NotificationEventType.QUERY_REJECTED),
    REVIEW_TIMEOUT(NotificationEventType.REVIEW_TIMEOUT),
    QUERY_ESCALATED(NotificationEventType.QUERY_ESCALATED),
    DEPLOYMENT_SUBMITTED(NotificationEventType.DEPLOYMENT_SUBMITTED,
            NotificationEventType.DEPLOYMENT_APPROVED);

    private final Set<NotificationEventType> eventTypes;

    TicketingTrigger(NotificationEventType first, NotificationEventType... rest) {
        this.eventTypes = EnumSet.of(first, rest);
    }

    public Set<NotificationEventType> eventTypes() {
        return Set.copyOf(eventTypes);
    }

    public static Optional<TicketingTrigger> forEvent(NotificationEventType eventType) {
        for (TicketingTrigger trigger : values()) {
            if (trigger.eventTypes.contains(eventType)) {
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
                            + "' must contain only QUERY_REJECTED, REVIEW_TIMEOUT, "
                            + "QUERY_ESCALATED or DEPLOYMENT_SUBMITTED", ex);
        }
    }
}
