package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Selectable PagerDuty trigger. A PagerDuty channel only pages for the triggers an operator
 * enables, and each maps to one or more {@link NotificationEventType}s:
 * {@code CRITICAL_RISK} → {@link NotificationEventType#AI_HIGH_RISK} (the listener fires that
 * event only for {@code CRITICAL} risk), {@code REVIEW_TIMEOUT} →
 * {@link NotificationEventType#REVIEW_TIMEOUT}, {@code ANOMALY} →
 * {@link NotificationEventType#ANOMALY_DETECTED} (behavioural anomaly detection, AF-383), and
 * {@code BREAK_GLASS} → {@link NotificationEventType#BREAK_GLASS_EXECUTED} (emergency access,
 * AF-385) plus {@link NotificationEventType#DEPLOYMENT_BREAK_GLASS_EXECUTED} (#695 — a channel
 * that pages for break-glass queries pages for break-glass deployments too, no new operator
 * knob), and {@code ESCALATION} → {@link NotificationEventType#QUERY_ESCALATED} (routing-policy
 * escalation, AF-453), and {@code REVIEW_STALLED} →
 * {@link NotificationEventType#REVIEW_ESCALATED} (nobody decided within the plan's escalation
 * window, #622 — distinct from {@code ESCALATION}, which fires at submission when a routing
 * policy raises the approval bar). {@code REVIEW_NUDGE} deliberately has no trigger: a reminder
 * is not an incident and must never page. The other deployment events
 * ({@code DEPLOYMENT_SUBMITTED}/{@code _APPROVED}/{@code _REJECTED}/{@code _OUTCOME_FAILED})
 * deliberately have no trigger either — routine lifecycle progress is not an incident.
 */
public enum PagerDutyTrigger {
    CRITICAL_RISK(NotificationEventType.AI_HIGH_RISK),
    REVIEW_TIMEOUT(NotificationEventType.REVIEW_TIMEOUT),
    ANOMALY(NotificationEventType.ANOMALY_DETECTED),
    BREAK_GLASS(NotificationEventType.BREAK_GLASS_EXECUTED,
            NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED),
    ESCALATION(NotificationEventType.QUERY_ESCALATED),
    REVIEW_STALLED(NotificationEventType.REVIEW_ESCALATED);

    private final Set<NotificationEventType> eventTypes;

    PagerDutyTrigger(NotificationEventType... eventTypes) {
        this.eventTypes = Set.of(eventTypes);
    }

    public Set<NotificationEventType> eventTypes() {
        return eventTypes;
    }

    public static Optional<PagerDutyTrigger> forEvent(NotificationEventType eventType) {
        for (PagerDutyTrigger trigger : values()) {
            if (trigger.eventTypes.contains(eventType)) {
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
                            + "' must contain only CRITICAL_RISK, REVIEW_TIMEOUT, ANOMALY, "
                            + "BREAK_GLASS, ESCALATION or REVIEW_STALLED", ex);
        }
    }
}
