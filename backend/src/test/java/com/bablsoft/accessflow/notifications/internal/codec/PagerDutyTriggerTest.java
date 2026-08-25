package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagerDutyTriggerTest {

    @Test
    void eventTypeMapping() {
        assertThat(PagerDutyTrigger.CRITICAL_RISK.eventTypes())
                .containsExactly(NotificationEventType.AI_HIGH_RISK);
        assertThat(PagerDutyTrigger.REVIEW_TIMEOUT.eventTypes())
                .containsExactly(NotificationEventType.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.ESCALATION.eventTypes())
                .containsExactly(NotificationEventType.QUERY_ESCALATED);
        assertThat(PagerDutyTrigger.REVIEW_STALLED.eventTypes())
                .containsExactly(NotificationEventType.REVIEW_ESCALATED);
        // #695: one operator knob covers break-glass queries and break-glass deployments.
        assertThat(PagerDutyTrigger.BREAK_GLASS.eventTypes())
                .containsExactlyInAnyOrder(NotificationEventType.BREAK_GLASS_EXECUTED,
                        NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED);
    }

    @Test
    void forEventResolvesMappedEvents() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.AI_HIGH_RISK))
                .contains(PagerDutyTrigger.CRITICAL_RISK);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.REVIEW_TIMEOUT))
                .contains(PagerDutyTrigger.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_ESCALATED))
                .contains(PagerDutyTrigger.ESCALATION);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.REVIEW_ESCALATED))
                .contains(PagerDutyTrigger.REVIEW_STALLED);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED))
                .contains(PagerDutyTrigger.BREAK_GLASS);
    }

    @Test
    void forEventReturnsEmptyForUnmappedEvents() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_SUBMITTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_APPROVED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_REJECTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.TEST)).isEmpty();
        // A reminder is not an incident: REVIEW_NUDGE deliberately has no trigger (#622).
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.REVIEW_NUDGE)).isEmpty();
        // #695: routine deployment lifecycle progress is not an incident.
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_SUBMITTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_APPROVED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_REJECTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.DEPLOYMENT_OUTCOME_FAILED))
                .isEmpty();
    }

    @Test
    void fromConfigParsesCaseInsensitively() {
        assertThat(PagerDutyTrigger.fromConfig("critical_risk"))
                .isEqualTo(PagerDutyTrigger.CRITICAL_RISK);
        assertThat(PagerDutyTrigger.fromConfig(" REVIEW_TIMEOUT "))
                .isEqualTo(PagerDutyTrigger.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.fromConfig("escalation"))
                .isEqualTo(PagerDutyTrigger.ESCALATION);
        assertThat(PagerDutyTrigger.fromConfig("review_stalled"))
                .isEqualTo(PagerDutyTrigger.REVIEW_STALLED);
    }

    @Test
    void fromConfigRejectsBlankOrUnknown() {
        assertThatThrownBy(() -> PagerDutyTrigger.fromConfig(" "))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
        assertThatThrownBy(() -> PagerDutyTrigger.fromConfig("bogus"))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
    }
}
