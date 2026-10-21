package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketingTriggerTest {

    @Test
    void eventTypeMapping() {
        assertThat(TicketingTrigger.QUERY_REJECTED.eventTypes())
                .containsExactly(NotificationEventType.QUERY_REJECTED);
        assertThat(TicketingTrigger.REVIEW_TIMEOUT.eventTypes())
                .containsExactly(NotificationEventType.REVIEW_TIMEOUT);
        assertThat(TicketingTrigger.QUERY_ESCALATED.eventTypes())
                .containsExactly(NotificationEventType.QUERY_ESCALATED);
        assertThat(TicketingTrigger.DEPLOYMENT_SUBMITTED.eventTypes())
                .containsExactlyInAnyOrder(NotificationEventType.DEPLOYMENT_SUBMITTED,
                        NotificationEventType.DEPLOYMENT_APPROVED);
    }

    @Test
    void bothDeploymentOpeningEventsResolveToTheDeploymentTrigger() {
        assertThat(TicketingTrigger.forEvent(NotificationEventType.DEPLOYMENT_SUBMITTED))
                .contains(TicketingTrigger.DEPLOYMENT_SUBMITTED);
        assertThat(TicketingTrigger.forEvent(NotificationEventType.DEPLOYMENT_APPROVED))
                .contains(TicketingTrigger.DEPLOYMENT_SUBMITTED);
        assertThat(TicketingTrigger.forEvent(NotificationEventType.DEPLOYMENT_REJECTED)).isEmpty();
        assertThat(TicketingTrigger.forEvent(
                NotificationEventType.DEPLOYMENT_BREAK_GLASS_EXECUTED)).isEmpty();
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
        assertThat(TicketingTrigger.fromConfig("deployment_submitted"))
                .isEqualTo(TicketingTrigger.DEPLOYMENT_SUBMITTED);
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
                .hasMessageContaining("triggers")
                .hasMessageContaining("DEPLOYMENT_SUBMITTED");
    }
}
