package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagerDutyTriggerTest {

    @Test
    void eventTypeMapping() {
        assertThat(PagerDutyTrigger.CRITICAL_RISK.eventType())
                .isEqualTo(NotificationEventType.AI_HIGH_RISK);
        assertThat(PagerDutyTrigger.REVIEW_TIMEOUT.eventType())
                .isEqualTo(NotificationEventType.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.ESCALATION.eventType())
                .isEqualTo(NotificationEventType.QUERY_ESCALATED);
    }

    @Test
    void forEventResolvesMappedEvents() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.AI_HIGH_RISK))
                .contains(PagerDutyTrigger.CRITICAL_RISK);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.REVIEW_TIMEOUT))
                .contains(PagerDutyTrigger.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_ESCALATED))
                .contains(PagerDutyTrigger.ESCALATION);
    }

    @Test
    void forEventReturnsEmptyForUnmappedEvents() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_SUBMITTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_APPROVED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.QUERY_REJECTED)).isEmpty();
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.TEST)).isEmpty();
    }

    @Test
    void fromConfigParsesCaseInsensitively() {
        assertThat(PagerDutyTrigger.fromConfig("critical_risk"))
                .isEqualTo(PagerDutyTrigger.CRITICAL_RISK);
        assertThat(PagerDutyTrigger.fromConfig(" REVIEW_TIMEOUT "))
                .isEqualTo(PagerDutyTrigger.REVIEW_TIMEOUT);
        assertThat(PagerDutyTrigger.fromConfig("escalation"))
                .isEqualTo(PagerDutyTrigger.ESCALATION);
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
