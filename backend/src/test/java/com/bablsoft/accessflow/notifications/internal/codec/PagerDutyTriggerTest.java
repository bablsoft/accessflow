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
    }

    @Test
    void forEventResolvesMappedEvents() {
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.AI_HIGH_RISK))
                .contains(PagerDutyTrigger.CRITICAL_RISK);
        assertThat(PagerDutyTrigger.forEvent(NotificationEventType.REVIEW_TIMEOUT))
                .contains(PagerDutyTrigger.REVIEW_TIMEOUT);
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
