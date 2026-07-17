package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketingTriggerTest {

    @Test
    void eventTypeMapping() {
        assertThat(TicketingTrigger.QUERY_REJECTED.eventType())
                .isEqualTo(NotificationEventType.QUERY_REJECTED);
        assertThat(TicketingTrigger.REVIEW_TIMEOUT.eventType())
                .isEqualTo(NotificationEventType.REVIEW_TIMEOUT);
        assertThat(TicketingTrigger.QUERY_ESCALATED.eventType())
                .isEqualTo(NotificationEventType.QUERY_ESCALATED);
    }

    @Test
    void forEventResolvesMappedEvents() {
        assertThat(TicketingTrigger.forEvent(NotificationEventType.QUERY_REJECTED))
                .contains(TicketingTrigger.QUERY_REJECTED);
        assertThat(TicketingTrigger.forEvent(NotificationEventType.REVIEW_TIMEOUT))
                .contains(TicketingTrigger.REVIEW_TIMEOUT);
        assertThat(TicketingTrigger.forEvent(NotificationEventType.QUERY_ESCALATED))
                .contains(TicketingTrigger.QUERY_ESCALATED);
    }

    @Test
    void forEventReturnsEmptyForUnmappedEvents() {
        assertThat(TicketingTrigger.forEvent(NotificationEventType.QUERY_SUBMITTED)).isEmpty();
        assertThat(TicketingTrigger.forEvent(NotificationEventType.QUERY_APPROVED)).isEmpty();
        assertThat(TicketingTrigger.forEvent(NotificationEventType.AI_HIGH_RISK)).isEmpty();
        assertThat(TicketingTrigger.forEvent(NotificationEventType.TEST)).isEmpty();
    }

    @Test
    void fromConfigParsesCaseInsensitivelyAndTrims() {
        assertThat(TicketingTrigger.fromConfig("query_rejected"))
                .isEqualTo(TicketingTrigger.QUERY_REJECTED);
        assertThat(TicketingTrigger.fromConfig(" REVIEW_TIMEOUT "))
                .isEqualTo(TicketingTrigger.REVIEW_TIMEOUT);
        assertThat(TicketingTrigger.fromConfig("Query_Escalated"))
                .isEqualTo(TicketingTrigger.QUERY_ESCALATED);
    }

    @Test
    void fromConfigRejectsBlankOrUnknown() {
        assertThatThrownBy(() -> TicketingTrigger.fromConfig(null))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
        assertThatThrownBy(() -> TicketingTrigger.fromConfig(" "))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
        assertThatThrownBy(() -> TicketingTrigger.fromConfig("bogus"))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
    }
}
