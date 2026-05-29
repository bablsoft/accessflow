package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;

import java.util.Locale;
import java.util.Optional;

/**
 * Selectable PagerDuty trigger. A PagerDuty channel only pages for the triggers an operator
 * enables, and each maps to exactly one {@link NotificationEventType}:
 * {@code CRITICAL_RISK} → {@link NotificationEventType#AI_HIGH_RISK} (the listener fires that
 * event only for {@code CRITICAL} risk) and {@code REVIEW_TIMEOUT} →
 * {@link NotificationEventType#REVIEW_TIMEOUT}.
 */
public enum PagerDutyTrigger {
    CRITICAL_RISK(NotificationEventType.AI_HIGH_RISK),
    REVIEW_TIMEOUT(NotificationEventType.REVIEW_TIMEOUT);

    private final NotificationEventType eventType;

    PagerDutyTrigger(NotificationEventType eventType) {
        this.eventType = eventType;
    }

    public NotificationEventType eventType() {
        return eventType;
    }

    public static Optional<PagerDutyTrigger> forEvent(NotificationEventType eventType) {
        for (PagerDutyTrigger trigger : values()) {
            if (trigger.eventType == eventType) {
                return Optional.of(trigger);
            }
        }
        return Optional.empty();
    }

    public static PagerDutyTrigger fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new NotificationChannelConfigException(
                    "Config key '" + ChannelConfigCodec.KEY_TRIGGERS + "' contains a blank value");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotificationChannelConfigException(
                    "Config key '" + ChannelConfigCodec.KEY_TRIGGERS
                            + "' must contain only CRITICAL_RISK or REVIEW_TIMEOUT", ex);
        }
    }
}
